package com.ananoesis.shell.security;

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
import com.ananoesis.shell.service.CommandExecutionService;

/**
 * 审计查询与脱敏（tasks 6.7）——验证最终命令/版本/目标快照查询及敏感值遮蔽。
 *
 * <h2>design.md D5 关键约束</h2>
 * <ul>
 *   <li>查询最终命令/版本/目标快照</li>
 *   <li>敏感值遮蔽：命令原文与输出中的密码、token 等必须脱敏</li>
 *   <li>新增记录复用凭据保护与脱敏入口</li>
 *   <li>不得记录控制 token、SSH 登录交互或完整私钥</li>
 * </ul>
 */
class WorkspaceAuditRedactionTest extends AbstractSqliteIntegrationTest {

    @Autowired private AuditRedactionService redactionService;
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
    // 6.7 敏感值遮蔽
    // ======================================================================

    @Test
    @DisplayName("6.7：命令中的 -p 密码参数被遮蔽")
    void passwordInCommandIsRedacted() {
        String command = "mysql -u root -pSuperSecret123 mydb";

        String redacted = redactionService.redactCommand(command);

        assertThat(redacted).doesNotContain("SuperSecret123");
        assertThat(redacted).contains("-p");
        assertThat(redacted).contains("mysql");
    }

    @Test
    @DisplayName("6.7：命令中的 Bearer token 被遮蔽")
    void bearerTokenInCommandIsRedacted() {
        String command = "curl -H 'Authorization: Bearer eyJhbGciOiJIUzI1NiJ9' https://api.example.com";

        String redacted = redactionService.redactCommand(command);

        assertThat(redacted).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(redacted).contains("Bearer");
    }

    @Test
    @DisplayName("6.7：命令中的 API key 参数被遮蔽")
    void apiKeyInCommandIsRedacted() {
        String command = "curl https://api.example.com?key=sk-1234567890abcdef";

        String redacted = redactionService.redactCommand(command);

        assertThat(redacted).doesNotContain("sk-1234567890abcdef");
        assertThat(redacted).contains("key=");
    }

    @Test
    @DisplayName("6.7：输出中的敏感信息被遮蔽")
    void sensitiveOutputIsRedacted() {
        String output = "Connection established\nPassword: MySecretPass\nDone.";

        String redacted = redactionService.redactOutput(output);

        assertThat(redacted).doesNotContain("MySecretPass");
        assertThat(redacted).contains("Connection established");
        assertThat(redacted).contains("Done.");
    }

    @Test
    @DisplayName("6.7：不含敏感信息的命令原样返回")
    void cleanCommandReturnedAsIs() {
        String command = "ls -la /var/log";

        String redacted = redactionService.redactCommand(command);

        assertThat(redacted).isEqualTo(command);
    }

    @Test
    @DisplayName("6.7：空命令和空输出安全处理")
    void emptyInputHandledSafely() {
        assertThat(redactionService.redactCommand("")).isEmpty();
        assertThat(redactionService.redactCommand(null)).isEmpty();
        assertThat(redactionService.redactOutput("")).isEmpty();
        assertThat(redactionService.redactOutput(null)).isEmpty();
    }

    // ======================================================================
    // 6.7 审计快照查询
    // ======================================================================

    @Test
    @DisplayName("6.7：查询执行记录含最终命令与版本快照")
    void auditSnapshotIncludesFinalCommandAndVersion() {
        CommandExecution execution = commandExecutionService.claimExecution(
                "agent_tool", null, "call_audit_1", sessionId, null, "echo hello");
        commandExecutionService.markSent(execution.getId());
        commandExecutionService.markCompleted(execution.getId(), 0, "hello\n", "", false);

        AuditRedactionService.ExecutionSnapshot snapshot =
                redactionService.snapshotOf(execution.getId());

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.command()).isEqualTo("echo hello");
        assertThat(snapshot.status()).isEqualTo("completed");
        assertThat(snapshot.exitCode()).isEqualTo(0);
    }

    @Test
    @DisplayName("6.7：审计快照中命令已脱敏")
    void auditSnapshotCommandIsRedacted() {
        CommandExecution execution = commandExecutionService.claimExecution(
                "agent_tool", null, "call_audit_redact", sessionId, null,
                "mysql -u root -pMyPassword123 mydb");
        commandExecutionService.markSent(execution.getId());
        commandExecutionService.markCompleted(execution.getId(), 0, "", "", false);

        AuditRedactionService.ExecutionSnapshot snapshot =
                redactionService.snapshotOf(execution.getId());

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.redactedCommand()).doesNotContain("MyPassword123");
        assertThat(snapshot.redactedCommand()).contains("mysql");
    }

    @Test
    @DisplayName("6.7：不存在的执行记录返回 null")
    void nonExistentExecutionReturnsNull() {
        AuditRedactionService.ExecutionSnapshot snapshot =
                redactionService.snapshotOf("non-existent-id");

        assertThat(snapshot).isNull();
    }

    // ======================================================================
    // 辅助
    // ======================================================================

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
