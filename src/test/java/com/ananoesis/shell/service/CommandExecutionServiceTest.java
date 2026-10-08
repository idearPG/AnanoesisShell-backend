package com.ananoesis.shell.service;

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
import com.ananoesis.shell.entity.CommandExecution;
import com.ananoesis.shell.mapper.CommandExecutionMapper;

/**
 * 命令执行账本服务（tasks 6.3）——验证执行领取、发送、完成及 unknown 标记。
 *
 * <h2>WHY 是集成测试</h2>
 * <p>{@code command_executions} 表的 CHECK 约束与状态流转需要真库验证。
 * 用替身 mapper 无法保障 {@code claim_status} 的 DDL 约束与服务层逻辑一致。</p>
 *
 * <h2>design.md D5 关键约束</h2>
 * <ul>
 *   <li>执行领取先提交 DB 状态，再写 PTY</li>
 *   <li>结果不明只标 unknown，不自动重发</li>
 * </ul>
 */
class CommandExecutionServiceTest extends AbstractSqliteIntegrationTest {

    @Autowired private CommandExecutionService commandExecutionService;
    @Autowired private CommandExecutionMapper executionMapper;
    @Autowired private DataSource dataSource;

    private UUID sessionId;

    private static final String NOW = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

    @BeforeEach
    void prepareSession() {
        sessionId = insertSession();
    }

    // ======================================================================
    // 6.3 执行领取
    // ======================================================================

    @Test
    @DisplayName("6.3：领取执行 → 落 claimed 行，含命令、来源、call_id")
    void claimExecutionPersistsClaimedRow() {
        String callId = "call_abc123";
        String command = "ls -la /tmp";

        CommandExecution execution = commandExecutionService.claimExecution(
                "agent_tool", null, callId, sessionId, null, command);

        assertThat(execution.getId()).isNotNull();
        assertThat(execution.getSource()).isEqualTo("agent_tool");
        assertThat(execution.getCallId()).isEqualTo(callId);
        assertThat(execution.getSessionId()).isEqualTo(sessionId.toString());
        assertThat(execution.getCommand()).isEqualTo(command);
        assertThat(execution.getClaimStatus()).isEqualTo("claimed");
        assertThat(execution.getClaimedAt()).isNotNull();
    }

    @Test
    @DisplayName("6.3：领取后标记发送 → status 变 sent，sent_at 被设置")
    void markSentTransitionsToSentStatus() {
        CommandExecution execution = commandExecutionService.claimExecution(
                "agent_tool", null, "call_sent", sessionId, null, "echo hello");

        commandExecutionService.markSent(execution.getId());

        CommandExecution updated = executionMapper.selectById(execution.getId());
        assertThat(updated.getClaimStatus()).isEqualTo("sent");
        assertThat(updated.getSentAt()).isNotNull();
    }

    @Test
    @DisplayName("6.3：领取后标记完成 → status 变 completed，含退出码和输出")
    void markCompletedTransitionsToCompletedWithResults() {
        CommandExecution execution = commandExecutionService.claimExecution(
                "agent_tool", null, "call_done", sessionId, null, "echo done");

        commandExecutionService.markCompleted(
                execution.getId(), 0, "done\n", "", false);

        CommandExecution updated = executionMapper.selectById(execution.getId());
        assertThat(updated.getClaimStatus()).isEqualTo("completed");
        assertThat(updated.getExitCode()).isEqualTo(0);
        assertThat(updated.getStdout()).isEqualTo("done\n");
        assertThat(updated.getStderr()).isEmpty();
        assertThat(updated.getOutputTruncated()).isEqualTo(0);
        assertThat(updated.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("6.3：结果不明 → 只标 unknown，不自动重发（design D5 不重放保护）")
    void markUnknownOnlyMarksUnknownNeverAutoReplays() {
        CommandExecution execution = commandExecutionService.claimExecution(
                "agent_tool", null, "call_unk", sessionId, null, "risky-command");

        commandExecutionService.markUnknown(execution.getId(), "写入 PTY 后连接中断");

        CommandExecution updated = executionMapper.selectById(execution.getId());
        assertThat(updated.getClaimStatus()).isEqualTo("unknown");
        assertThat(updated.getCompletedAt()).isNotNull();
        // WHY 退出码为 null：结果不明意味着没有可靠的退出证据
        assertThat(updated.getExitCode()).isNull();
    }

    @Test
    @DisplayName("6.3：completed 后不可再标 unknown——终态不可覆盖")
    void terminalStatusCannotBeOverwritten() {
        CommandExecution execution = commandExecutionService.claimExecution(
                "agent_tool", null, "call_terminal", sessionId, null, "echo final");
        commandExecutionService.markCompleted(execution.getId(), 0, "", "", false);

        // WHY 不抛异常而是静默忽略：晚到的状态更新可能是网络延迟造成的，
        // 抛异常会让调用方的清理逻辑失败；记日志并保留已有终态更安全
        commandExecutionService.markUnknown(execution.getId(), "late arrival");

        CommandExecution still = executionMapper.selectById(execution.getId());
        assertThat(still.getClaimStatus()).isEqualTo("completed");
    }

    @Test
    @DisplayName("6.3：manual 来源的命令也可被领取")
    void manualSourceCanBeClaimed() {
        CommandExecution execution = commandExecutionService.claimExecution(
                "manual", null, null, sessionId, null, "manual-cmd");

        assertThat(execution.getSource()).isEqualTo("manual");
        assertThat(execution.getCallId()).isNull();
        assertThat(execution.getClaimStatus()).isEqualTo("claimed");
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
