package com.ananoesis.shell.approval;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.contract.model.ConversationCreate;
import com.ananoesis.shell.entity.Host;
import com.ananoesis.shell.mapper.HostMapper;
import com.ananoesis.shell.service.ConversationService;
import com.ananoesis.shell.service.SettingsService;
import com.ananoesis.shell.support.TestWait;
import com.ananoesis.shell.ws.ApprovalResponseFrame.Decision;
import com.ananoesis.shell.ws.ToolName;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 审批闸门的挂起、裁决与超时（tasks 8.1 / 8.2 / 8.3 / 8.5）。
 *
 * <h2>WHY 是集成测试而不是纯单元测试</h2>
 * <p>{@code submit} 的第一件事就是往 {@code approvals} 表落一行 pending——
 * spec 要求审计 MUST 记录时间、目标服务器、工具名与参数、AI 分析、用户决定与执行结果，
 * 而「落库了没有」只能用真库验证。用替身 {@code ApprovalAuditService} 的话，
 * 被测的恰恰是那个替身，审计断言会全部变成自证。</p>
 *
 * <h2>WHY 绝不调用 {@code gate.shutdown()}</h2>
 * <p>闸门的调度器与挂起表都在<b>容器单例</b>里，而所有集成测试共用同一个上下文。
 * {@code shutdown()} 会 {@code shutdownNow()} 掉那个单线程调度器，
 * 之后同一 JVM 里的超时用例将永远等不到定时器——症状是"某个用例单独跑能过、全量跑就挂"，
 * 极难定位。因此本类用 {@link #cancelEverythingIssued} 逐个裁决来清理，
 * 只清空挂起表、不动调度器。</p>
 *
 * <h2>WHY 每个用例都必须清理自己签发的凭据</h2>
 * <p>一个没被裁决的条目会让它的超时定时器在<b>两分钟后</b>（种子时限 120 秒）触发。
 * 那时测试早已结束，但调度器线程仍在往审计表写 timeout 行——
 * 后续用例的 {@code COUNT(*)} 断言会被这些"幽灵写入"污染，
 * 而且失败信息看起来像是别的用例算错了数。</p>
 */
class ApprovalGateIntegrationTest extends AbstractSqliteIntegrationTest {

    /** 种子值；用例改了时限后由 {@link #restoreApprovalTimeout} 还原。 */
    private static final String APPROVAL_TIMEOUT_KEY = "approval.timeout.seconds";
    private static final String SEEDED_APPROVAL_TIMEOUT = "120";

    private static final String HOST_LABEL = "审批测试机";
    private static final String COMMAND = "systemctl restart nginx";
    private static final String ANALYSIS = "nginx 已连续 5xx，需要重启以恢复服务";

    @Autowired private ApprovalGate gate;
    @Autowired private ApprovalAuditService audit;
    @Autowired private SettingsService settings;
    @Autowired private ConversationService conversations;
    @Autowired private HostMapper hosts;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DataSource dataSource;

    /** 本用例签发出的全部凭据；@AfterEach 据此清理挂起表。 */
    private final List<ApprovalGate.Ticket> issued = new ArrayList<>();

    private UUID hostId;
    private UUID conversationId;

    @BeforeEach
    void prepareFixtures() {
        issued.clear();
        hostId = insertHost(HOST_LABEL);
        conversationId = conversations.create(new ConversationCreate().hostId(hostId)).getId();
    }

    @AfterEach
    void cancelEverythingIssued() {
        for (ApprovalGate.Ticket ticket : issued) {
            // 返回 false 表示这张已经被用例自己裁决过了，属正常情况
            gate.respond(ticket.approvalId(), Decision.CANCEL);
        }
        issued.clear();
    }

    @AfterEach
    void restoreApprovalTimeout() {
        writeSetting(APPROVAL_TIMEOUT_KEY, SEEDED_APPROVAL_TIMEOUT);
    }

    // ======================================================================
    // 8.1 受理：分配 id、挂起、排定超时
    // ======================================================================

    @Test
    @DisplayName("8.1/Q5：受理返回凭据，时限取自 settings 的当前值，失效时刻 = 受理时刻 + 时限")
    void submitReturnsTicketWhoseTimeoutComesFromSettings() {
        writeSetting(APPROVAL_TIMEOUT_KEY, "37");

        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        assertThat(ticket.approvalId()).isNotNull();
        assertThat(ticket.timeoutSeconds())
                .as("Q5：阈值走 settings 键值，且必须是读到的当前值而不是启动时的快照")
                .isEqualTo(37)
                .isEqualTo(settings.approvalTimeoutSeconds());
        assertThat(ticket.requestedAt()).isNotNull();
        assertThat(ticket.expiresAt()).isEqualTo(ticket.requestedAt().plusSeconds(37));
        assertThat(ticket.proposal().command()).isEqualTo(COMMAND);
        assertThat(gate.pendingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("8.1：每次受理分配不同的 approval_id，且是 canonical UUID 形式（契约 format: uuid）")
    void everySubmissionGetsAFreshCanonicalId() {
        ApprovalGate.Ticket first = submit(proposal(COMMAND));
        ApprovalGate.Ticket second = submit(proposal(COMMAND));

        assertThat(first.approvalId()).isNotEqualTo(second.approvalId());
        // WHY 在意形式：契约把 approval_id 声明为 format: uuid，前端与审计页都按 UUID 解析它。
        // EntityIds.newUuid() 产出的是无连字符的 32 位串，
        // 不经 UUID.fromString(...).toString() 归一就会违约
        assertThat(first.approvalId().toString()).contains("-").hasSize(36);
        assertThat(gate.pendingCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("8.1：受理后条目处于挂起态，await 会一直阻塞到决定到达为止")
    void awaitBlocksUntilADecisionArrives() throws Exception {
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<ApprovalOutcome> observed = new AtomicReference<>();

        Thread waiter = new Thread(() -> {
            started.countDown();
            try {
                observed.set(gate.await(ticket));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "approval-waiter-test");
        waiter.setDaemon(true);
        waiter.start();

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        TestWait.sleep(200);
        assertThat(observed.get())
                .as("没有任何决定时，等待方 MUST 一直挂着——这正是内存挂起的含义")
                .isNull();
        assertThat(waiter.isAlive()).isTrue();

        assertThat(gate.respond(ticket.approvalId(), Decision.APPROVE)).isTrue();
        waiter.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(observed.get()).isEqualTo(ApprovalOutcome.APPROVED);
        assertThat(gate.pendingCount()).isZero();
    }

    // ======================================================================
    // 8.5 审计：pending 行立刻落库
    // ======================================================================

    @Test
    @DisplayName("8.5：受理即落 pending 审计行——不等决定，否则进程被杀就会吞掉证据")
    void submitPersistsPendingRowImmediately() {
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        Map<String, Object> row = readApprovalRow(ticket.approvalId());

        assertThat(row).as("审计行必须在 submit 返回前就已可见").isNotEmpty();
        assertThat(row.get("decision")).isEqualTo("pending");
        assertThat(row.get("decided_by")).isNull();
        assertThat(row.get("decided_at")).isNull();
        assertThat(row.get("execution_status")).isEqualTo("not_executed");
        assertThat(row.get("exit_code")).isNull();
        assertThat(((Number) row.get("output_truncated")).intValue()).isZero();

        // WHY 顺带钉住审计查询：契约的 ApprovalDecision 只有 approved/cancelled/timed_out 三个取值，
        // 压根没有 pending —— 所以 GET /api/approvals 必须把尚未裁决的行排除掉。
        // 不排除的话前端要么渲染不出「用户决定」，要么被迫自造一个契约里不存在的枚举值
        assertThat(audit.page(1, 20, hostId, null).getTotal())
                .as("pending 行不得出现在审计分页查询里")
                .isZero();
    }

    @Test
    @DisplayName("8.5：审计行含 spec 列明的全部要素（时间/目标服务器/工具名与参数/AI 分析）")
    void auditRowCarriesEveryElementRequiredBySpec() throws Exception {
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        Map<String, Object> row = readApprovalRow(ticket.approvalId());

        assertThat(row.get("conversation_id")).isEqualTo(conversationId.toString());
        assertThat(row.get("host_id")).as("目标服务器是 spec 明列的审计要素").isEqualTo(hostId.toString());
        assertThat(row.get("tool_name")).isEqualTo("run_command");
        assertThat(row.get("ai_analysis")).isEqualTo(ANALYSIS);
        assertThat(row.get("requested_at")).isNotNull();
        assertThat(row.get("expires_at")).isNotNull();

        // 工具参数以 JSON 落库，审计页要能原样还原出用户当初批准的是哪条命令
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = objectMapper.readValue(
                (String) row.get("tool_arguments"), Map.class);
        assertThat(arguments).containsEntry("command", COMMAND).containsEntry("ai_analysis", ANALYSIS);
    }

    @Test
    @DisplayName("8.5：审计行不含任何凭据材料（明文口令、密文列、凭据类型都不允许出现）")
    void auditRowCarriesNoCredentialMaterial() throws Exception {
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        Map<String, Object> row = readApprovalRow(ticket.approvalId());
        String serialized = objectMapper.writeValueAsString(row);

        // WHY 这条属于安全验收：spec 与契约双重要求审计 MUST NOT 含明文凭据。
        // 本用例的主机是密码认证（credentials 表里另有密文），
        // 于是把内嵌 SSH 服务器的口令、凭据表的列名与凭据类型字面值都当作探针
        assertThat(serialized)
                .doesNotContain("F4ke-P@ssw0rd")
                .doesNotContain("ciphertext")
                .doesNotContain("ssh_password")
                .doesNotContain("llm_api_key");
    }

    // ======================================================================
    // 8.2 用户裁决
    // ======================================================================

    @Test
    @DisplayName("8.2：批准 → 等待方拿到 APPROVED，审计行记 approved / decided_by=user")
    void approveReleasesTheWaiterAndIsAudited() throws Exception {
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        ApprovalOutcome outcome = awaitWithConcurrentDecision(ticket, Decision.APPROVE);

        assertThat(outcome).isEqualTo(ApprovalOutcome.APPROVED);
        assertThat(outcome.approved()).as("只有 APPROVED 才允许放行命令").isTrue();

        Map<String, Object> row = readApprovalRow(ticket.approvalId());
        assertThat(row.get("decision")).isEqualTo("approved");
        assertThat(row.get("decided_by")).isEqualTo("user");
        assertThat(row.get("decided_at")).isNotNull();
        // 执行结果由 ApprovedCommandRunner 负责写，闸门不该越权
        assertThat(row.get("execution_status")).isEqualTo("not_executed");
        assertThat(gate.pendingCount()).isZero();
    }

    @Test
    @DisplayName("8.2：取消 → 等待方拿到 CANCELLED，审计行记 rejected（库内字面值与契约不同）")
    void cancelReleasesTheWaiterAndIsAuditedAsRejected() throws Exception {
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        ApprovalOutcome outcome = awaitWithConcurrentDecision(ticket, Decision.CANCEL);

        assertThat(outcome).isEqualTo(ApprovalOutcome.CANCELLED);
        assertThat(outcome.approved()).isFalse();

        Map<String, Object> row = readApprovalRow(ticket.approvalId());
        // WHY 断言字面值而不是枚举：DDL 的 CHECK 约束是 pending/approved/rejected/timeout，
        // 契约侧却叫 cancelled。这层换算错了不会有任何报错，只会让审计说谎
        assertThat(row.get("decision")).isEqualTo("rejected");
        assertThat(row.get("decided_by")).isEqualTo("user");
        assertThat(row.get("decided_at")).isNotNull();
        assertThat(gate.pendingCount()).isZero();
    }

    @Test
    @DisplayName("8.2：首个决定胜出，后到的决定被拒——避免「审计记 approved、命令却按取消处理」")
    void firstDecisionWinsAndLaterOnesAreRefused() throws Exception {
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        assertThat(gate.respond(ticket.approvalId(), Decision.CANCEL)).isTrue();
        assertThat(gate.respond(ticket.approvalId(), Decision.APPROVE))
                .as("已裁决的提案不得被第二次裁决")
                .isFalse();
        assertThat(gate.respond(ticket.approvalId(), Decision.CANCEL)).isFalse();

        assertThat(gate.await(ticket)).isEqualTo(ApprovalOutcome.CANCELLED);
        assertThat(readApprovalRow(ticket.approvalId()).get("decision")).isEqualTo("rejected");
    }

    @Test
    @DisplayName("8.2：裁决先于等待到达时仍须被兑现——用户批准了就绝不能被当成超时")
    void decisionArrivingBeforeAwaitIsStillHonoured() throws Exception {
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        // WHY 这个顺序值得单列一个用例：submit 与 await 之间隔着一次
        // ai_stream(tool_call) 的 WebSocket 广播，而那次广播的发送时限是 10 秒。
        // 前端弹框极快、或自动化脚本直接回帧时，裁决完全可能抢在 await 之前完成。
        // 若此时 await 因为「条目已被摘除」而回落成 TIMED_OUT，用户看到的就是：
        // 我明明点了批准，命令却没跑，审计还写着 approved —— 三处彼此矛盾。
        assertThat(gate.respond(ticket.approvalId(), Decision.APPROVE)).isTrue();

        assertThat(gate.await(ticket)).isEqualTo(ApprovalOutcome.APPROVED);
        assertThat(readApprovalRow(ticket.approvalId()).get("decision")).isEqualTo("approved");
    }

    @Test
    @DisplayName("取消先于等待到达时同样被兑现，不会被误判成超时")
    void cancellationArrivingBeforeAwaitIsStillHonoured() throws Exception {
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        assertThat(gate.respond(ticket.approvalId(), Decision.CANCEL)).isTrue();

        assertThat(gate.await(ticket)).isEqualTo(ApprovalOutcome.CANCELLED);
        assertThat(readApprovalRow(ticket.approvalId()).get("decision")).isEqualTo("rejected");
    }

    @Test
    @DisplayName("未知 id 与空参数的裁决请求一律返回 false，不抛异常")
    void unknownAndMalformedResponsesAreRefusedQuietly() {
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        // WHY 必须是 false 而不是异常：上行帧来自浏览器，任何内容都可能出现。
        // 抛异常会沿 WebSocket 处理器上抛并关掉连接——用户点错一次弹框就断线
        assertThat(gate.respond(UUID.randomUUID(), Decision.APPROVE)).isFalse();
        assertThat(gate.respond(null, Decision.APPROVE)).isFalse();
        assertThat(gate.respond(ticket.approvalId(), null)).isFalse();
        assertThat(gate.respond(null, null)).isFalse();

        // 那些无效请求不得影响真正挂着的那一张
        assertThat(gate.pendingCount()).isEqualTo(1);
    }

    // ======================================================================
    // 8.3 超时兜底
    // ======================================================================

    @Test
    @DisplayName("8.3：时限内无决定 → 自动按取消处理，等待方被唤醒、挂起资源被释放")
    void timeoutAutoCancelsAndReleasesResources() throws Exception {
        writeSetting(APPROVAL_TIMEOUT_KEY, "1");
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));
        assertThat(ticket.timeoutSeconds()).isEqualTo(1);

        long startedAt = System.nanoTime();
        ApprovalOutcome outcome = gate.await(ticket);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(outcome).isEqualTo(ApprovalOutcome.TIMED_OUT);
        assertThat(outcome.approved()).as("超时 MUST NOT 放行命令").isFalse();
        assertThat(elapsedMillis)
                .as("应当按时限被唤醒，而不是等满 await 的兜底宽限（时限 + 5 秒）")
                .isBetween(800L, 4_000L);
        assertThat(gate.pendingCount()).as("spec 要求超时后释放挂起资源").isZero();
    }

    @Test
    @DisplayName("8.3/8.5：超时在审计里记 timeout / decided_by=system，与用户主动取消区分开")
    void timeoutIsAttributedToTheSystemInTheAuditRow() throws Exception {
        writeSetting(APPROVAL_TIMEOUT_KEY, "1");
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        assertThat(gate.await(ticket)).isEqualTo(ApprovalOutcome.TIMED_OUT);

        Map<String, Object> row = readApprovalRow(ticket.approvalId());
        assertThat(row.get("decision")).isEqualTo("timeout");
        // WHY 在意 decided_by：spec 要求审计能分辨「人不想要」与「人没来得及看」，
        // 后者是流程缺陷（时限太短或用户离开了），责任归属完全不同
        assertThat(row.get("decided_by")).isEqualTo("system");
        assertThat(row.get("decided_at")).isNotNull();
        assertThat(row.get("execution_status")).isEqualTo("not_executed");
    }

    @Test
    @DisplayName("8.3：用户决定会取消超时定时器——批准得快就不会留下一条 timeout 覆盖 approved")
    void userDecisionCancelsTheTimeoutTask() throws Exception {
        writeSetting(APPROVAL_TIMEOUT_KEY, "2");
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        assertThat(gate.respond(ticket.approvalId(), Decision.APPROVE)).isTrue();
        assertThat(gate.await(ticket)).isEqualTo(ApprovalOutcome.APPROVED);

        // 等过时限，确认定时器没有事后再写一行 timeout
        TestWait.sleep(2_600);

        assertThat(readApprovalRow(ticket.approvalId()).get("decision"))
                .as("已裁决的提案不得被超时任务改写")
                .isEqualTo("approved");
        assertThat(countApprovalRows(ticket.approvalId())).isEqualTo(1);
    }

    @Test
    @DisplayName("8.3：用户决定与超时同时发生时只有一个赢家，且审计与内存结局必须一致")
    void racingDecisionAndTimeoutProduceExactlyOneWinner() throws Exception {
        writeSetting(APPROVAL_TIMEOUT_KEY, "1");
        ApprovalGate.Ticket ticket = submit(proposal(COMMAND));

        // 在时限附近尝试裁决：赢家可能是用户也可能是定时器，两者都合法，
        // 但**必须**只有一个赢家，且 await 的结局与审计行不能自相矛盾
        AtomicReference<Boolean> responded = new AtomicReference<>();
        Thread racer = new Thread(() -> {
            TestWait.sleep(900);
            responded.set(gate.respond(ticket.approvalId(), Decision.APPROVE));
        }, "approval-racer-test");
        racer.setDaemon(true);
        racer.start();

        ApprovalOutcome outcome = gate.await(ticket);
        racer.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(readApprovalRow(ticket.approvalId()).get("decision"))
                .as("审计行必须与等待方拿到的结局一致")
                .isEqualTo(outcome.dbDecision());
        assertThat(outcome).isIn(ApprovalOutcome.APPROVED, ApprovalOutcome.TIMED_OUT);
        if (Boolean.TRUE.equals(responded.get())) {
            assertThat(outcome)
                    .as("respond 返回 true 就意味着它是赢家，结局必须是 APPROVED")
                    .isEqualTo(ApprovalOutcome.APPROVED);
        }
        assertThat(gate.pendingCount()).isZero();
    }

    // ======================================================================
    // Q8：闸门只审副作用命令
    // ======================================================================

    @Test
    @DisplayName("Q8：只读工具压根构造不出提案，因此不可能进入闸门")
    void readOnlyToolsCannotReachTheGate() {
        for (ToolName readOnly : new ToolName[] {ToolName.LIST_DIR, ToolName.READ_FILE, ToolName.SYSTEM_INFO}) {
            assertThatThrownBy(() -> gate.submit(new ApprovalProposal(conversationId, hostId, HOST_LABEL,
                    readOnly, Map.of("path", "/var/log"), null, ANALYSIS, null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不经审批闸门");
        }
        assertThat(gate.pendingCount()).as("被拒的提案不得留下挂起条目").isZero();
        assertThat(countHostApprovalRows()).as("被拒的提案也不得留下审计行").isZero();
    }

    @Test
    @DisplayName("受理 null 提案立刻抛 NPE，不留半成品状态")
    void nullProposalIsRejected() {
        assertThatThrownBy(() -> gate.submit(null)).isInstanceOf(NullPointerException.class);
        assertThat(gate.pendingCount()).isZero();
        assertThat(countHostApprovalRows()).isZero();
    }

    // ======================================================================
    // 并发受理
    // ======================================================================

    @Test
    @DisplayName("并发受理互不干扰：每张凭据各自独立裁决，审计行与结局一一对应")
    void concurrentSubmissionsAreIndependent() throws Exception {
        int total = 8;
        List<ApprovalGate.Ticket> tickets = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            tickets.add(submit(proposal("echo " + i)));
        }
        assertThat(gate.pendingCount()).isEqualTo(total);
        assertThat(countHostApprovalRows()).isEqualTo(total);

        for (int i = 0; i < tickets.size(); i++) {
            ApprovalGate.Ticket ticket = tickets.get(i);
            Decision decision = i % 2 == 0 ? Decision.APPROVE : Decision.CANCEL;
            assertThat(gate.respond(ticket.approvalId(), decision)).isTrue();
        }
        for (ApprovalGate.Ticket ticket : tickets) {
            ApprovalOutcome outcome = gate.await(ticket);
            assertThat(outcome).isIn(ApprovalOutcome.APPROVED, ApprovalOutcome.CANCELLED);
            assertThat(readApprovalRow(ticket.approvalId()).get("decision")).isEqualTo(outcome.dbDecision());
        }
        assertThat(gate.pendingCount()).isZero();
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    /** 受理并登记，供 {@link #cancelEverythingIssued} 清理。 */
    private ApprovalGate.Ticket submit(ApprovalProposal proposal) {
        ApprovalGate.Ticket ticket = gate.submit(proposal);
        issued.add(ticket);
        return ticket;
    }

    private ApprovalProposal proposal(String command) {
        Map<String, Object> params = new HashMap<>();
        params.put("command", command);
        params.put("ai_analysis", ANALYSIS);
        return new ApprovalProposal(conversationId, hostId, HOST_LABEL,
                ToolName.RUN_COMMAND, params, command, ANALYSIS, null);
    }

    /**
     * 在另一个线程上等待，主线程发出决定。
     *
     * <p>WHY 不直接「先 respond 再 await」：那测的是
     * {@code decisionArrivingBeforeAwaitIsStillHonoured} 的形状。真实流程里等待方
     * <b>先</b>阻塞在 future 上、决定<b>后</b>到达，走的是 {@code CompletableFuture}
     * 的唤醒语义，与前者不是同一条代码路径。</p>
     */
    private ApprovalOutcome awaitWithConcurrentDecision(ApprovalGate.Ticket ticket, Decision decision)
            throws Exception {
        CompletableFuture<ApprovalOutcome> future = new CompletableFuture<>();
        Thread waiter = new Thread(() -> {
            try {
                future.complete(gate.await(ticket));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, "approval-await-test");
        waiter.setDaemon(true);
        waiter.start();

        TestWait.until("等待方已进入挂起态", () -> gate.pendingCount() == 1);
        assertThat(gate.respond(ticket.approvalId(), decision)).isTrue();
        return future.get(15, TimeUnit.SECONDS);
    }

    /** 直接往 {@code hosts} 表插一行；外键开着，凭空的 UUID 会被 SQLite 拒掉。 */
    private UUID insertHost(String name) {
        Host row = new Host();
        UUID id = UUID.randomUUID();
        // WHY 显式 setId：实体主键策略是 ASSIGN_UUID，MyBatis-Plus 会生成无连字符的 32 位串，
        // 与全链路使用的 UUID#toString() 对不上，审计页就再也关联不到这台机器
        row.setId(id.toString());
        row.setName(name);
        row.setHost("127.0.0.1");
        row.setPort(22);
        row.setUsername("ops");
        row.setAuthType("password");
        hosts.insert(row);
        return id;
    }

    private void writeSetting(String key, String value) {
        String sql = "UPDATE settings SET setting_value = ? WHERE setting_key = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.setString(2, key);
            assertThat(statement.executeUpdate()).as("种子行 %s 必须存在", key).isEqualTo(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> readApprovalRow(UUID approvalId) {
        String sql = "SELECT conversation_id, host_id, tool_name, tool_arguments, ai_analysis, "
                + "decision, decided_by, requested_at, expires_at, decided_at, "
                + "execution_status, exit_code, output_truncated "
                + "FROM approvals WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, approvalId.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Map.of();
                }
                Map<String, Object> row = new HashMap<>();
                row.put("conversation_id", rs.getString("conversation_id"));
                row.put("host_id", rs.getString("host_id"));
                row.put("tool_name", rs.getString("tool_name"));
                row.put("tool_arguments", rs.getString("tool_arguments"));
                row.put("ai_analysis", rs.getString("ai_analysis"));
                row.put("decision", rs.getString("decision"));
                row.put("decided_by", rs.getString("decided_by"));
                row.put("requested_at", rs.getString("requested_at"));
                row.put("expires_at", rs.getString("expires_at"));
                row.put("decided_at", rs.getString("decided_at"));
                row.put("execution_status", rs.getString("execution_status"));
                row.put("exit_code", rs.getObject("exit_code"));
                row.put("output_truncated", rs.getObject("output_truncated"));
                return row;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private int countApprovalRows(UUID approvalId) {
        return count("SELECT COUNT(*) FROM approvals WHERE id = ?", approvalId.toString());
    }

    /**
     * 统计<b>本用例这台主机</b>名下的审计行数。
     *
     * <p>WHY 不用 {@code SELECT COUNT(*) FROM approvals}：所有集成测试共用同一个 SQLite 文件，
     * 裸计数会把别的测试类留下的行一并算进来，断言就失去了意义（症状是"期望 0 却得到 14"，
     * 而那 14 行与本用例毫无关系）。{@code @BeforeEach} 每次都插一台全新的主机，
     * 按 {@code host_id} 过滤即可白拿隔离性，不必引入事务回滚那套更重的机制。</p>
     */
    private int countHostApprovalRows() {
        return count("SELECT COUNT(*) FROM approvals WHERE host_id = ?", hostId.toString());
    }

    private int count(String sql, String... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
