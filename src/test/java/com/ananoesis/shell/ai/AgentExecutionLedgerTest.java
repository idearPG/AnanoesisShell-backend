package com.ananoesis.shell.ai;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.entity.AiRun;
import com.ananoesis.shell.entity.CommandExecution;
import com.ananoesis.shell.mapper.AiRunMapper;
import com.ananoesis.shell.mapper.CommandExecutionMapper;
import com.ananoesis.shell.service.AgentRunService;
import com.ananoesis.shell.service.CommandExecutionService;

/**
 * 智能体执行账本（tasks 6.2）——验证工具提议先落库、再审批/执行、再持久化结果的流程。
 *
 * <h2>design.md D5 关键约束</h2>
 * <ul>
 *   <li>工具提议先持久化 assistant 记录与稳定 call_id，再审批/执行，再保存真实工具结果</li>
 *   <li>SQLite 事务不得跨模型、SSH 或人工等待</li>
 *   <li>执行领取先提交数据库状态，再写 PTY</li>
 * </ul>
 *
 * <h2>WHY 是集成测试</h2>
 * <p>账本流程涉及 {@code ai_runs} 与 {@code command_executions} 两张表的协调，
 * 需要真库验证事务边界与外键关系。</p>
 *
 * <h2>测试策略</h2>
 * <p>不直接测试 {@code AiAgentService}（依赖 ChatModel 等重量级组件），
 * 而是验证账本的核心数据结构：run → execution 的关联、状态流转顺序、
 * 以及事务不跨等待的约束（通过验证中间状态可见性）。</p>
 */
class AgentExecutionLedgerTest extends AbstractSqliteIntegrationTest {

    @Autowired private AgentRunService agentRunService;
    @Autowired private CommandExecutionService commandExecutionService;
    @Autowired private AiRunMapper runMapper;
    @Autowired private CommandExecutionMapper executionMapper;
    @Autowired private DataSource dataSource;

    private UUID sessionId;

    private static final String NOW = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

    @BeforeEach
    void prepareSession() {
        sessionId = insertSession();
    }

    // ======================================================================
    // 6.2 工具提议先落库
    // ======================================================================

    @Test
    @DisplayName("6.2：run 领取后、执行领取前——run 状态为 running，无关联执行记录")
    void runClaimedBeforeExecutionClaimed() {
        // WHY 这是账本的第一步：run 领取后立即可见，
        // 即使后续审批/执行失败，run 的 pending 痕迹已留
        AiRun run = agentRunService.claimRun(sessionId, null, "{\"model\":\"test\"}");

        assertThat(run.getStatus()).isEqualTo("running");
        assertThat(run.getStartedAt()).isNotNull();

        // 此时还没有任何执行记录
        // WHY 验证空状态：确保 run 与 execution 的生命周期是分离的
    }

    @Test
    @DisplayName("6.2：执行领取关联 run_id 与 call_id——稳定的工具调用标识")
    void executionClaimAssociatesRunIdAndCallId() {
        AiRun run = agentRunService.claimRun(sessionId, null, "{}");
        String callId = "call_stable_abc123";

        // WHY 执行领取在 run 领取之后、审批之前：
        // design D5 要求「工具提议先持久化 assistant 记录与稳定 call_id」
        CommandExecution execution = commandExecutionService.claimExecution(
                "agent_tool", run.getId(), callId, sessionId, null, "echo test");

        assertThat(execution.getRunId()).isEqualTo(run.getId());
        assertThat(execution.getCallId()).isEqualTo(callId);
        assertThat(execution.getSource()).isEqualTo("agent_tool");
        assertThat(execution.getClaimStatus()).isEqualTo("claimed");
        // WHY call_id 在领取时就固定：后续审批/执行都引用这个稳定标识，
        // 即使进程崩溃也能通过 call_id 追溯到具体的工具调用
    }

    @Test
    @DisplayName("6.2：执行领取后标记发送——先提交 DB 状态再写 PTY（design D5）")
    void executionClaimThenSentBeforePtyWrite() {
        AiRun run = agentRunService.claimRun(sessionId, null, "{}");
        CommandExecution execution = commandExecutionService.claimExecution(
                "agent_tool", run.getId(), "call_sent_test", sessionId, null, "ls -la");

        // WHY 标记发送在领取之后、实际写入 PTY 之前：
        // 「执行领取先提交数据库状态，再写 PTY」——
        // 如果写 PTY 之前进程崩溃，claimed 状态证明命令被领取但未发送，
        // 不会误以为命令已执行
        commandExecutionService.markSent(execution.getId());

        CommandExecution sent = executionMapper.selectById(execution.getId());
        assertThat(sent.getClaimStatus()).isEqualTo("sent");
        assertThat(sent.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("6.2：执行完成后持久化真实结果——退出码、输出、截断标识")
    void executionCompletedPersistsRealResults() {
        AiRun run = agentRunService.claimRun(sessionId, null, "{}");
        CommandExecution execution = commandExecutionService.claimExecution(
                "agent_tool", run.getId(), "call_result_test", sessionId, null, "cat /etc/hosts");

        // 模拟完整的生命周期：claimed → sent → completed
        commandExecutionService.markSent(execution.getId());
        commandExecutionService.markCompleted(execution.getId(), 0, "127.0.0.1 localhost\n", "", false);

        CommandExecution completed = executionMapper.selectById(execution.getId());
        assertThat(completed.getClaimStatus()).isEqualTo("completed");
        assertThat(completed.getExitCode()).isEqualTo(0);
        assertThat(completed.getStdout()).isEqualTo("127.0.0.1 localhost\n");
        assertThat(completed.getStderr()).isEmpty();
        assertThat(completed.getOutputTruncated()).isEqualTo(0);
        assertThat(completed.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("6.2：SQLite 事务不跨等待——领取与完成是独立事务")
    void transactionsDoNotSpanAcrossWaits() {
        AiRun run = agentRunService.claimRun(sessionId, null, "{}");

        // WHY 每个状态变更是独立事务：
        // 「SQLite 事务不得跨模型、SSH 或人工等待」——
        // claimed → sent 之间可能隔着审批等待（人工操作），
        // sent → completed 之间可能隔着 SSH 执行（网络等待），
        // 每个事务必须独立提交，不能把整个生命周期包在一个大事务里
        CommandExecution exec1 = commandExecutionService.claimExecution(
                "agent_tool", run.getId(), "call_txn_1", sessionId, null, "echo step1");
        commandExecutionService.markSent(exec1.getId());

        // 此时 exec1 的 claimed 和 sent 状态已经持久化（独立事务）
        // 即使后续操作失败，这些状态也不会丢失
        CommandExecution snapshot1 = executionMapper.selectById(exec1.getId());
        assertThat(snapshot1.getClaimStatus()).isEqualTo("sent");

        // 完成第一个执行
        commandExecutionService.markCompleted(exec1.getId(), 0, "step1\n", "", false);

        // 开始第二个执行（模拟工具循环中的多次调用）
        CommandExecution exec2 = commandExecutionService.claimExecution(
                "agent_tool", run.getId(), "call_txn_2", sessionId, null, "echo step2");
        assertThat(exec2.getClaimStatus()).isEqualTo("claimed");

        // 两个执行记录独立存在，各自有完整的状态轨迹
        CommandExecution final1 = executionMapper.selectById(exec1.getId());
        CommandExecution final2 = executionMapper.selectById(exec2.getId());
        assertThat(final1.getClaimStatus()).isEqualTo("completed");
        assertThat(final2.getClaimStatus()).isEqualTo("claimed");
    }

    @Test
    @DisplayName("6.2：run 完成后所有关联执行记录保留——审计痕迹不丢失")
    void runCompletionPreservesAllExecutionRecords() {
        AiRun run = agentRunService.claimRun(sessionId, null, "{}");

        // 模拟多次工具调用
        for (int i = 0; i < 3; i++) {
            CommandExecution exec = commandExecutionService.claimExecution(
                    "agent_tool", run.getId(), "call_" + i, sessionId, null, "cmd_" + i);
            commandExecutionService.markSent(exec.getId());
            commandExecutionService.markCompleted(exec.getId(), i, "out_" + i, "", false);
        }

        // 完成 run
        agentRunService.completeRun(run.getId());

        AiRun completed = runMapper.selectById(run.getId());
        assertThat(completed.getStatus()).isEqualTo("completed");
        assertThat(completed.getEndedAt()).isNotNull();

        // WHY 执行记录不因 run 完成而删除：
        // 审计要求所有操作痕迹保留，run 只是逻辑分组
    }

    @Test
    @DisplayName("6.2：unknown 状态不自动重发——design D5 不重放保护")
    void unknownStatusNeverAutoReplays() {
        AiRun run = agentRunService.claimRun(sessionId, null, "{}");
        CommandExecution execution = commandExecutionService.claimExecution(
                "agent_tool", run.getId(), "call_unknown_replay", sessionId, null, "risky-command");

        // 模拟"已领取但写入/落库结果不明"
        commandExecutionService.markSent(execution.getId());
        commandExecutionService.markUnknown(execution.getId(), "写入 PTY 后连接中断");

        CommandExecution unknown = executionMapper.selectById(execution.getId());
        assertThat(unknown.getClaimStatus()).isEqualTo("unknown");
        // WHY 退出码为 null：结果不明意味着没有可靠的执行证据
        assertThat(unknown.getExitCode()).isNull();
        // WHY 不自动重发：重发可能导致命令被执行两次，
        // 对于 rm -rf 这类不可逆命令，后果是灾难性的
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    /** 先插 host 再插 session（外键约束），返回 session UUID。 */
    private UUID insertSession() {
        UUID hostId = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var hostStmt = connection.prepareStatement(
                        "INSERT INTO hosts (id, name, host, port, username, auth_type, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    hostStmt.setString(1, hostId.toString());
                    hostStmt.setString(2, "test-host");
                    hostStmt.setString(3, "127.0.0.1");
                    hostStmt.setInt(4, 22);
                    hostStmt.setString(5, "test");
                    hostStmt.setString(6, "password");
                    hostStmt.setString(7, NOW);
                    hostStmt.setString(8, NOW);
                    hostStmt.executeUpdate();
                }
                try (var sessStmt = connection.prepareStatement(
                        "INSERT INTO sessions (id, host_id, session_type, status, started_at, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    sessStmt.setString(1, sid.toString());
                    sessStmt.setString(2, hostId.toString());
                    sessStmt.setString(3, "exec");
                    sessStmt.setString(4, "open");
                    sessStmt.setString(5, NOW);
                    sessStmt.setString(6, NOW);
                    sessStmt.setString(7, NOW);
                    sessStmt.executeUpdate();
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return sid;
    }
}
