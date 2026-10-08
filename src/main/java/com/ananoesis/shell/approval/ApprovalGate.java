package com.ananoesis.shell.approval;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.ananoesis.shell.service.SettingsService;
import com.ananoesis.shell.support.EntityIds;
import com.ananoesis.shell.ws.ApprovalResponseFrame;

import jakarta.annotation.PreDestroy;

/**
 * 命令审批闸门（tasks 8.1 / 8.3 / 6.4 / 6.5）——副作用工具与远端 shell 之间<b>唯一</b>的通路。
 *
 * <h2>版本化（6.4）</h2>
 * <p>design.md D5 要求「不可变 revision：修改产生新版本、旧版本不可执行」。
 * 每个 {@link Entry} 维护一个 {@code version} 计数器，初始为 1。
 * 修改操作（{@link #modify}）在 {@code expectedVersion} 匹配时递增版本；
 * 批准/拒绝操作（{@link #respondWithVersion}）在 {@code expectedVersion} 匹配时才裁决。
 * 旧版本的裁决请求被静默拒绝——前端据此知道用户看到的是过期快照。</p>
 *
 * <h2>并发栅栏（6.5）</h2>
 * <p>批准/拒绝/修改/超时/停止/关闭在同一状态机中互斥。
 * {@link Entry#claim()} 的 {@link AtomicBoolean#compareAndSet} 是唯一的 CAS 点，
 * 保证并发裁决只有一个赢家。修改操作也通过 {@code synchronized} 块与裁决互斥——
 * 修改中的批准请求会因版本不匹配而被拒。</p>
 */
@Component
public class ApprovalGate {

    private static final Logger LOG = LoggerFactory.getLogger(ApprovalGate.class);

    private static final int AWAIT_GRACE_SECONDS = 5;

    private final ApprovalAuditService audit;
    private final SettingsService settings;
    private final ObjectProvider<ApprovalNotifier> notifiers;

    private final Map<UUID, Entry> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(new TimeoutThreadFactory());

    public ApprovalGate(ApprovalAuditService audit, SettingsService settings,
                        ObjectProvider<ApprovalNotifier> notifiers) {
        this.audit = Objects.requireNonNull(audit, "audit 不得为 null");
        this.settings = Objects.requireNonNull(settings, "settings 不得为 null");
        this.notifiers = Objects.requireNonNull(notifiers, "notifiers 不得为 null");
    }

    /**
     * 一张已受理的审批凭据。
     */
    public record Ticket(UUID approvalId, ApprovalProposal proposal, int timeoutSeconds,
                         OffsetDateTime requestedAt, OffsetDateTime expiresAt) {
    }

    // ==================================================================
    // 受理与等待
    // ==================================================================

    /**
     * 受理一个提案：分配 id、落 pending 审计行、登记挂起、排定超时、广播请求帧。
     */
    public Ticket submit(ApprovalProposal proposal) {
        Objects.requireNonNull(proposal, "proposal 不得为 null");

        int timeoutSeconds = settings.approvalTimeoutSeconds();
        UUID approvalId = UUID.fromString(EntityIds.newUuid());
        OffsetDateTime requestedAt = OffsetDateTime.now();
        OffsetDateTime expiresAt = requestedAt.plusSeconds(timeoutSeconds);

        audit.insertPending(approvalId, proposal, requestedAt, expiresAt);

        Entry entry = new Entry();
        pending.put(approvalId, entry);
        entry.timeoutTask = scheduler.schedule(() -> decide(approvalId, ApprovalOutcome.TIMED_OUT),
                timeoutSeconds, TimeUnit.SECONDS);

        Ticket ticket = new Ticket(approvalId, proposal, timeoutSeconds, requestedAt, expiresAt);
        try {
            ApprovalNotifier notifier = notifiers.getIfAvailable();
            if (notifier == null) {
                LOG.warn("无审批广播出口，提案将等待至超时: approvalId={}", approvalId);
            } else {
                notifier.notifyRequest(ticket);
            }
        } catch (RuntimeException e) {
            LOG.warn("广播审批请求失败，提案将等待至超时: approvalId={} cause={}",
                    approvalId, String.valueOf(e.getMessage()));
        }
        return ticket;
    }

    /**
     * 阻塞等待一个提案的决定。
     */
    public ApprovalOutcome await(Ticket ticket) throws InterruptedException {
        Objects.requireNonNull(ticket, "ticket 不得为 null");
        Entry entry = pending.get(ticket.approvalId());
        if (entry == null) {
            return recordedOutcomeOrTimedOut(ticket.approvalId(), true);
        }
        try {
            return entry.future.get(ticket.timeoutSeconds() + AWAIT_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOG.warn("等待审批决定超出兜底时限，按超时处理: approvalId={}", ticket.approvalId());
            decide(ticket.approvalId(), ApprovalOutcome.TIMED_OUT);
            return recordedOutcomeOrTimedOut(ticket.approvalId(), false);
        } catch (ExecutionException e) {
            LOG.error("等待审批决定时出现异常，按超时处理: approvalId={}", ticket.approvalId(), e);
            decide(ticket.approvalId(), ApprovalOutcome.TIMED_OUT);
            return recordedOutcomeOrTimedOut(ticket.approvalId(), false);
        }
    }

    private ApprovalOutcome recordedOutcomeOrTimedOut(UUID approvalId, boolean announceFallback) {
        ApprovalOutcome recorded;
        try {
            recorded = audit.decisionOf(approvalId);
        } catch (RuntimeException e) {
            LOG.error("回读审批审计行失败，按超时处理: approvalId={}", approvalId, e);
            return ApprovalOutcome.TIMED_OUT;
        }
        if (recorded != null) {
            if (announceFallback) {
                LOG.warn("审批在等待开始前就已被裁决，按审计行还原结局: approvalId={} outcome={}",
                        approvalId, recorded);
            }
            return recorded;
        }
        LOG.warn("等待审批时条目已不在内存且审计行无结局，按超时处理: approvalId={}", approvalId);
        return ApprovalOutcome.TIMED_OUT;
    }

    // ==================================================================
    // 决定（含版本校验 6.4）
    // ==================================================================

    /**
     * 处理来自 {@code /ws/approval} 的用户响应（不带版本校验，向后兼容）。
     */
    public boolean respond(UUID approvalId, ApprovalResponseFrame.Decision decision) {
        if (approvalId == null || decision == null) {
            return false;
        }
        return decide(approvalId,
                decision == ApprovalResponseFrame.Decision.APPROVE
                        ? ApprovalOutcome.APPROVED
                        : ApprovalOutcome.CANCELLED);
    }

    /**
     * 带版本校验的裁决（design D5 / 6.4）。
     *
     * <p>WHY expectedVersion：前端弹框显示的是某个版本的命令快照。
     * 如果用户在弹框上点了批准，但期间命令已被修改（版本递增），
     * 那么用户批准的其实是旧版本——这不应该被执行。
     * 只有 expectedVersion 匹配当前版本时，裁决才生效。</p>
     *
     * @param approvalId      审批 id
     * @param decision        用户决定
     * @param expectedVersion 前端持有的版本号
     * @return 是否裁决成功。{@code false} 表示版本不匹配或已裁决
     */
    public boolean respondWithVersion(UUID approvalId, ApprovalResponseFrame.Decision decision,
                                      int expectedVersion) {
        if (approvalId == null || decision == null) {
            return false;
        }
        Entry entry = pending.get(approvalId);
        if (entry == null) {
            return false;
        }
        // WHY 版本校验在 claim 之前：版本不匹配的请求不应消耗裁决权
        synchronized (entry) {
            if (entry.version != expectedVersion) {
                LOG.info("审批版本不匹配，拒绝裁决: approvalId={} expected={} actual={}",
                        approvalId, expectedVersion, entry.version);
                return false;
            }
        }
        ApprovalOutcome outcome = decision == ApprovalResponseFrame.Decision.APPROVE
                ? ApprovalOutcome.APPROVED
                : ApprovalOutcome.CANCELLED;
        return decide(approvalId, outcome);
    }

    /**
     * 修改命令（产生新版本，design D5 / 6.4）。
     *
     * <p>WHY 修改不重置超时：保留原到期时间。否则用户可以通过反复修改无限续期审批窗口。</p>
     *
     * @param approvalId      审批 id
     * @param modifiedCommand 修改后的命令
     * @param expectedVersion 前端持有的版本号
     * @return 是否修改成功。{@code false} 表示版本不匹配或已裁决
     */
    public boolean modify(UUID approvalId, String modifiedCommand, int expectedVersion) {
        if (approvalId == null || modifiedCommand == null) {
            return false;
        }
        Entry entry = pending.get(approvalId);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (entry.claimed.get()) {
                // 已裁决的不可修改
                return false;
            }
            if (entry.version != expectedVersion) {
                LOG.info("审批版本不匹配，拒绝修改: approvalId={} expected={} actual={}",
                        approvalId, expectedVersion, entry.version);
                return false;
            }
            // 递增版本
            entry.version++;
            entry.modifiedCommand = modifiedCommand;
            int newVersion = entry.version;
            // 落库版本变更
            try {
                audit.recordModification(approvalId, modifiedCommand, newVersion);
            } catch (RuntimeException e) {
                LOG.error("写入审批修改的审计行失败: approvalId={}", approvalId, e);
                // 回滚内存状态
                entry.version--;
                entry.modifiedCommand = null;
                return false;
            }
            LOG.info("审批已修改: approvalId={} newVersion={}", approvalId, newVersion);
            return true;
        }
    }

    /**
     * 当前裁决对象的生效命令（修改的消费出口，供执行链路回读）。
     *
     * <p>WHY：{@link #modify} 只更新内存条目与审计行的 final_command 列，
     * 而执行方（{@code AiAgentService#runGated}）持有的是提案原命令串。
     * 不回读本方法，批准后执行的就会一直是旧命令——修改功能形同虚设。
     * 裁决后条目已从挂起表移除，此时回读审计行：final_command 在裁决点
     * 之前写入、之后不可变（claimed 拦截了后续修改），因此本读无竞态。</p>
     *
     * @param approvalId 审批 id
     * @param fallback   无修改记录时的回退值（提案原命令）
     * @return 被修改过则为修改后命令，否则为 fallback
     */
    public String effectiveCommand(UUID approvalId, String fallback) {
        Entry entry = pending.get(approvalId);
        if (entry != null) {
            String modified = entry.modifiedCommand;
            return modified != null ? modified : fallback;
        }
        String finalCommand = audit.finalCommandOf(approvalId);
        return finalCommand != null ? finalCommand : fallback;
    }

    /**
     * 裁决一个提案。
     */
    private boolean decide(UUID approvalId, ApprovalOutcome outcome) {
        Entry entry = pending.get(approvalId);
        if (entry == null) {
            return false;
        }
        if (!entry.claim()) {
            return false;
        }
        entry.cancelTimeout();
        try {
            audit.recordDecision(approvalId, outcome, OffsetDateTime.now());
        } catch (RuntimeException e) {
            LOG.error("写入审批决定的审计行失败: approvalId={} outcome={}", approvalId, outcome, e);
        }
        pending.remove(approvalId, entry);
        entry.future.complete(outcome);
        LOG.info("审批已裁决: approvalId={} outcome={} 剩余挂起={}", approvalId, outcome, pending.size());
        return true;
    }

    // ==================================================================
    // 生命周期与可观测性
    // ==================================================================

    @PreDestroy
    void shutdown() {
        for (UUID approvalId : java.util.List.copyOf(pending.keySet())) {
            decide(approvalId, ApprovalOutcome.TIMED_OUT);
        }
        scheduler.shutdownNow();
        LOG.info("审批闸门已关闭");
    }

    int pendingCount() {
        return pending.size();
    }

    /**
     * 一个挂起条目：等待中的 future、它的超时任务、版本号、以及一次性的裁决权。
     */
    private static final class Entry {

        private final CompletableFuture<ApprovalOutcome> future = new CompletableFuture<>();
        private final AtomicBoolean claimed = new AtomicBoolean();
        private volatile ScheduledFuture<?> timeoutTask;

        /** 当前版本号（design D5 / 6.4），初始为 1，每次修改递增。 */
        private int version = 1;

        /** 修改后的命令（null 表示未修改）。 */
        private volatile String modifiedCommand;

        boolean claim() {
            return claimed.compareAndSet(false, true);
        }

        void cancelTimeout() {
            ScheduledFuture<?> task = timeoutTask;
            if (task != null) {
                task.cancel(false);
            }
        }
    }

    private static final class TimeoutThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "approval-timeout-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
