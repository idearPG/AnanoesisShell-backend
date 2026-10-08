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
import com.ananoesis.shell.mapper.AiRunMapper;
import com.ananoesis.shell.service.AgentRunService;

/**
 * 停止机制（tasks 6.6）——验证取消代次与停止流程。
 *
 * <h2>design.md D5 关键约束</h2>
 * <ul>
 *   <li>停止先改变取消代次并禁止新写入，再取消模型流/待审批</li>
 *   <li>对在途命令发送中断并清算，最后归还安全输入状态</li>
 *   <li>晚到事件无执行权</li>
 * </ul>
 *
 * <h2>WHY 是集成测试</h2>
 * <p>停止机制涉及 {@code ai_runs} 表的取消代次递增与状态流转，
 * 需要真库验证事务一致性。</p>
 */
class AgentStopTest extends AbstractSqliteIntegrationTest {

    @Autowired private AgentRunService agentRunService;
    @Autowired private AiRunMapper runMapper;
    @Autowired private DataSource dataSource;

    private UUID sessionId;

    private static final String NOW = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

    @BeforeEach
    void prepareSession() {
        sessionId = insertSession();
    }

    // ======================================================================
    // 6.6 停止流程
    // ======================================================================

    @Test
    @DisplayName("6.6：停止 run 后状态变 stopped，取消代次递增")
    void stopRunTransitionsToStoppedAndIncrementsCancellation() {
        AiRun run = agentRunService.claimRun(sessionId, null, "{}");
        int initialGen = run.getCancellationGeneration();

        // WHY 先递增取消代次再标记 stopped：
        // 递增代次阻断新写入（正在执行的代码路径会发现代次不匹配而中止），
        // 然后标记 stopped 让后续查询知道这个 run 已经结束
        int newGen = agentRunService.incrementCancellationGeneration(run.getId());
        agentRunService.stopRun(run.getId());

        assertThat(newGen).isEqualTo(initialGen + 1);
        AiRun updated = runMapper.selectById(run.getId());
        assertThat(updated.getStatus()).isEqualTo("stopped");
        assertThat(updated.getCancellationGeneration()).isEqualTo(newGen);
        assertThat(updated.getEndedAt()).isNotNull();
    }

    @Test
    @DisplayName("6.6：停止后旧代次的 isCancelled 返回 true——晚到事件无执行权")
    void afterStopOldGenerationIsCancelled() {
        AiRun run = agentRunService.claimRun(sessionId, null, "{}");
        int callerGen = run.getCancellationGeneration(); // 0

        // 模拟停止：递增代次 + 标记停止
        agentRunService.incrementCancellationGeneration(run.getId());
        agentRunService.stopRun(run.getId());

        // WHY callerGen=0 对 activeGen=1 → 已取消：
        // 正在执行的代码路径持有旧代次，检查时发现活跃代次已大于自己的代次，
        // 就知道有人触发了停止，应当中止当前操作
        assertThat(agentRunService.isCancelled(run.getId(), callerGen)).isTrue();
    }

    @Test
    @DisplayName("6.6：多次停止幂等——已停止的 run 再次停止不抛异常")
    void multipleStopsAreIdempotent() {
        AiRun run = agentRunService.claimRun(sessionId, null, "{}");

        agentRunService.incrementCancellationGeneration(run.getId());
        agentRunService.stopRun(run.getId());

        // WHY 第二次停止不抛异常：停止可能是用户重复点击触发的，
        // 幂等处理比报错更友好——状态已经是 stopped，再写一次也是 stopped
        agentRunService.incrementCancellationGeneration(run.getId());
        agentRunService.stopRun(run.getId());

        AiRun updated = runMapper.selectById(run.getId());
        assertThat(updated.getStatus()).isEqualTo("stopped");
        // 代次递增了两次
        assertThat(updated.getCancellationGeneration()).isEqualTo(2);
    }

    @Test
    @DisplayName("6.6：停止后同 session 可以领取新 run")
    void newRunCanBeClaimedAfterStop() {
        AiRun first = agentRunService.claimRun(sessionId, null, "{}");

        // 停止当前 run
        agentRunService.incrementCancellationGeneration(first.getId());
        agentRunService.stopRun(first.getId());

        // WHY 停止后能领取新 run：stopped 和 completed 一样都是终态，
        // 不再持有 session 级的 running 唯一索引，新 run 可以正常领取
        AiRun second = agentRunService.claimRun(sessionId, null, "{}");
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo("running");
        // 新 run 的取消代次从 0 开始，不受前一个 run 的影响
        assertThat(second.getCancellationGeneration()).isEqualTo(0);
    }

    @Test
    @DisplayName("6.6：取消代次递增阻断新写入——持有旧代次的代码路径检测到取消")
    void cancellationGenerationBlocksNewWrites() {
        AiRun run = agentRunService.claimRun(sessionId, null, "{}");
        int gen0 = run.getCancellationGeneration();

        // 模拟执行过程中被取消
        // 代次 0 对活跃代次 0 → 未取消
        assertThat(agentRunService.isCancelled(run.getId(), gen0)).isFalse();

        // 递增代次（停止的第一步）
        int gen1 = agentRunService.incrementCancellationGeneration(run.getId());

        // 代次 0 < 活跃代次 1 → 已取消，代码路径应当中止
        assertThat(agentRunService.isCancelled(run.getId(), gen0)).isTrue();
        // 代次 1 == 活跃代次 1 → 未取消（新代码路径使用新代次则不受影响）
        assertThat(agentRunService.isCancelled(run.getId(), gen1)).isFalse();
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
