package com.ananoesis.shell.approval;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.config.SshProperties;
import com.ananoesis.shell.contract.model.ConversationCreate;
import com.ananoesis.shell.contract.model.ToolResultStatus;
import com.ananoesis.shell.entity.Host;
import com.ananoesis.shell.mapper.HostMapper;
import com.ananoesis.shell.security.CredentialProtectionException;
import com.ananoesis.shell.service.ConversationService;
import com.ananoesis.shell.service.HostNotFoundException;
import com.ananoesis.shell.service.SettingsService;
import com.ananoesis.shell.ssh.ExecLimits;
import com.ananoesis.shell.ssh.SshAuthMethod;
import com.ananoesis.shell.ssh.SshConnectException;
import com.ananoesis.shell.ssh.SshConnectionService;
import com.ananoesis.shell.ssh.SshExecService;
import com.ananoesis.shell.ssh.SshFailureKind;
import com.ananoesis.shell.ssh.SshTarget;
import com.ananoesis.shell.ssh.SshTargetResolver;
import com.ananoesis.shell.support.FakeSshServer;
import com.ananoesis.shell.support.SshTestDoubles;
import com.ananoesis.shell.ws.ToolName;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 已批准命令的执行与输出约束（tasks 8.2 / 8.4 / 8.5）。
 *
 * <h2>WHY 是集成测试</h2>
 * <p>本类要同时钉住三件跨层的事实：① 命令真的经 6.5 的 exec 通道落到了远端；
 * ② 执行结果真的写进了 {@code approvals} 审计行；③ 时限与输出上限真的取自
 * {@code settings} 的<b>当前</b>值。这三条都要真 SSH 服务器 + 真 SQLite 才验得出来，
 * 把任何一层换成替身都会让断言退化成"验证我自己写的替身"。</p>
 *
 * <h2>WHY 自己装配 {@code SshExecService} 而不用容器里的那个</h2>
 * <p>容器里的 bean 会拿 {@code hosts} 表的地址与 {@code credentials} 表的密文去连真机器。
 * 这里把解析器换成固定目标、目标换成 {@link FakeSshServer}，其余（阈值、审计）仍用真 bean。</p>
 *
 * <h2>WHY 异常路径用一个"一进门就抛"的解析器</h2>
 * <p>连接失败、主机不存在、凭据保护不可用这三种情况，在真 SSH 服务器上只能靠
 * "关掉服务器 / 删掉主机行 / 破坏密钥环"来制造，既慢又脆弱，而且触发的是
 * sshj 的底层异常而不是被测代码要处理的那三个具体类型。
 * 解析器是 {@code SshExecService} 唯一的可替换缝隙，从这里注入异常
 * 精确命中 {@code ApprovedCommandRunner#run} 的四个 catch 分支。</p>
 */
class ApprovedCommandRunnerTest extends AbstractSqliteIntegrationTest {

    private static final String TIMEOUT_KEY = "run_command.timeout.seconds";
    private static final String MAX_OUTPUT_KEY = "run_command.max_output_bytes";
    private static final String SEEDED_TIMEOUT = "60";
    private static final String SEEDED_MAX_OUTPUT = "65536";

    private static final String HOST_LABEL = "命令执行测试机";
    private static final String ANALYSIS = "重启 nginx 以恢复 5xx";

    /** 内嵌 SSH 服务器的登录口令；用作"明文凭据不得出现在审计里"的探针。 */
    private static final String PASSWORD_PROBE = FakeSshServer.PASSWORD;

    private static FakeSshServer fake;

    @Autowired private ApprovalAuditService audit;
    @Autowired private SettingsService settings;
    @Autowired private ConversationService conversations;
    @Autowired private HostMapper hosts;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private DataSource dataSource;

    private ApprovedCommandRunner runner;
    private UUID hostId;
    private UUID conversationId;
    private UUID approvalId;

    @BeforeAll
    static void startFakeServer() throws IOException {
        fake = FakeSshServer.start();
    }

    @AfterAll
    static void stopFakeServer() {
        fake.close();
    }

    @BeforeEach
    void wireRunner() {
        runner = new ApprovedCommandRunner(execServiceOn(fake), settings, audit);
        hostId = insertHost(HOST_LABEL);
        conversationId = conversations.create(new ConversationCreate().hostId(hostId)).getId();
        approvalId = newApprovedRow("echo ready");
    }

    @AfterEach
    void restoreSettings() {
        writeSetting(TIMEOUT_KEY, SEEDED_TIMEOUT);
        writeSetting(MAX_OUTPUT_KEY, SEEDED_MAX_OUTPUT);
    }

    // ==================================================================
    // 8.4 阈值：每次执行都重新读设置
    // ==================================================================

    @Test
    @DisplayName("8.4/Q5：执行约束取自 settings 的当前值，且每次调用都重读——不是启动快照")
    void limitsAreReadFromSettingsOnEveryCall() {
        writeSetting(TIMEOUT_KEY, "42");
        writeSetting(MAX_OUTPUT_KEY, "4096");

        ExecLimits first = runner.limits();
        assertThat(first.timeout()).isEqualTo(Duration.ofSeconds(42));
        assertThat(first.maxOutputBytes()).isEqualTo(4096);

        // WHY 再改一次：只断言"读到了值"无法发现"构造时缓存了一份"的缺陷。
        // 用户刚在界面上把时限从 42 改成 7，下一条命令却仍按 42 秒跑，
        // 是那种没人会去测、但一旦发生就极难归因的行为
        writeSetting(TIMEOUT_KEY, "7");
        assertThat(runner.limits().timeout()).isEqualTo(Duration.ofSeconds(7));
    }

    // ==================================================================
    // 8.2 正常执行
    // ==================================================================

    @Test
    @DisplayName("8.2：批准后的命令经 exec 通道原样下发，结果写进审计行")
    void approvedCommandRunsOverExecChannelAndIsAudited() {
        ApprovedCommandRunner.Result result = runner.run(approvalId, hostId, "echo keep-me-verbatim");

        assertThat(result.executed()).isTrue();
        assertThat(result.infrastructureError()).isNull();
        assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(result.execution().exitCode()).isZero();
        assertThat(result.execution().stdout()).isEqualTo("keep-me-verbatim\n");
        assertThat(result.execution().stderr()).isEmpty();
        assertThat(result.feedback()).contains("exit=0").contains("keep-me-verbatim");

        // WHY 断言原始 shell 串：这是"命令 MUST NOT 被改写"的唯一硬证据。
        // 若中途被加了引号、被拼接、被 shell 包装，用户批准的就不是实际执行的那条
        assertThat(fake.execCommands()).contains("echo keep-me-verbatim");

        Map<String, Object> row = readRow(approvalId);
        assertThat(row.get("execution_status")).isEqualTo("success");
        assertThat(((Number) row.get("exit_code")).intValue()).isZero();
        assertThat(((Number) row.get("output_truncated")).intValue()).isZero();
        assertThat(row.get("decision")).as("执行结果不得改写用户决定").isEqualTo("approved");
    }

    @Test
    @DisplayName("8.2/8.4：非 0 退出码仍是 success 的工具结果，但审计如实记 failed")
    void nonZeroExitIsStillASuccessfulToolCall() {
        ApprovedCommandRunner.Result result = runner.run(approvalId, hostId, "fail boom");

        // WHY 这个区分很要紧：契约的 ToolResultStatus 没有"命令失败"这一取值。
        // 报成 error 会让模型以为是基础设施坏了而放弃这条线索；
        // 报成 success 并把 exit=3 与 stderr 如实回喂，模型才能继续推理
        assertThat(result.status()).isEqualTo(ToolResultStatus.SUCCESS);
        assertThat(result.execution().exitCode()).isEqualTo(3);
        assertThat(result.feedback()).contains("exit=3").contains("fake-failure: boom");

        assertThat(readRow(approvalId).get("execution_status"))
                .as("库里的 execution_status 记的是执行过程的事实，与契约分类不是同一套取值域")
                .isEqualTo("failed");
    }

    // ==================================================================
    // 8.4 输出上限与执行超时
    // ==================================================================

    @Test
    @DisplayName("8.4：输出超过 run_command.max_output_bytes 被截断，状态与审计都显式标注")
    void oversizedOutputIsTruncatedAndFlagged() {
        writeSetting(MAX_OUTPUT_KEY, "1000");

        ApprovedCommandRunner.Result result = runner.run(approvalId, hostId, "big 20000");

        assertThat(result.status()).isEqualTo(ToolResultStatus.OUTPUT_TRUNCATED);
        assertThat(result.execution().truncated()).isTrue();
        assertThat(result.execution().stdout()).hasSize(1_000);
        assertThat(result.execution().exitCode()).as("截断不改变命令自身的退出码").isZero();
        assertThat(result.feedback())
                .as("模型必须知道它拿到的不是完整输出，否则会基于半截日志下结论")
                .contains("输出超过上限已被截断");

        Map<String, Object> row = readRow(approvalId);
        assertThat(row.get("execution_status")).isEqualTo("truncated");
        assertThat(((Number) row.get("output_truncated")).intValue()).isOne();
    }

    @Test
    @DisplayName("8.4：命令超过 run_command.timeout.seconds 被中断，状态记 execution_timeout")
    void executionTimeoutInterruptsAndIsFlagged() {
        writeSetting(TIMEOUT_KEY, "1");

        long startedAt = System.nanoTime();
        ApprovedCommandRunner.Result result = runner.run(approvalId, hostId, "sleep 5000");
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(result.status()).isEqualTo(ToolResultStatus.EXECUTION_TIMEOUT);
        assertThat(result.execution().timedOut()).isTrue();
        assertThat(elapsedMillis)
                .as("应当在时限附近返回，而不是等满命令自己的 5 秒")
                .isLessThan(4_000L);
        assertThat(result.feedback()).contains("执行超时，命令已被中断");

        Map<String, Object> row = readRow(approvalId);
        assertThat(row.get("execution_status")).isEqualTo("timeout");
        assertThat(((Number) row.get("output_truncated")).intValue())
                .as("超时意味着输出也可能不完整，审计上同样要标注")
                .isOne();
    }

    // ==================================================================
    // 8.2 基础设施失败：不抛异常，变成可回喂的文本
    // ==================================================================

    @Test
    @DisplayName("8.2：主机配置不存在 → 不抛异常，回喂固定文案，审计记 failed")
    void missingHostBecomesAnInfrastructureErrorNotAnException() {
        ApprovedCommandRunner broken = runnerWithResolver(
                throwing(HostNotFoundException.forId(hostId.toString())));

        ApprovedCommandRunner.Result result = broken.run(approvalId, hostId, "echo ready");

        assertThat(result.executed()).isFalse();
        assertThat(result.execution()).isNull();
        assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(result.feedback()).isEqualTo("目标服务器配置不存在或已被删除");
        assertFailureAudited("目标服务器配置不存在或已被删除");
    }

    @Test
    @DisplayName("8.2：连接失败只透出已脱敏的 userMessage，主机坐标与口令一律不外泄")
    void connectFailureOnlyExposesTheSanitisedUserMessage() {
        // detail 刻意塞满敏感坐标：它只该进后端日志，绝不能进回喂给模型的文本
        SshConnectException failure = new SshConnectException(SshFailureKind.HOST_UNREACHABLE,
                "10.20.30.40:2222 user=ops password=" + PASSWORD_PROBE);
        ApprovedCommandRunner broken = runnerWithResolver(throwing(failure));

        ApprovedCommandRunner.Result result = broken.run(approvalId, hostId, "echo ready");

        assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
        // WHY 断言"等于固定文案"而不是"包含"：getMessage() 里全是内部细节，
        // 而这段文本会被回喂给模型，进而可能进入模型服务商的日志
        assertThat(result.feedback()).isEqualTo(SshFailureKind.HOST_UNREACHABLE.userMessage());
        assertThat(result.feedback())
                .doesNotContain(PASSWORD_PROBE)
                .doesNotContain("10.20.30.40")
                .doesNotContain("ops");
        assertFailureAudited(SshFailureKind.HOST_UNREACHABLE.userMessage());
    }

    @Test
    @DisplayName("8.2：凭据保护不可用 → 回喂 spec 规定的提示语并给出下一步")
    void credentialProtectionFailureSurfacesTheSpecMessage() {
        ApprovedCommandRunner broken = runnerWithResolver(
                throwing(CredentialProtectionException.unavailable("主密码未配置", null)));

        ApprovedCommandRunner.Result result = broken.run(approvalId, hostId, "echo ready");

        assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(result.feedback())
                .startsWith(CredentialProtectionException.UNAVAILABLE_MESSAGE)
                .contains("请在设置中配置主密码后重试");
        assertFailureAudited(result.feedback());
    }

    @Test
    @DisplayName("8.2：未预期异常回落到通用文案，异常消息本身不外泄")
    void unexpectedFailureFallsBackToTheGenericMessage() {
        ApprovedCommandRunner broken = runnerWithResolver(
                throwing(new IllegalStateException("内部细节 password=" + PASSWORD_PROBE)));

        ApprovedCommandRunner.Result result = broken.run(approvalId, hostId, "echo ready");

        assertThat(result.status()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(result.feedback())
                .isEqualTo("命令执行失败：服务器内部错误，请查看后端日志获取详情")
                .doesNotContain(PASSWORD_PROBE)
                .doesNotContain("内部细节");
    }

    @Test
    @DisplayName("8.2：run 对 null 参数立刻抛 NPE——审计行 id 缺失时宁可崩也不要写脏数据")
    void nullArgumentsAreRejected() {
        assertThatThrownBy(() -> runner.run(null, hostId, "echo ready"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> runner.run(approvalId, null, "echo ready"))
                .isInstanceOf(NullPointerException.class);
    }

    // ==================================================================
    // 8.5 审计安全
    // ==================================================================

    @Test
    @DisplayName("8.5：执行结果落库后，审计行里没有任何凭据材料")
    void auditRowNeverCarriesCredentialMaterial() throws Exception {
        // 让远端把口令回显出来：这是最坏的情况——凭据经命令输出流进审计表。
        // 闸门与执行器都无法阻止模型跑这样一条命令，但必须保证审计行里
        // 不含**本地**凭据材料（密文列名、凭据类型、SSH 口令字面值）
        runner.run(approvalId, hostId, "echo " + PASSWORD_PROBE);

        Map<String, Object> row = readRow(approvalId);
        String serialized = objectMapper.writeValueAsString(row);

        assertThat(serialized)
                .doesNotContain("ciphertext")
                .doesNotContain("ssh_password")
                .doesNotContain("llm_api_key")
                .doesNotContain("BEGIN OPENSSH PRIVATE KEY");
    }

    @Test
    @DisplayName("8.5：审计行里的 execution_result 是可还原的 JSON，含 stdout/stderr/note 三段")
    void executionResultIsStoredAsStructuredJson() throws Exception {
        runner.run(approvalId, hostId, "echo hello");

        String json = (String) readRow(approvalId).get("execution_result");
        assertThat(json).isNotNull().isNotBlank();

        @SuppressWarnings("unchecked")
        Map<String, Object> stored = objectMapper.readValue(json, Map.class);
        assertThat(stored).containsKeys("stdout", "stderr", "note");
        assertThat(stored.get("stdout")).isEqualTo("hello\n");
        assertThat(stored.get("stderr")).isEqualTo("");
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private void assertFailureAudited(String expectedNote) {
        Map<String, Object> row = readRow(approvalId);
        assertThat(row.get("execution_status")).isEqualTo("failed");
        assertThat(row.get("exit_code")).as("命令没跑过，就不该编造一个退出码").isNull();
        assertThat(String.valueOf(row.get("execution_result"))).contains(expectedNote);
    }

    /** 一个"无论问哪台主机都连到内嵌服务器"的 exec 服务。 */
    private static SshExecService execServiceOn(FakeSshServer server) {
        SshProperties properties = fastProperties();
        SshTarget target = SshTarget.of("127.0.0.1", server.port(), FakeSshServer.USERNAME,
                SshAuthMethod.password(FakeSshServer.PASSWORD));
        return new SshExecService(new SshConnectionService(properties), properties,
                new SshTestDoubles.FixedTargetResolver(target));
    }

    /** 一个"一进门就抛指定异常"的 exec 服务，用于命中四个 catch 分支。 */
    private ApprovedCommandRunner runnerWithResolver(SshTargetResolver resolver) {
        SshProperties properties = fastProperties();
        return new ApprovedCommandRunner(
                new SshExecService(new SshConnectionService(properties), properties, resolver),
                settings, audit);
    }

    private static SshTargetResolver throwing(RuntimeException failure) {
        return new SshTargetResolver() {
            @Override
            public <T> T withTarget(UUID requested, Function<SshTarget, T> action) {
                throw failure;
            }
        };
    }

    /**
     * WHY 不用容器里的 {@code SshProperties}：它的 {@code connectTimeout} 是 15 秒，
     * 一次连不上就要等满，测试会慢到没人愿意跑。
     */
    private static SshProperties fastProperties() {
        SshProperties properties = new SshProperties();
        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setExecTimeout(Duration.ofSeconds(5));
        properties.setExecMaxOutputBytes(65_536);
        properties.setTerminalIdleTimeout(Duration.ofMinutes(5));
        properties.setPtyTerm("xterm-256color");
        properties.setPtyColumns(80);
        properties.setPtyRows(24);
        return properties;
    }

    /** 造一条已批准的审计行，供 {@code run} 写执行结果。 */
    private UUID newApprovedRow(String command) {
        UUID id = UUID.randomUUID();
        ApprovalProposal proposal = new ApprovalProposal(conversationId, hostId, HOST_LABEL,
                ToolName.RUN_COMMAND, Map.of("command", command, "ai_analysis", ANALYSIS),
                command, ANALYSIS, null);
        OffsetDateTime requestedAt = OffsetDateTime.now();
        audit.insertPending(id, proposal, requestedAt, requestedAt.plusSeconds(120));
        audit.recordDecision(id, ApprovalOutcome.APPROVED, OffsetDateTime.now());
        return id;
    }

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

    private Map<String, Object> readRow(UUID id) {
        String sql = "SELECT decision, decided_by, execution_status, exit_code, output_truncated, "
                + "execution_result FROM approvals WHERE id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Map.of();
                }
                Map<String, Object> row = new HashMap<>();
                row.put("decision", rs.getString("decision"));
                row.put("decided_by", rs.getString("decided_by"));
                row.put("execution_status", rs.getString("execution_status"));
                row.put("exit_code", rs.getObject("exit_code"));
                row.put("output_truncated", rs.getObject("output_truncated"));
                row.put("execution_result", rs.getString("execution_result"));
                return row;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
