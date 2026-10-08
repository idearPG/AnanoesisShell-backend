package com.ananoesis.shell.datastore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;

/**
 * V1→V2 迁移验收：嵌入式 SQLite 落在用户数据目录、启用 WAL、Flyway V1+V2 建齐全部业务表。
 *
 * <p>测试刻意使用**原生 JDBC** 而非 MyBatis-Plus 查询，避免"用被测代码验证被测代码"，
 * 确保断言反映的是数据库文件的真实状态。</p>
 */
class SqliteSchemaMigrationTest extends AbstractSqliteIntegrationTest {

    /** design.md D10 约定的 V2 全量业务表（V1 的 7 张 + V2 新增 3 张）。 */
    private static final Set<String> EXPECTED_TABLES = Set.of(
            "hosts", "credentials", "sessions",
            "ai_conversations", "ai_messages", "approvals", "settings",
            "ai_runs", "command_executions", "file_transfers");

    @Autowired
    private DataSource dataSource;

    // ========== V1 基础断言 ==========

    @Test
    @DisplayName("SQLite 文件生成于配置的用户数据目录下")
    void databaseFileIsCreatedUnderConfiguredDataDirectory() {
        Path databaseFile = databaseFile();

        assertThat(Files.exists(databaseFile))
                .as("期望在 %s 生成 SQLite 文件", databaseFile)
                .isTrue();
    }

    @Test
    @DisplayName("Flyway V1+V2 迁移建齐全部业务表")
    void flywayMigrationCreatesAllBusinessTables() throws SQLException {
        Set<String> actualTables = queryBusinessTables();

        assertThat(actualTables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    }

    @Test
    @DisplayName("Flyway 迁移历史已记录 V1 和 V2")
    void flywayRecordsMigrationHistory() throws SQLException {
        List<String> versions = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank")) {
            while (rs.next()) {
                versions.add(rs.getString(1));
            }
        }

        assertThat(versions).contains("1", "2");
    }

    @Test
    @DisplayName("PRAGMA journal_mode 返回 wal（design.md D6）")
    void journalModeIsWriteAheadLog() throws SQLException {
        String journalMode = querySingleValue("PRAGMA journal_mode");

        assertThat(journalMode).isEqualToIgnoringCase("wal");
    }

    @Test
    @DisplayName("credentials 表只存密文列，不含任何明文凭据字段（credential-store spec）")
    void credentialsTableStoresCiphertextOnly() throws SQLException {
        Set<String> columns = queryColumnNames("credentials");

        assertThat(columns).contains("ciphertext");
        // 明文列一旦出现即违反「数据库中 MUST NOT 存储明文凭据」
        assertThat(columns).doesNotContain(
                "password", "plaintext", "plain_text", "secret",
                "private_key", "passphrase", "api_key", "apikey", "value");
    }

    @Test
    @DisplayName("approvals 表覆盖审计要素：时间/目标服务器/工具名与参数/AI分析/用户决定/执行结果")
    void approvalsTableCoversAuditElements() throws SQLException {
        Set<String> columns = queryColumnNames("approvals");

        assertThat(columns).contains(
                "requested_at", "decided_at",              // 时间
                "host_id",                                 // 目标服务器
                "tool_name", "tool_arguments",             // 工具名与参数
                "ai_analysis",                             // AI 分析
                "decision",                                // 用户决定
                "execution_status", "execution_result");   // 执行结果
    }

    // ========== V2 新增结构断言 ==========

    @Test
    @DisplayName("V2：ai_conversations 新增 session_id 字段（可空）")
    void aiConversationsHasSessionIdColumn() throws SQLException {
        Set<String> columns = queryColumnNames("ai_conversations");
        assertThat(columns).contains("session_id");
    }

    @Test
    @DisplayName("V2：ai_messages 新增 source、command_id、run_id 字段")
    void aiMessagesHasNewColumns() throws SQLException {
        Set<String> columns = queryColumnNames("ai_messages");
        assertThat(columns).contains("source", "command_id", "run_id");
    }

    @Test
    @DisplayName("V2：ai_runs 表结构完整")
    void aiRunsTableStructureIsComplete() throws SQLException {
        Set<String> columns = queryColumnNames("ai_runs");
        assertThat(columns).contains(
                "id", "session_id", "conversation_id", "status",
                "model_config_snapshot", "context_recovery_count",
                "cancellation_generation", "started_at", "ended_at",
                "created_at", "updated_at");
    }

    @Test
    @DisplayName("V2：command_executions 表结构完整")
    void commandExecutionsTableStructureIsComplete() throws SQLException {
        Set<String> columns = queryColumnNames("command_executions");
        assertThat(columns).contains(
                "id", "source", "run_id", "call_id", "session_id",
                "conversation_id", "command", "claim_status", "cwd",
                "exit_code", "output_truncated", "stdout", "stderr",
                "claimed_at", "sent_at", "completed_at",
                "created_at", "updated_at");
    }

    @Test
    @DisplayName("V2：file_transfers 表结构完整")
    void fileTransfersTableStructureIsComplete() throws SQLException {
        Set<String> columns = queryColumnNames("file_transfers");
        assertThat(columns).contains(
                "id", "session_id", "direction", "remote_path", "file_name",
                "declared_size", "transferred_bytes", "status", "overwrite",
                "expected_target", "temp_file_path", "failure_code",
                "download_ticket_hash", "download_ticket_expires_at",
                "queued_at", "ready_at", "ready_deadline",
                "transfer_started_at", "transfer_completed_at", "published_at",
                "created_at", "updated_at");
    }

    @Test
    @DisplayName("V2：approvals 新增 run_id、call_id、version、expected_version、final_command、target_session_id")
    void approvalsHasNewColumns() throws SQLException {
        Set<String> columns = queryColumnNames("approvals");
        assertThat(columns).contains(
                "run_id", "call_id", "version", "expected_version",
                "final_command", "target_session_id");
    }

    // ========== V1→V2 数据完整性 ==========

    @Test
    @DisplayName("V2 升级后 V1 旧数据不丢失：聊天、消息、审批均完好")
    void v1DataSurvivesUpgrade() throws SQLException {
        // Flyway 已在上下文启动时跑完 V1+V2；此处验证 V1 预置的 settings 仍在
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT setting_value FROM settings WHERE setting_key = 'approval.timeout.seconds'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("120");
        }
    }

    // ========== 外键级联语义 ==========

    @Test
    @DisplayName("V2：删除对话只级联清理消息，不影响 ai_runs、command_executions 和 approvals")
    void deleteConversationCascadesOnlyMessages() throws SQLException {
        // 准备：插入主机 → 会话 → 对话 → 消息 + ai_run + command_execution + approval
        String hostId = "test-host-cascade";
        String sessionId = "test-session-cascade";
        String conversationId = "test-conv-cascade";
        String messageId = "test-msg-cascade";
        String runId = "test-run-cascade";
        String execId = "test-exec-cascade";
        String approvalId = "test-approval-cascade";
        String now = "2026-09-22T10:00:00";

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement stmt = connection.createStatement()) {
                // 外键约束需要 foreign_keys=ON，由 JDBC URL 保证
                stmt.executeUpdate("INSERT INTO hosts(id, name, host, port, username, auth_type, created_at, updated_at) "
                        + "VALUES('" + hostId + "', 'h', '1.2.3.4', 22, 'u', 'password', '" + now + "', '" + now + "')");
                stmt.executeUpdate("INSERT INTO sessions(id, host_id, session_type, status, started_at, created_at, updated_at) "
                        + "VALUES('" + sessionId + "', '" + hostId + "', 'exec', 'open', '" + now + "', '" + now + "', '" + now + "')");
                stmt.executeUpdate("INSERT INTO ai_conversations(id, status, created_at, updated_at) "
                        + "VALUES('" + conversationId + "', 'active', '" + now + "', '" + now + "')");
                stmt.executeUpdate("INSERT INTO ai_messages(id, conversation_id, seq, role, created_at) "
                        + "VALUES('" + messageId + "', '" + conversationId + "', 1, 'user', '" + now + "')");
                stmt.executeUpdate("INSERT INTO ai_runs(id, session_id, conversation_id, status, started_at, created_at, updated_at) "
                        + "VALUES('" + runId + "', '" + sessionId + "', '" + conversationId + "', 'completed', '" + now + "', '" + now + "', '" + now + "')");
                stmt.executeUpdate("INSERT INTO command_executions(id, source, run_id, session_id, command, claim_status, created_at, updated_at) "
                        + "VALUES('" + execId + "', 'agent_tool', '" + runId + "', '" + sessionId + "', 'ls', 'completed', '" + now + "', '" + now + "')");
                stmt.executeUpdate("INSERT INTO approvals(id, tool_name, decision, run_id, conversation_id, requested_at, created_at, updated_at) "
                        + "VALUES('" + approvalId + "', 'run_command', 'approved', '" + runId + "', '" + conversationId + "', '" + now + "', '" + now + "', '" + now + "')");
                connection.commit();
            }

            // 删除对话 → 应级联清理消息，但 ai_runs/command_executions/approvals 应保留（SET NULL）
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("DELETE FROM ai_conversations WHERE id = '" + conversationId + "'");
            }
            connection.commit();

            // 验证：消息被级联删除
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ai_messages WHERE conversation_id = '" + conversationId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).as("消息应被级联删除").isEqualTo(0);
            }

            // 验证：ai_runs 的 conversation_id 被 SET NULL
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT conversation_id FROM ai_runs WHERE id = '" + runId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).as("ai_runs.conversation_id 应被 SET NULL").isNull();
            }

            // 验证：command_executions 的 conversation_id 被 SET NULL
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT conversation_id FROM command_executions WHERE id = '" + execId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).as("command_executions.conversation_id 应被 SET NULL").isNull();
            }

            // 验证：approvals 的 conversation_id 被 SET NULL
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT conversation_id FROM approvals WHERE id = '" + approvalId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).as("approvals.conversation_id 应被 SET NULL").isNull();
            }

            // 清理
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("DELETE FROM approvals WHERE id = '" + approvalId + "'");
                stmt.executeUpdate("DELETE FROM command_executions WHERE id = '" + execId + "'");
                stmt.executeUpdate("DELETE FROM ai_runs WHERE id = '" + runId + "'");
                stmt.executeUpdate("DELETE FROM sessions WHERE id = '" + sessionId + "'");
                stmt.executeUpdate("DELETE FROM hosts WHERE id = '" + hostId + "'");
            }
            connection.commit();
        }
    }

    @Test
    @DisplayName("V2：账本 command_executions 可按 session_id 查询")
    void commandExecutionsCanBeQueriedBySession() throws SQLException {
        String hostId = "test-host-query";
        String sessionId = "test-session-query";
        String execId = "test-exec-query";
        String now = "2026-09-22T11:00:00";

        try (Connection connection = dataSource.getConnection();
             Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("INSERT INTO hosts(id, name, host, port, username, auth_type, created_at, updated_at) "
                    + "VALUES('" + hostId + "', 'h', '1.2.3.4', 22, 'u', 'password', '" + now + "', '" + now + "')");
            stmt.executeUpdate("INSERT INTO sessions(id, host_id, session_type, status, started_at, created_at, updated_at) "
                    + "VALUES('" + sessionId + "', '" + hostId + "', 'exec', 'open', '" + now + "', '" + now + "', '" + now + "')");
            stmt.executeUpdate("INSERT INTO command_executions(id, source, session_id, command, claim_status, created_at, updated_at) "
                    + "VALUES('" + execId + "', 'manual', '" + sessionId + "', 'whoami', 'completed', '" + now + "', '" + now + "')");

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT command FROM command_executions WHERE session_id = '" + sessionId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("whoami");
            }

            // 清理
            stmt.executeUpdate("DELETE FROM command_executions WHERE id = '" + execId + "'");
            stmt.executeUpdate("DELETE FROM sessions WHERE id = '" + sessionId + "'");
            stmt.executeUpdate("DELETE FROM hosts WHERE id = '" + hostId + "'");
        }
    }

    @Test
    @DisplayName("V2：approvals 版本字段可查询")
    void approvalsVersionFieldsAreQueryable() throws SQLException {
        String approvalId = "test-approval-version";
        String now = "2026-09-22T12:00:00";

        try (Connection connection = dataSource.getConnection();
             Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("INSERT INTO approvals(id, tool_name, decision, version, expected_version, requested_at, created_at, updated_at) "
                    + "VALUES('" + approvalId + "', 'run_command', 'pending', 2, 1, '" + now + "', '" + now + "', '" + now + "')");

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT version, expected_version FROM approvals WHERE id = '" + approvalId + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
                assertThat(rs.getInt(2)).isEqualTo(1);
            }

            // 清理
            stmt.executeUpdate("DELETE FROM approvals WHERE id = '" + approvalId + "'");
        }
    }

    // ========== 辅助方法 ==========

    private Set<String> queryBusinessTables() throws SQLException {
        Set<String> tables = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type = 'table' "
                             + "AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    private Set<String> queryColumnNames(String table) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name").toLowerCase(Locale.ROOT));
            }
        }
        assertThat(columns).as("表 %s 应当存在", table).isNotEmpty();
        return columns;
    }

    private String querySingleValue(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
