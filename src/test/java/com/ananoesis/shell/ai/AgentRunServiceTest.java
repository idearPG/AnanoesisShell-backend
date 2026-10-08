package com.ananoesis.shell.ai;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.entity.AiRun;
import com.ananoesis.shell.mapper.AiRunMapper;
import com.ananoesis.shell.service.AgentRunService;

/**
 * 运行账本服务（tasks 6.1）——验证 run 的领取、状态流转与 session 串行裁决。
 *
 * <h2>WHY 是集成测试</h2>
 * <p>{@code ai_runs} 表的唯一索引 {@code (session_id) WHERE status = 'running'}
 * 是 session 串行裁决的核心保障，只有真库才能验证这一约束是否生效。
 * 用替身 mapper 的话，"同时两个 running 被接受"这种最危险的缺陷会被绕过。</p>
 */
class AgentRunServiceTest extends AbstractSqliteIntegrationTest {

    @Autowired private AgentRunService agentRunService;
    @Autowired private AiRunMapper runMapper;
    @Autowired private DataSource dataSource;

    private UUID sessionId;

    @BeforeEach
    void prepareSession() {
        // WHY 直接插 sessions 行：ai_runs.session_id 有外键约束
        sessionId = insertSession();
    }

    // ======================================================================
    // 6.1 run 领取与状态流转
    // ======================================================================

    @Test
    @DisplayName("6.1：领取 run 落 running 行，模型配置快照在 run 开始冻结")
    void claimRunPersistsRunningRowWithFrozenConfig() {
        String modelConfigJson = "{\"provider\":\"openai\",\"model\":\"gpt-4\"}";

        AiRun run = agentRunService.claimRun(sessionId, null, modelConfigJson);

        assertThat(run.getId()).isNotNull();
        assertThat(run.getSessionId()).isEqualTo(sessionId.toString());
        assertThat(run.getStatus()).isEqualTo("running");
        assertThat(run.getModelConfigSnapshot()).isEqualTo(modelConfigJson);
        assertThat(run.getCancellationGeneration()).isEqualTo(0);
        assertThat(run.getContextRecoveryCount()).isEqualTo(0);
        assertThat(run.getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("6.1：同 session 同时只能有一个 running run（数据库唯一索引保障）")
    void sameSessionCannotHaveTwoRunningRuns() {
        agentRunService.claimRun(sessionId, null, "{}");

        // WHY 期望异常而非返回 false：唯一索引冲突会抛 DataAccessException，
        // 调用方需要知道"这个 session 已有 run 在跑"，而不是静默丢弃
        assertThatThrownBy(() -> agentRunService.claimRun(sessionId, null, "{}"))
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("6.1：不同 session 可并行领取 run")
    void differentSessionsCanRunInParallel() {
        UUID session2 = insertSession();

        AiRun run1 = agentRunService.claimRun(sessionId, null, "{}");
        AiRun run2 = agentRunService.claimRun(session2, null, "{}");

        assertThat(run1.getId()).isNotEqualTo(run2.getId());
        assertThat(run1.getSessionId()).isEqualTo(sessionId.toString());
        assertThat(run2.getSessionId()).isEqualTo(session2.toString());
    }

    @Test
    @DisplayName("6.1：完成 run 后 status 变 completed，ended_at 被设置")
    void completeRunSetsStatusAndEndTime() {
        AiRun run = agentRunService.claimRun(sessionId, null, "{}");

        agentRunService.completeRun(run.getId());

        AiRun updated = runMapper.selectById(run.getId());
        assertThat(updated.getStatus()).isEqualTo("completed");
        assertThat(updated.getEndedAt()).isNotNull();
    }

    @Test
    @DisplayName("6.1：失败 run 后 status 变 failed")
    void failRunSetsStatusToFailed() {
        AiRun run = agentRunService.claimRun(sessionId, null, "{}");

        agentRunService.failRun(run.getId());

        AiRun updated = runMapper.selectById(run.getId());
        assertThat(updated.getStatus()).isEqualTo("failed");
        assertThat(updated.getEndedAt()).isNotNull();
    }

    @Test
    @DisplayName("6.1：停止 run 后 status 变 stopped")
    void stopRunSetsStatusToStopped() {
        AiRun run = agentRunService.claimRun(sessionId, null, "{}");

        agentRunService.stopRun(run.getId());

        AiRun updated = runMapper.selectById(run.getId());
        assertThat(updated.getStatus()).isEqualTo("stopped");
        assertThat(updated.getEndedAt()).isNotNull();
    }

    // ======================================================================
    // 6.1 取消代次（为 6.6 停止机制做准备）
    // ======================================================================

    @Test
    @DisplayName("6.1：取消代次初始为 0，递增后返回新值")
    void cancellationGenerationStartsAtZeroAndIncrements() {
        AiRun run = agentRunService.claimRun(sessionId, null, "{}");
        assertThat(run.getCancellationGeneration()).isEqualTo(0);

        int newGen = agentRunService.incrementCancellationGeneration(run.getId());

        assertThat(newGen).isEqualTo(1);
        AiRun updated = runMapper.selectById(run.getId());
        assertThat(updated.getCancellationGeneration()).isEqualTo(1);
    }

    @Test
    @DisplayName("6.1：检查取消——当前代次等于活跃代次时未取消，小于时已取消")
    void isCancelledChecksGenerationAgainstActive() {
        AiRun run = agentRunService.claimRun(sessionId, null, "{}");

        // 代次 0 对活跃代次 0 → 未取消
        assertThat(agentRunService.isCancelled(run.getId(), 0)).isFalse();

        // 递增到 1
        agentRunService.incrementCancellationGeneration(run.getId());

        // 代次 0 < 活跃代次 1 → 已取消
        assertThat(agentRunService.isCancelled(run.getId(), 0)).isTrue();
        // 代次 1 == 活跃代次 1 → 未取消
        assertThat(agentRunService.isCancelled(run.getId(), 1)).isFalse();
    }

    @Test
    @DisplayName("6.1：完成后同 session 可以领取新 run")
    void newRunCanBeClaimedAfterPreviousCompletes() {
        AiRun first = agentRunService.claimRun(sessionId, null, "{}");
        agentRunService.completeRun(first.getId());

        AiRun second = agentRunService.claimRun(sessionId, null, "{}");
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo("running");
    }

    // ======================================================================
    // 辅助
    // ======================================================================

    private static final String NOW = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

    /** 先插 host 再插 session（sessions.host_id 有外键约束），返回 session UUID。 */
    private UUID insertSession() {
        UUID hostId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // WHY 先插 host：sessions.host_id REFERENCES hosts(id)
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
                    sessStmt.setString(1, sessionId.toString());
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
        return sessionId;
    }
}
