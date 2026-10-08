package com.ananoesis.shell.service;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ananoesis.shell.entity.AiRun;
import com.ananoesis.shell.mapper.AiRunMapper;
import com.ananoesis.shell.support.EntityIds;

/**
 * 运行账本服务（tasks 6.1 / 6.6）——管理 {@code ai_runs} 表的生命周期。
 *
 * <h2>核心职责</h2>
 * <ul>
 *   <li>领取 run：以数据库唯一索引保障同一 session 同时只有一个 running run；</li>
 *   <li>状态流转：running → completed / failed / stopped / unknown；</li>
 *   <li>取消代次：递增代次以阻断新写入（为 6.6 停止机制服务）；</li>
 *   <li>恢复额度：递增上下文恢复计数（为 design D7 单次恢复服务）。</li>
 * </ul>
 *
 * <h2>WHY session 串行裁决靠数据库唯一索引而非内存锁</h2>
 * <p>design.md D5 要求「同一 session 同时只能有一条 running run」。
 * 内存锁（如 {@code ConcurrentHashMap}）在进程重启后丢失，而数据库唯一索引
 * {@code ux_ai_runs_session_active} 在 {@code ai_runs(session_id) WHERE status = 'running'}
 * 上提供持久保障——即使进程崩溃重启，残留的 running 行仍会阻止新 run 被领取，
 * 直到人工或清算逻辑处理。</p>
 *
 * <h2>WHY 模型配置快照在 run 开始时冻结</h2>
 * <p>design.md D7 要求「每个 run 开始冻结模型配置」。用户在 run 执行期间修改模型设置
 * 不应影响当前 run 的行为——冻结快照保证可复现性和审计一致性。</p>
 */
@Service
public class AgentRunService {

    private static final Logger LOG = LoggerFactory.getLogger(AgentRunService.class);

    private final AiRunMapper runMapper;

    public AgentRunService(AiRunMapper runMapper) {
        this.runMapper = Objects.requireNonNull(runMapper, "runMapper 不得为 null");
    }

    // ==================================================================
    // 领取
    // ==================================================================

    /**
     * 领取一次 run：落一行 {@code status=running} 的记录。
     *
     * <p>WHY 抛异常而非返回 null：唯一索引冲突说明同 session 已有 run 在跑，
     * 调用方需要明确知道这一事实以通知用户「本会话正在处理上一条提问」。</p>
     *
     * @param sessionId         所属会话
     * @param conversationId    关联对话，可空
     * @param modelConfigSnapshot 模型配置快照（JSON 文本），运行期间不可变
     * @return 新创建的 run 实体
     * @throws org.springframework.dao.DuplicateKeyException 同 session 已有 running run
     */
    @Transactional
    public AiRun claimRun(UUID sessionId, @Nullable UUID conversationId,
                          @Nullable String modelConfigSnapshot) {
        Objects.requireNonNull(sessionId, "sessionId 不得为 null");

        AiRun run = new AiRun();
        run.setId(EntityIds.newUuid());
        run.setSessionId(sessionId.toString());
        run.setConversationId(conversationId == null ? null : conversationId.toString());
        run.setStatus("running");
        run.setModelConfigSnapshot(modelConfigSnapshot);
        run.setContextRecoveryCount(0);
        run.setCancellationGeneration(0);
        run.setStartedAt(LocalDateTime.now());

        runMapper.insert(run);
        LOG.info("已领取 run: id={} sessionId={}", run.getId(), sessionId);
        return run;
    }

    // ==================================================================
    // 状态流转
    // ==================================================================

    /**
     * 标记 run 为已完成。
     */
    @Transactional
    public void completeRun(String runId) {
        transitionTo(runId, "completed");
    }

    /**
     * 标记 run 为失败。
     */
    @Transactional
    public void failRun(String runId) {
        transitionTo(runId, "failed");
    }

    /**
     * 标记 run 为已停止（用户主动取消）。
     */
    @Transactional
    public void stopRun(String runId) {
        transitionTo(runId, "stopped");
    }

    /**
     * 标记 run 为未知状态（远端状态不确定时）。
     */
    @Transactional
    public void markRunUnknown(String runId) {
        transitionTo(runId, "unknown");
    }

    private void transitionTo(String runId, String newStatus) {
        Objects.requireNonNull(runId, "runId 不得为 null");
        Objects.requireNonNull(newStatus, "newStatus 不得为 null");

        AiRun patch = new AiRun();
        patch.setId(runId);
        patch.setStatus(newStatus);
        patch.setEndedAt(LocalDateTime.now());
        runMapper.updateById(patch);
        LOG.info("run 状态流转: id={} → {}", runId, newStatus);
    }

    // ==================================================================
    // 取消代次（为 6.6 停止机制服务）
    // ==================================================================

    /**
     * 递增取消代次。
     *
     * <p>WHY 返回新值：调用方（停止逻辑）需要知道新的活跃代次，
     * 以便与正在执行的代码路径持有的代次比较，判断是否已被取消。</p>
     *
     * @return 递增后的新代次
     */
    @Transactional
    public int incrementCancellationGeneration(String runId) {
        Objects.requireNonNull(runId, "runId 不得为 null");

        AiRun current = runMapper.selectById(runId);
        if (current == null) {
            throw new IllegalArgumentException("run 不存在: " + runId);
        }
        int newGen = (current.getCancellationGeneration() == null ? 0 : current.getCancellationGeneration()) + 1;

        AiRun patch = new AiRun();
        patch.setId(runId);
        patch.setCancellationGeneration(newGen);
        runMapper.updateById(patch);

        LOG.info("取消代次递增: runId={} → {}", runId, newGen);
        return newGen;
    }

    /**
     * 检查给定代次是否已被取消。
     *
     * <p>WHY 参数是 {@code callerGeneration} 而非只读 runId：
     * 调用方在开始执行前记录当时的代次，执行过程中定期或关键点检查。
     * 如果活跃代次已大于 callerGeneration，说明有人在此期间触发了取消，
     * 调用方应当中止当前操作。</p>
     *
     * @param runId            run 标识
     * @param callerGeneration 调用方持有的代次
     * @return {@code true} 表示活跃代次已大于 callerGeneration，当前操作应中止
     */
    public boolean isCancelled(String runId, int callerGeneration) {
        Objects.requireNonNull(runId, "runId 不得为 null");

        AiRun current = runMapper.selectById(runId);
        if (current == null) {
            // run 已被删除（级联清理），视为已取消
            return true;
        }
        int activeGen = current.getCancellationGeneration() == null ? 0 : current.getCancellationGeneration();
        return callerGeneration < activeGen;
    }

    // ==================================================================
    // 恢复额度（为 design D7 单次恢复服务）
    // ==================================================================

    /**
     * 查询当前恢复计数。
     *
     * @param runId run 标识
     * @return 已使用的恢复次数；run 不存在时返回 -1
     */
    public int getRecoveryCount(String runId) {
        Objects.requireNonNull(runId, "runId 不得为 null");
        AiRun current = runMapper.selectById(runId);
        if (current == null) {
            return -1;
        }
        return current.getContextRecoveryCount() == null ? 0 : current.getContextRecoveryCount();
    }

    /**
     * 递增恢复计数（design D7 "单次恢复"）。
     *
     * <p>WHY 返回新值：调用方需要知道递增后的值，以判断是否已用完恢复额度
     * （新值 >= 1 表示已用过，不应再恢复）。</p>
     *
     * @param runId run 标识
     * @return 递增后的恢复计数
     */
    @Transactional
    public int incrementRecoveryCount(String runId) {
        Objects.requireNonNull(runId, "runId 不得为 null");

        AiRun current = runMapper.selectById(runId);
        if (current == null) {
            throw new IllegalArgumentException("run 不存在: " + runId);
        }
        int newCount = (current.getContextRecoveryCount() == null ? 0 : current.getContextRecoveryCount()) + 1;

        AiRun patch = new AiRun();
        patch.setId(runId);
        patch.setContextRecoveryCount(newCount);
        runMapper.updateById(patch);

        LOG.info("恢复计数递增: runId={} → {}", runId, newCount);
        return newCount;
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 按 id 查找 run。
     *
     * @return run 实体；不存在时为 null
     */
    @Nullable
    public AiRun findRun(String runId) {
        if (runId == null) {
            return null;
        }
        return runMapper.selectById(runId);
    }
}
