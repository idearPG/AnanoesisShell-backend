package com.ananoesis.shell.ssh;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个连接实例的运行时容器（task 4.1 / 4.3 / 4.4 + 5.3/5.5 输入调度）。
 *
 * <p>每次连接生成独立的 {@code session_id}（UUID），不按 {@code host_id} 去重。
 * 同主机两次连接得到两个完全独立的 {@code SessionRuntime}，关闭一条不影响另一条。</p>
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>持有 SSH 传输连接（通过 {@link SshTerminalSession}）、持久 PTY</li>
 *   <li>输入调度器（{@link PtyCommandScheduler}）——Shell 集成完成后安装</li>
 *   <li>单调递增 {@code event_seq} 与 1 MiB 尾部事件缓冲（task 4.3）</li>
 *   <li>断线 30 秒宽限期与超时清算（task 4.4）</li>
 * </ul>
 *
 * <p>WHY 事件缓冲默认 1 MiB：design.md D2 规定"有界事件尾部"，1 MiB 足以覆盖
 * 短暂断线期间的终端输出，同时防止单连接无限膨胀。</p>
 */
public class SessionRuntime {

    private static final Logger LOG = LoggerFactory.getLogger(SessionRuntime.class);

    /** 默认尾部事件缓冲上限（字节）。design.md D2：1 MiB。 */
    static final int DEFAULT_TAIL_BUFFER_BYTES = 1024 * 1024;

    /** 宽限期调度器——所有 runtime 共享，守护线程。 */
    private static final ScheduledExecutorService GRACE_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "session-grace-scheduler");
                t.setDaemon(true);
                return t;
            });

    private final UUID sessionId;
    private final UUID hostId;
    private final SshTerminalSession terminalSession;
    private final TerminalOutputListener listener;
    private final Consumer<String> cleanupCallback;
    private final int tailBufferLimitBytes;

    // ---- 事件序号与尾部缓冲（task 4.3） ----
    private final AtomicLong eventSeqCounter = new AtomicLong(0);
    /** 尾部事件缓冲：按插入顺序维护，超出上限时从头部逐出。 */
    private final List<EventRecord> eventBuffer = new ArrayList<>();
    private long eventBufferBytes = 0;

    // ---- 断线宽限（task 4.4） ----
    private volatile ScheduledFuture<?> pendingDisconnectTask;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // ---- PTY 输入调度器（task 5.3/5.5） ----
    /**
     * Shell 集成完成后安装的输入调度器。
     * WHY volatile + setter：调度器在 Shell 集成成功后才可用（需要 nonce），
     * 而集成是异步的，因此不能在构造器里初始化。
     */
    private volatile PtyCommandScheduler scheduler;

    // ---- 嵌套 Shell 重装次数计数（防无限循环） ----
    /**
     * 嵌套 Shell 重装尝试次数。
     * WHY AtomicInteger：虽然回调在调度器的同步块内调用，但多个调度器实例（旧 vs 新）
     * 的定时器可能在并发线程上触发，用原子计数器保证线程安全。
     */
    private final AtomicLong nestedReinstallCount = new AtomicLong(0);

    /**
     * 递增并返回嵌套重装次数。
     * @return 递增后的次数
     */
    public long incrementAndGetNestedReinstallCount() {
        return nestedReinstallCount.incrementAndGet();
    }

    /**
     * 构造运行时。
     *
     * <p>WHY sessionId 取自 terminalSession.id() 而不是自行生成：
     * Bridge（WS 输出监听器）使用 terminalSession 的 id 标识帧归属，
     * SessionRuntime 必须与之保持一致，否则前端收到的帧 session_id 与
     * open 回执的 session_id 不同，导致前端无法关联。</p>
     *
     * @param hostId          所属主机配置
     * @param terminalSession 已建立的 PTY 会话
     * @param listener        输出监听器（WS 桥接 + 事件记录）
     * @param cleanupCallback 清算回调（sessionId → void）；测试可传 null
     */
    public SessionRuntime(UUID hostId,
                          SshTerminalSession terminalSession,
                          TerminalOutputListener listener,
                          Consumer<String> cleanupCallback) {
        this(hostId, terminalSession, listener, cleanupCallback, DEFAULT_TAIL_BUFFER_BYTES);
    }

    /** 可配置缓冲上限的构造器（供测试使用）。 */
    SessionRuntime(UUID hostId,
                   SshTerminalSession terminalSession,
                   TerminalOutputListener listener,
                   Consumer<String> cleanupCallback,
                   int tailBufferLimitBytes) {
        // WHY sessionId 从 terminalSession 的 id 取：保持 WS 帧与 runtime 注册表的 id 一致
        this.sessionId = UUID.fromString(terminalSession.id());
        this.hostId = Objects.requireNonNull(hostId, "hostId 不得为 null");
        this.terminalSession = Objects.requireNonNull(terminalSession, "terminalSession 不得为 null");
        this.listener = Objects.requireNonNull(listener, "listener 不得为 null");
        this.cleanupCallback = cleanupCallback; // 允许 null（测试桩场景）
        this.tailBufferLimitBytes = tailBufferLimitBytes;
    }

    // ==================================================================
    // 基本属性
    // ==================================================================

    public UUID sessionId() {
        return sessionId;
    }

    public UUID hostId() {
        return hostId;
    }

    public SshTerminalSession terminalSession() {
        return terminalSession;
    }

    public boolean isClosed() {
        return closed.get();
    }

    // ==================================================================
    // PTY 输入调度器（task 5.3/5.5）
    // ==================================================================

    /**
     * 获取当前安装的输入调度器。
     *
     * @return 调度器；Shell 集成未完成或不被支持时返回 {@code null}
     */
    public PtyCommandScheduler scheduler() {
        return scheduler;
    }

    /**
     * 安装输入调度器（Shell 集成成功后调用）。
     *
     * <p>WHY 用 setter 而非构造器参数：调度器的创建依赖 Shell 集成的结果
     * （nonce），而集成发生在 runtime 构造之后。</p>
     */
    public void setScheduler(PtyCommandScheduler scheduler) {
        this.scheduler = scheduler;
    }

    // ==================================================================
    // 事件序号与尾部缓冲（task 4.3）
    // ==================================================================

    /**
     * 记录一条运行时事件并分配单调递增序号。
     *
     * <p>WHY 同步方法：事件缓冲是有界列表，需要在同一把锁内完成"追加 + 逐出"，
     * 保证序号与缓冲内容的一致性。读写终端输出的频率远低于锁竞争开销。</p>
     *
     * @return 分配的事件序号（从 1 开始）
     */
    public synchronized long recordEvent(String type, String data) {
        long seq = eventSeqCounter.incrementAndGet();
        EventRecord record = new EventRecord(seq, type, data);
        eventBuffer.add(record);
        eventBufferBytes += record.byteSize;

        // 超出上限时从头部逐出旧事件
        while (eventBufferBytes > tailBufferLimitBytes && !eventBuffer.isEmpty()) {
            EventRecord evicted = eventBuffer.remove(0);
            eventBufferBytes -= evicted.byteSize;
        }

        return seq;
    }

    /**
     * 订阅事件快照（task 4.3）。
     *
     * <p>返回自 {@code lastSeenSeq} 之后的所有缓冲事件。若客户端请求的起点
     * 已被逐出（{@code lastSeenSeq} 小于缓冲中最早序号），则标记 {@code gap_detected=true}，
     * 告知前端"部分事件可能丢失"。</p>
     *
     * @param lastSeenSeq 客户端已收到的最新序号；0 表示从头开始
     * @return 快照：包含 {@code events}、可选 {@code gap_detected}、{@code latest_seq}
     */
    public synchronized Map<String, Object> subscribe(long lastSeenSeq) {
        Map<String, Object> snapshot = new LinkedHashMap<>();

        long latestSeq = eventSeqCounter.get();
        snapshot.put("latest_seq", latestSeq);

        // 检测缺口：客户端期望的下一个序号（lastSeenSeq + 1）比缓冲中最早的序号还小
        if (!eventBuffer.isEmpty()) {
            long oldestBufferedSeq = eventBuffer.get(0).seq;
            if (lastSeenSeq + 1 < oldestBufferedSeq) {
                // 客户端需要的某些事件已被逐出
                snapshot.put("gap_detected", true);
            }
        }

        // 过滤出 lastSeenSeq 之后的事件
        List<Map<String, Object>> events = new ArrayList<>();
        for (EventRecord record : eventBuffer) {
            if (record.seq > lastSeenSeq) {
                events.add(record.toMap());
            }
        }
        snapshot.put("events", events);

        return snapshot;
    }

    /** 当前最新事件序号（无事件时为 0）。 */
    public synchronized long currentEventSeq() {
        return eventSeqCounter.get();
    }

    // ==================================================================
    // 断线宽限与清算（task 4.4）
    // ==================================================================

    /**
     * 控制端断线——进入宽限期。
     *
     * <p>WHY 不立即关闭：前端刷新页面或网络短暂抖动时，用户期望回来后还能看到
     * 自己的终端。30 秒宽限（design.md D1）让重订阅成为可能。</p>
     */
    public void disconnectForGracePeriod(Duration gracePeriod) {
        Objects.requireNonNull(gracePeriod, "gracePeriod 不得为 null");
        if (closed.get()) {
            return;
        }
        LOG.info("控制端断线，进入宽限期: session={} grace={}", sessionId, gracePeriod);
        cancelGracePeriod();
        pendingDisconnectTask = GRACE_SCHEDULER.schedule(
                this::onGracePeriodExpired,
                gracePeriod.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * 控制端重连——取消宽限期清算。
     *
     * <p>WHY 用 CAS 而非直接取消：若宽限期恰好在此期间到期，CAS 保证不会
     * 误取消已经执行的清算任务。</p>
     */
    public void rebind() {
        if (cancelGracePeriod()) {
            LOG.info("控制端重连，宽限期已取消: session={}", sessionId);
        }
    }

    /**
     * 显式关闭——立即清算，不走宽限期。
     *
     * <p>WHY 幂等：多个并发来源（用户关闭、远端 EOF、空闲回收）可能同时触发，
     * CAS 保证恰好一次清算。</p>
     */
    public void close(SshCloseReason reason) {
        Objects.requireNonNull(reason, "reason 不得为 null");
        if (!closed.compareAndSet(false, true)) {
            return; // 已关闭，幂等返回
        }
        cancelGracePeriod();
        doClose(reason);
    }

    /**
     * 终端会话已关闭的回调（由 {@link SshTerminalSession} 的 closeHook 调用）。
     *
     * <p>WHY 需要它：远端 EOF 或读泵异常时，终端自行关闭。此方法确保 runtime
     * 的 closed 状态与终端一致，并触发清算回调。</p>
     */
    void onTerminalClosed(SshCloseReason reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelGracePeriod();
        fireCleanupCallback();
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private void onGracePeriodExpired() {
        if (closed.compareAndSet(false, true)) {
            LOG.info("宽限期超时，开始清算: session={}", sessionId);
            doClose(SshCloseReason.TIMEOUT);
        }
    }

    /**
     * 执行清算：关闭终端会话并触发清算回调。
     *
     * <p>WHY 清算回调在此处而非 close() 内调用：无论关闭来源是显式 close()、
     * 宽限期超时还是终端自行关闭，回调都必须恰好触发一次。统一在 doClose() 内
     * 完成，避免三路关闭逻辑重复。</p>
     */
    private void doClose(SshCloseReason reason) {
        try {
            terminalSession.close(reason);
        } catch (RuntimeException e) {
            LOG.warn("关闭终端会话时出错: session={}", sessionId, e);
        }
        fireCleanupCallback();
    }

    private void fireCleanupCallback() {
        if (cleanupCallback != null) {
            try {
                cleanupCallback.accept(sessionId.toString());
            } catch (RuntimeException e) {
                LOG.warn("清算回调执行失败: session={}", sessionId, e);
            }
        }
    }

    private boolean cancelGracePeriod() {
        ScheduledFuture<?> task = pendingDisconnectTask;
        pendingDisconnectTask = null;
        return task != null && task.cancel(false);
    }

    // ==================================================================
    // 事件记录
    // ==================================================================

    /** 缓冲中的单条事件。 */
    private static final class EventRecord {
        final long seq;
        final String type;
        final String data;
        final int byteSize;

        EventRecord(long seq, String type, String data) {
            this.seq = seq;
            this.type = type;
            this.data = data;
            // WHY 按 UTF-8 字节计算大小：终端输出可能含中文/宽字符，
            // 按字符计数会低估实际内存占用。UTF-8 字节数更接近真实开销。
            this.byteSize = 24 + (data != null
                    ? data.getBytes(StandardCharsets.UTF_8).length : 0);
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("seq", seq);
            map.put("type", type);
            map.put("data", data);
            return map;
        }
    }
}
