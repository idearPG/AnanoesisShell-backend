package com.ananoesis.shell.controller;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.approval.ApprovalAuditService;
import com.ananoesis.shell.approval.ApprovalOutcome;
import com.ananoesis.shell.approval.ApprovalProposal;
import com.ananoesis.shell.approval.CommandExecution;
import com.ananoesis.shell.contract.model.Approval;
import com.ananoesis.shell.contract.model.ApprovalDecision;
import com.ananoesis.shell.contract.model.ApprovalsPage;
import com.ananoesis.shell.contract.model.ConversationCreate;
import com.ananoesis.shell.contract.model.Error;
import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.contract.model.ExecutionStatus;
import com.ananoesis.shell.entity.Host;
import com.ananoesis.shell.mapper.HostMapper;
import com.ananoesis.shell.service.ConversationService;
import com.ananoesis.shell.ws.ToolName;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * tasks 8.5 的验收（HTTP 侧）：{@code GET /api/approvals} 的审计分页查询。
 *
 * <p>WHY 用<b>生产写入路径</b>（{@code insertPending} → {@code recordDecision} →
 * {@code recordExecution}）造数据，而不是直接 INSERT 一行 approvals：
 * 本测试要验的是「闸门写下的事实」与「接口呈现的事实」是否一致。
 * 自己拼 SQL 造行等于绕开了三段式写入，一旦 {@code recordExecution} 漏写某列，
 * 本测试依然全绿——那正是最容易漏的一类缺陷。</p>
 *
 * <p>WHY 每个用例都新建一台主机、且查询默认带 {@code host_id} 过滤：所有集成测试共享
 * 同一个 Spring 上下文与同一个 SQLite 文件（见 {@link AbstractSqliteIntegrationTest}），
 * 不加过滤就会把别的测试类留下的审计行一并算进 {@code total}，
 * 断言随即依赖 JUnit 的方法执行顺序，出现「单独跑绿、一起跑红」这类最难排查的失败。</p>
 */
class ApprovalsApiIntegrationTest extends AbstractSqliteIntegrationTest {

    /** 种进 {@code credentials} 表的假密文，用于验证审计响应不会把它带出来。 */
    private static final String PLANTED_CIPHERTEXT = "PLANTED-CIPHERTEXT-7d41f0a2c9";

    private static final String HOST_LABEL = "审计接口测试机";
    private static final String OTHER_HOST_LABEL = "另一台机器";
    private static final String ANALYSIS = "重启 nginx 以恢复 5xx，影响面为全站静态资源";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ApprovalAuditService audit;
    @Autowired
    private ConversationService conversations;
    @Autowired
    private HostMapper hosts;

    private UUID hostId;
    private UUID conversationId;

    @BeforeEach
    void seedHostAndConversation() {
        hostId = insertHost(HOST_LABEL);
        conversationId = conversations.create(new ConversationCreate().hostId(hostId)).getId();
    }

    // ======================================================================
    // 响应形状与分页语义
    // ======================================================================

    @Test
    @DisplayName("GET /api/approvals → 200，响应体只含契约声明的四个分页字段")
    void listApprovalsEmitsExactlyContractFields() throws Exception {
        ResponseEntity<String> raw = rest.getForEntity(own(""), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        // WHY 断言字段集合：冻结契约的 ApprovalsPage 只有 items/total/page/size。
        // 擅自加 next_cursor 之类的字段就是破坏契约，前端按契约生成的类型会对不上
        JsonNode tree = objectMapper.readTree(raw.getBody());
        assertThat(tree.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("items", "total", "page", "size");
        assertThat(parse(raw.getBody()).getItems()).isEmpty();
    }

    @Test
    @DisplayName("不传分页参数时使用契约默认值 page=1 / size=20，并原样回显")
    void pagingDefaultsComeFromTheContract() {
        ApprovalsPage page = parse(rest.getForEntity(own(""), String.class).getBody());

        assertThat(page.getPage()).as("契约 defaultValue=1").isEqualTo(1);
        assertThat(page.getSize()).as("契约 defaultValue=20").isEqualTo(20);
        assertThat(page.getTotal()).isZero();
    }

    @Test
    @DisplayName("分页切片不重不漏：三页并集等于全部行，total 恒为总数")
    void pagingSlicesWithoutOverlapOrLoss() {
        for (int i = 0; i < 5; i++) {
            approve(propose("echo slice-" + i));
        }

        ApprovalsPage first = parse(rest.getForEntity(own("&page=1&size=2"), String.class).getBody());
        ApprovalsPage second = parse(rest.getForEntity(own("&page=2&size=2"), String.class).getBody());
        ApprovalsPage third = parse(rest.getForEntity(own("&page=3&size=2"), String.class).getBody());

        assertThat(first.getItems()).hasSize(2);
        assertThat(second.getItems()).hasSize(2);
        assertThat(third.getItems()).as("第 5 条落在第三页").hasSize(1);
        // WHY 断言 total 不随页码变化：把 total 写成「本页条数」是分页最常见的实现错误，
        // 前端据此算出的总页数会变成 1，用户永远翻不到第二页
        assertThat(first.getTotal()).isEqualTo(5L);
        assertThat(second.getTotal()).isEqualTo(5L);
        assertThat(third.getTotal()).isEqualTo(5L);

        Set<UUID> seen = new LinkedHashSet<>();
        first.getItems().forEach(item -> seen.add(item.getId()));
        second.getItems().forEach(item -> seen.add(item.getId()));
        third.getItems().forEach(item -> seen.add(item.getId()));
        assertThat(seen).as("三页不得有重复条目，也不得漏掉条目").hasSize(5);
    }

    @Test
    @DisplayName("size 超过契约上限 200 → 400 validation_error，而不是 500")
    void sizeAboveContractCapIsRejectedAs400() {
        ResponseEntity<String> raw = rest.getForEntity(own("&size=500"), String.class);

        // WHY 这条断言重要：契约的 @Max(200) 只声明在生成的接口上。
        // 若没有对应的异常翻译，Spring 抛出的校验异常会落进兜底分支变成 500 internal_error，
        // 把「客户端传错参数」报告成「服务器故障」，同时污染错误日志与告警
        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Error error = parseError(raw.getBody());
        assertThat(error.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(error.getDetails()).as("应指出是哪个参数越界").isNotEmpty();
        assertThat(error.getDetails().get(0).getField()).isEqualTo("size");
    }

    @Test
    @DisplayName("page=0 违反契约 @Min(1) → 400 validation_error，而不是 500")
    void nonPositivePageIsRejectedAs400() {
        ResponseEntity<String> raw = rest.getForEntity(own("&page=0"), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Error error = parseError(raw.getBody());
        assertThat(error.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        assertThat(error.getDetails()).isNotEmpty();
        assertThat(error.getDetails().get(0).getField()).isEqualTo("page");
    }

    // ======================================================================
    // 过滤
    // ======================================================================

    @Test
    @DisplayName("尚未裁决的 pending 行不出现在审计视图里（契约 ApprovalDecision 无 pending 取值）")
    void pendingRowsAreExcludedFromTheAuditView() {
        UUID approvalId = propose("echo still-waiting");

        assertThat(ownPage().getTotal())
                .as("pending 不是一个结局，硬映射到任一契约取值都是让审计说谎")
                .isZero();

        // 裁决之后立刻可见：证明排除的是「状态」而不是「整条行」
        approve(approvalId);
        ApprovalsPage page = ownPage();
        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getItems().get(0).getDecision()).isEqualTo(ApprovalDecision.APPROVED);
    }

    @Test
    @DisplayName("decision 过滤：三个契约取值各自只命中对应的库字面值")
    void decisionFilterMatchesEachContractValue() {
        approve(propose("echo approved-one"));
        cancel(propose("echo cancelled-one"));
        timeOut(propose("echo timed-out-one"));

        // WHY 三个取值都要走一遍：库里的字面值与契约取值并不同名
        // （cancelled 在库里叫 rejected、timed_out 叫 timeout），
        // 换算写反时只看其中一个取值是发现不了的
        assertSingleHit("approved", ApprovalDecision.APPROVED, "echo approved-one");
        assertSingleHit("cancelled", ApprovalDecision.CANCELLED, "echo cancelled-one");
        assertSingleHit("timed_out", ApprovalDecision.TIMED_OUT, "echo timed-out-one");

        assertThat(ownPage().getTotal()).as("不带 decision 时应看到全部三条").isEqualTo(3L);
    }

    @Test
    @DisplayName("host_id 过滤把本用例的行与其他主机上的行隔开")
    void hostIdFilterIsolatesRowsPerHost() {
        approve(propose("echo mine"));
        UUID otherHost = insertHost(OTHER_HOST_LABEL);
        UUID otherConversation = conversations.create(new ConversationCreate().hostId(otherHost)).getId();
        approve(proposeOn(otherHost, otherConversation, "echo theirs"));

        ApprovalsPage mine = ownPage();
        assertThat(mine.getTotal()).isEqualTo(1L);
        assertThat(mine.getItems().get(0).getHostLabel()).isEqualTo(HOST_LABEL);
        assertThat(mine.getItems().get(0).getHostId()).isEqualTo(hostId);

        ApprovalsPage theirs = parse(rest.getForEntity(
                "/api/approvals?host_id=" + otherHost, String.class).getBody());
        assertThat(theirs.getTotal()).isEqualTo(1L);
        assertThat(theirs.getItems().get(0).getHostLabel()).isEqualTo(OTHER_HOST_LABEL);
    }

    @Test
    @DisplayName("decision 传契约外的取值 → 400 validation_error，且不复述用户输入")
    void unknownDecisionValueIsRejectedAs400() {
        ResponseEntity<String> raw = rest.getForEntity(own("&decision=pending"), String.class);

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Error error = parseError(raw.getBody());
        assertThat(error.getCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        // WHY 断言不复述：把非法输入原样拼回响应既可用于探测解析器，也会让前端 bug 变成怪异的 4xx 文案
        assertThat(error.getMessage()).doesNotContain("pending");
    }

    // ======================================================================
    // 审计要素（command-approval spec「审批审计日志」）
    // ======================================================================

    @Test
    @DisplayName("已批准并执行成功的行：spec 要求的六项审计要素齐全")
    void approvedExecutionCarriesEveryAuditElementRequiredBySpec() throws Exception {
        UUID approvalId = propose("echo audit-me");
        approve(approvalId);
        audit.recordExecution(approvalId, new CommandExecution(0, "audit-me\n", "", false, false));

        Approval item = onlyItem();

        assertThat(item.getId()).isEqualTo(approvalId);
        assertThat(item.getCreatedAt()).as("要素：时间").isNotNull();
        assertThat(item.getHostId()).as("要素：目标服务器").isEqualTo(hostId);
        assertThat(item.getHostLabel()).isEqualTo(HOST_LABEL);
        assertThat(item.getToolName()).as("要素：工具名").isEqualTo(ToolName.RUN_COMMAND.getValue());
        assertThat(item.getToolParams()).as("要素：工具参数").containsEntry("command", "echo audit-me");
        assertThat(item.getAiAnalysis()).as("要素：AI 分析").isEqualTo(ANALYSIS);
        assertThat(item.getDecision()).as("要素：用户决定").isEqualTo(ApprovalDecision.APPROVED);
        assertThat(item.getDecidedAt()).isNotNull();
        assertThat(item.getExecution()).as("要素：执行结果").isNotNull();
        assertThat(item.getExecution().getStatus()).isEqualTo(ExecutionStatus.EXECUTED);
        assertThat(item.getExecution().getExitCode()).isZero();
        assertThat(item.getExecution().getStdout()).isEqualTo("audit-me\n");
        assertThat(item.getExecution().getTruncated()).isFalse();

        // WHY 顺带钉住单条 item 的字段集合：与 ApprovalsPage 同理，多一个字段就是破坏契约
        JsonNode tree = objectMapper.readTree(objectMapper.writeValueAsString(item));
        assertThat(tree.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "id", "created_at", "host_id", "host_label", "tool_name",
                "tool_params", "ai_analysis", "decision", "decided_at", "execution");
    }

    @Test
    @DisplayName("用户取消 → execution.status=rejected、note=「用户已拒绝」、没有退出码")
    void cancelledRowIsReportedAsRejectedWithTheSpecNote() {
        cancel(propose("echo never-run"));

        Approval item = onlyItem();
        assertThat(item.getDecision()).isEqualTo(ApprovalDecision.CANCELLED);
        assertThat(item.getExecution().getStatus()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(item.getExecution().getNote()).isEqualTo(ApprovalAuditService.REJECTED_NOTE);
        // WHY 断言退出码为 null：命令根本没跑，编一个 0 出来会让用户以为它成功执行过
        assertThat(item.getExecution().getExitCode()).isNull();
        assertThat(item.getExecution().getStdout()).isEmpty();
    }

    @Test
    @DisplayName("审批超时 → execution.status=rejected、note=「审批超时，已自动取消」")
    void timedOutRowIsReportedAsRejectedWithTheTimeoutNote() {
        timeOut(propose("echo never-run"));

        Approval item = onlyItem();
        assertThat(item.getDecision()).isEqualTo(ApprovalDecision.TIMED_OUT);
        assertThat(item.getExecution().getStatus()).isEqualTo(ExecutionStatus.REJECTED);
        assertThat(item.getExecution().getNote()).isEqualTo(ApprovalAuditService.TIMED_OUT_NOTE);
    }

    @Test
    @DisplayName("执行超时与输出截断映射到契约各自的 ExecutionStatus，并置 truncated=true")
    void timeoutAndTruncationMapToDedicatedExecutionStatuses() {
        UUID timedOut = propose("sleep 600");
        approve(timedOut);
        audit.recordExecution(timedOut, new CommandExecution(-1, "partial\n", "", false, true));

        UUID truncated = propose("cat huge.log");
        approve(truncated);
        audit.recordExecution(truncated, new CommandExecution(0, "AAAA", "", true, false));

        Approval interrupted = find(timedOut);
        assertThat(interrupted.getExecution().getStatus()).isEqualTo(ExecutionStatus.EXECUTION_TIMEOUT);
        assertThat(interrupted.getExecution().getTruncated())
                .as("被中断时输出同样不完整，必须一并标注").isTrue();
        assertThat(interrupted.getExecution().getNote()).contains("超时");

        Approval capped = find(truncated);
        assertThat(capped.getExecution().getStatus()).isEqualTo(ExecutionStatus.OUTPUT_TRUNCATED);
        assertThat(capped.getExecution().getTruncated()).isTrue();
        assertThat(capped.getExecution().getNote()).contains("截断");
    }

    @Test
    @DisplayName("命令返回非 0 退出码仍是 executed：契约没有「命令失败」这一档")
    void nonZeroExitIsStillReportedAsExecuted() {
        UUID approvalId = propose("fail boom");
        approve(approvalId);
        audit.recordExecution(approvalId, new CommandExecution(3, "", "fake-failure: boom\n", false, false));

        Approval item = onlyItem();
        // WHY 这条断言重要：若把非 0 退出码报成 rejected，用户会以为命令没执行，
        // 而它其实已经在目标机器上跑过了——这是最危险的一种误报
        assertThat(item.getExecution().getStatus()).isEqualTo(ExecutionStatus.EXECUTED);
        assertThat(item.getExecution().getExitCode()).isEqualTo(3);
        assertThat(item.getExecution().getStderr()).isEqualTo("fake-failure: boom\n");
    }

    @Test
    @DisplayName("主机被删除后审计行仍在，只是 host_label 变为 null（留痕不因删除而消失）")
    void auditRowSurvivesHostDeletionWithANullLabel() {
        UUID approvalId = propose("echo outlive-the-host");
        approve(approvalId);

        hosts.deleteById(hostId.toString());

        // WHY 这里不能用带 host_id 过滤的查询：application.yml 打开了 foreign_keys=on，
        // approvals.host_id 上的 ON DELETE SET NULL 已把这一列置空——
        // 这正是「审计行不因删机器而消失」的实现方式，也是它无法再按主机筛出的原因
        Approval item = findAnywhere(approvalId);
        assertThat(item.getHostLabel()).as("主机已不存在，展示名只能是 null").isNull();
        assertThat(item.getHostId()).as("外键 ON DELETE SET NULL 已生效").isNull();
        assertThat(item.getToolParams()).containsEntry("command", "echo outlive-the-host");
        assertThat(item.getDecision()).isEqualTo(ApprovalDecision.APPROVED);
    }

    // ======================================================================
    // 安全
    // ======================================================================

    @Test
    @DisplayName("审计响应不含凭据材料：种进 credentials 表的密文不得出现在响应体里")
    void responseNeverCarriesCredentialMaterial() {
        plantCredential();
        approve(propose("systemctl restart nginx"));

        ResponseEntity<String> raw = rest.getForEntity(own(""), String.class);
        String body = raw.getBody() == null ? "" : raw.getBody();

        assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body)
                .as("响应体不得带出凭据表的任何内容")
                .doesNotContain(PLANTED_CIPHERTEXT)
                .doesNotContain("ciphertext")
                .doesNotContain("ssh_password")
                .doesNotContain("llm_api_key")
                .doesNotContain("BEGIN OPENSSH PRIVATE KEY");
        // 反面断言：证明这条请求确实取到了那一行（否则上面几条 doesNotContain 是空转）
        assertThat(onlyItem().getToolParams()).containsEntry("command", "systemctl restart nginx");
    }

    // ======================================================================
    // 辅助：造数据（全部走生产写入路径）
    // ======================================================================

    /** 落一条 pending 审计行，返回 approval_id。 */
    private UUID propose(String command) {
        return proposeOn(hostId, conversationId, command);
    }

    private UUID proposeOn(UUID host, UUID conversation, String command) {
        UUID approvalId = UUID.randomUUID();
        OffsetDateTime requestedAt = OffsetDateTime.now();
        // WHY 时限写死 120 而不取 settings：本测试只关心审计行的呈现。
        // 「阈值随 settings 当前值变化」由 ApprovedCommandRunnerTest 与 SettingsApiIntegrationTest 各自钉住
        audit.insertPending(approvalId,
                new ApprovalProposal(conversation, host, HOST_LABEL, ToolName.RUN_COMMAND,
                        Map.of("command", command), command, ANALYSIS, null),
                requestedAt, requestedAt.plusSeconds(120));
        return approvalId;
    }

    private void approve(UUID approvalId) {
        audit.recordDecision(approvalId, ApprovalOutcome.APPROVED, OffsetDateTime.now());
    }

    private void cancel(UUID approvalId) {
        audit.recordDecision(approvalId, ApprovalOutcome.CANCELLED, OffsetDateTime.now());
    }

    private void timeOut(UUID approvalId) {
        audit.recordDecision(approvalId, ApprovalOutcome.TIMED_OUT, OffsetDateTime.now());
    }

    // ======================================================================
    // 辅助：查询与断言
    // ======================================================================

    /** 本用例主机的审计页（带 host_id 过滤，避开其他测试类留下的行）。 */
    private ApprovalsPage ownPage() {
        return parse(rest.getForEntity(own(""), String.class).getBody());
    }

    private Approval onlyItem() {
        ApprovalsPage page = ownPage();
        assertThat(page.getItems()).as("期望恰好一条审计行").hasSize(1);
        return page.getItems().get(0);
    }

    private Approval find(UUID approvalId) {
        List<Approval> hits = matching(ownPage(), approvalId);
        assertThat(hits).as("审计页里应能找到 " + approvalId).hasSize(1);
        return hits.get(0);
    }

    /**
     * 在全库范围内按 id 找一条审计行（不按主机过滤）。
     *
     * <p>WHY 取 size=200（契约上限）：本方法只服务于「主机已被删除」这一个用例，
     * 那时 host_id 已被外键置空、无从过滤。新写入的行按 requested_at 倒序排在最前，
     * 因此第一页必然覆盖它。</p>
     */
    private Approval findAnywhere(UUID approvalId) {
        ApprovalsPage page = parse(rest.getForEntity("/api/approvals?size=200", String.class).getBody());
        List<Approval> hits = matching(page, approvalId);
        assertThat(hits).as("审计页里应能找到 " + approvalId).hasSize(1);
        return hits.get(0);
    }

    private static List<Approval> matching(ApprovalsPage page, UUID approvalId) {
        List<Approval> hits = new ArrayList<>();
        for (Approval item : page.getItems()) {
            if (approvalId.equals(item.getId())) {
                hits.add(item);
            }
        }
        return hits;
    }

    private void assertSingleHit(String wireValue, ApprovalDecision expected, String expectedCommand) {
        ApprovalsPage page = parse(rest.getForEntity(own("&decision=" + wireValue), String.class).getBody());
        assertThat(page.getTotal()).as("decision=" + wireValue).isEqualTo(1L);
        assertThat(page.getItems().get(0).getDecision()).isEqualTo(expected);
        assertThat(page.getItems().get(0).getToolParams()).containsEntry("command", expectedCommand);
    }

    /** 带 host_id 过滤的审计页 URL。 */
    private String own(String extraQuery) {
        return "/api/approvals?host_id=" + hostId + extraQuery;
    }

    private ApprovalsPage parse(String body) {
        try {
            return objectMapper.readValue(body, ApprovalsPage.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析响应体: " + body, e);
        }
    }

    private Error parseError(String body) {
        try {
            return objectMapper.readValue(body, Error.class);
        } catch (Exception e) {
            throw new IllegalStateException("无法解析错误响应体: " + body, e);
        }
    }

    // ======================================================================
    // 辅助：直接写库
    // ======================================================================

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

    /**
     * 往 {@code credentials} 表种一行可识别的密文。
     *
     * <p>WHY 不用 {@code CredentialStoreService}：那会真的走系统密钥环加密，
     * 得到的密文每次不同、无法在断言里比对；本测试只需要一个
     * 「确定存在于库中、且绝不该出现在 HTTP 响应里」的字符串。</p>
     */
    private void plantCredential() {
        String sql = "INSERT OR REPLACE INTO credentials "
                + "(id, owner_type, owner_id, credential_type, ciphertext, created_at, updated_at) "
                + "VALUES (?, 'host', ?, 'ssh_password', ?, datetime('now'), datetime('now'))";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, hostId.toString());
            statement.setString(3, PLANTED_CIPHERTEXT);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("无法种入测试凭据行", e);
        }
    }
}
