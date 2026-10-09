package com.ananoesis.shell.ssh;

import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PTY 输入调度器（tasks 5.3 + 5.4）。
 *
 * <p>管理 Agent 与人工在共享 PTY 上的输入权。核心约束：</p>
 * <ul>
 *   <li>状态机：{@code manual_idle → agent_owned → manual_idle}（循环），
 *       以及 {@code manual_busy}（人工输入中）、{@code stopping}（正在停止）、
 *       {@code unknown}（无法判定）</li>
 *   <li>人工半行/粘贴/全屏/嵌套 Shell 时不注入 Agent 字节</li>
 *   <li>只有确认空提示符（{@code manual_idle}）才领取输入权</li>
 *   <li>串行执行：读到可靠完成帧（{@code CMD_END} + {@code PROMPT}）才允许下一个</li>
 *   <li>空闲超时（默认 120 秒无输出/无帧活动，ssh-connection spec 心跳检测）+ 30 分钟绝对上限兜底 +
 *       64 KiB 采集上限（BUG-A 改造：分钟级安装命令持续输出时不得被误杀）</li>
 *   <li>超限继续排空、仅丢弃超额采集内容</li>
 *   <li>中断后 3 秒无完成证据 → {@code unknown}</li>
 *   <li>manual_busy 自愈：用户停手超过 busy 时限且无前台命令在跑（无 CMD_START
 *       证据）时清行回 idle（修复：停止回合后键盘活动把状态标 busy，而 busy 的
 *       旧唯一恢复证据是下一条命令的 PROMPT 帧——Agent 提交又被 busy 拒绝不发命令，
 *       形成"拒绝→无帧→永久 busy"死锁，获准命令全被拒、模型重复思考不执行）</li>
 * </ul>
 *
 * <p>WHY 串行而非并行：PTY 是共享的 stdin/stdout，并发写入会让命令输出交错，
 * 无法区分哪段输出属于哪条命令。串行保证每条命令的采集完整且可归属。</p>
 */
public class PtyCommandScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(PtyCommandScheduler.class);

    /** ssh-connection spec：空闲超时（无 stdout/stderr 输出）SHALL 为 120 秒，作为心跳检测。
     *  命令持续有输出即视为存活，连续 120 秒无任何输出/帧才中断
     *  （BUG-A：旧语义为固定 60 秒绝对超时，安装 JDK 等分钟级命令被误杀；
     *  v0.2.0 由 60 秒提升为 120 秒，与 spec 心跳检测对齐）。 */
    static final long DEFAULT_TIMEOUT_MS = 120_000;

    /** design.md D3：中断后 3 秒无完成证据 → unknown。 */
    static final long DEFAULT_INTERRUPT_WATCH_MS = 3_000;

    /**
     * 单条命令绝对执行上限（兜底）：无论是否持续输出，超过此时长必被中断。
     *
     * <p>WHY 需要绝对上限：空闲超时依赖输出活动判存活，真正“跑飞”的命令
     * （死循环刷输出、无限下载）会无限占用输入权，必须有硬兜底。</p>
     */
    static final long DEFAULT_MAX_ABSOLUTE_MS = 30 * 60_000L;

    /** 等待方（runner/tools）future.get 相对绝对上限的余量（秒），避免提前弃等回落 exec。 */
    private static final long PTY_WAIT_GRACE_SECONDS = 10;

    /**
     * MANUAL_BUSY 自愈时限：最后一次人工按键后静默超过此时长且无前台命令在跑，
     * 视为人工活动结束，清行回 idle。
     *
     * <p>WHY 需要自愈（停止后卡死 bug）：busy 的旧唯一恢复证据是 PROMPT 帧，
     * 而 PROMPT 只在有命令真正执行完时才出现；用户在空提示符敲了半行后停手转去
     * 审批 Agent 命令，就落入"busy 拒绝提交→不发命令→无 PROMPT→永久 busy"的
     * 结构性死锁。现行成熟 ssh 客户端从不用"是否敲过键"无限期封锁自动执行，
     * 而是在注入前清掉残留半行。10 秒取值：覆盖正常打字停顿（连续按键会重置计时），
     * 又不至于让获准命令等太久。</p>
     */
    static final long DEFAULT_BUSY_EXPIRE_MS = 10_000;

    /** design.md D3：64 KiB 单命令采集上限。 */
    static final int MAX_COLLECTION_BYTES = 65536;

    /** 嵌套 Shell 探测默认超时（毫秒）：MANUAL_IDLE 超过此时间无帧活动则发送探测命令。 */
    static final long DEFAULT_NESTED_DETECT_TIMEOUT_MS = 8_000;

    /** 嵌套 Shell 探测标记——与 $$ (PID) 拼接后写入 PTY，回显中检测此标记确认嵌套 Shell 存在。 */
    static final String NESTED_PROBE_MARKER = "_ANANOESIS_NESTED_PROBE_";

    /**
     * PTY 路径等待方的 get 上限（秒）。
     *
     * <p>WHY 必须晚于调度器自己的绝对上限：调度器会在上限处主动中断并完成
     * future；若等待方先超时，会误判“PTY 不可用”回落 exec 把同一命令
     * 重跑一遍（BUG-B 实测现象）。用户显式配置更大的运行时限时以配置为准。</p>
     */
    public static long ptyWaitCeilingSeconds(long settingsTimeoutSeconds) {
        return Math.max(settingsTimeoutSeconds, DEFAULT_MAX_ABSOLUTE_MS / 1000 + PTY_WAIT_GRACE_SECONDS);
    }

    /** Ctrl-C 字符（ASCII ETX）。 */
    private static final String CTRL_C = "\u0003";

    /** 命令 id 生成器——单调递增，保证同一会话内不重复。 */
    private final AtomicLong commandIdCounter = new AtomicLong(0);

    // ==================================================================
    // 状态机
    // ==================================================================

    /**
     * 输入调度状态。
     *
     * <p>WHY 用枚举而非布尔标志：五个状态之间的转换规则复杂，
     * 用 switch 表达式比一堆 if-else 更安全、可读性更好。</p>
     */
    public enum State {
        /** 人工空闲——Agent 可领取输入权。 */
        MANUAL_IDLE,
        /** 人工忙碌——用户正在输入/粘贴/全屏程序，Agent 不得注入。 */
        MANUAL_BUSY,
        /** Agent 拥有输入权——正在执行命令，拒绝人工输入。 */
        AGENT_OWNED,
        /** 正在停止——不再接受新命令。 */
        STOPPING,
        /** 无法判定——钩子丢失/中断后无完成证据/嵌套 Shell 等。 */
        UNKNOWN
    }

    private volatile State state = State.MANUAL_IDLE;

    // ==================================================================
    // 依赖
    // ==================================================================

    private final SshTerminalSession terminalSession;
    private final String expectedNonce;
    private final ScheduledExecutorService scheduler;
    private final long timeoutMs;
    private final long interruptWatchMs;
    /** 单条命令绝对执行上限（防跑飞兜底，与是否活跃无关）。 */
    private final long maxAbsoluteMs;

    /** MANUAL_BUSY 自愈时限（见 {@link #DEFAULT_BUSY_EXPIRE_MS}）。 */
    private final long busyExpireMs;

    /**
     * 嵌套 Shell 帧超时检测阈值（毫秒）：MANUAL_IDLE 超过此时间无任何帧活动则触发探测。
     * 0 表示禁用嵌套检测（保持旧行为）。
     */
    private final long nestedDetectTimeoutMs;

    // ==================================================================
    // 运行时状态
    // ==================================================================

    /** 当前正在执行的命令（agent_owned 时非 null）。 */
    private PendingCommand currentCommand;

    /** 等待执行的命令队列。 */
    private final Queue<PendingCommand> commandQueue = new LinkedList<>();

    /** 超时定时任务（可取消）。 */
    private ScheduledFuture<?> pendingTimeout;

    /** 中断后观察窗口定时任务（可取消）。 */
    private ScheduledFuture<?> pendingWatch;

    /** busy 自愈定时任务（每次人工按键重新调度，可取消）。 */
    private ScheduledFuture<?> pendingBusyExpire;

    /** 嵌套探测定时任务（MANUAL_IDLE 帧超时后触发）。 */
    private ScheduledFuture<?> pendingNestedProbe;

    /**
     * 最近一次帧活动时刻（纳秒）：任何帧（PROMPT/CWD/CMD_START/CMD_END）或
     * collectOutput 输出均刷新。WHY 独立于 currentCommand.lastActivityNanos：
     * 嵌套检测需在无在飞命令时也能判定"Shell 应该持续产出帧但实际没有"。
     */
    private volatile long lastFrameNanos = System.nanoTime();

    /** 嵌套探测状态机：IDLE→PROBE_SENT→（回显+窗口内无帧：回调+FALLBACK）／（无回显或任意帧到达：回 IDLE）。 */
    private enum NestedState { IDLE, PROBE_SENT, FALLBACK }
    
        /** 探针回显是否已到达（shell 存活证据；与 PROMPT 帧缺失组合才构成嵌套确认）。 */
        private boolean probeEchoed;
    private NestedState nestedState = NestedState.IDLE;

    /** 集成重安装尝试次数（最多 1 次）。 */
    private int nestedReinstallAttempts = 0;

    /** 嵌套 Shell 回调：探测确认后通知调用方触发集成重安装。 */
    private volatile Runnable nestedShellCallback;

    /** 人工命令完成回调：人工命令（CMD_END + PROMPT 序列）完成后通知上层持久化。 */
    private volatile ManualCommandListener manualCommandListener;

    /** 是否已收到 CMD_END（用于判断 PROMPT 是否标志着命令完成）。 */
    private boolean cmdEndReceived;

    // ==================================================================
    // 人工命令追踪（Shell → Agent 记忆同步）
    // ==================================================================

    /**
     * 人工命令输出累积缓冲：MANUAL_BUSY 期间 CMD_START 后的 PTY 输出。
     * 包含命令回显和实际输出，供回调提取命令文本与输出摘要。
     */
    private final StringBuilder manualOutputBuffer = new StringBuilder();
    private int manualCollectedBytes = 0;
    private boolean manualOutputTruncated = false;

    /** 人工命令退出码（CMD_END 帧中解析）。 */
    private int manualExitCode = ExecOutcome.EXIT_CODE_UNKNOWN;

    /** 人工命令是否有 CMD_START 证据（区分"用户敲了 Enter"和"命令正在跑"）。 */
    private boolean manualCmdStartReceived;

    /**
     * 会话级当前工作目录（最新一条 CWD 帧的路径，无论来自人工 cd 还是 Agent 命令）。
     *
     * <p>WHY 无条件更新而非只记在飞命令结果：浏览器验收发现用户 cd 后问 Agent
     * “当前目录”，旧逻辑丢弃 cmdId=0 的人工帧导致提示词只能注入空值，模型猜成 /。
     * volatile：帧处理在 WS 线程，读取在 Agent 工作线程。</p>
     */
    private volatile String sessionCwd;

    /**
     * 构造调度器（使用默认超时配置）。
     *
     * @param terminalSession 绑定的 PTY 会话
     * @param expectedNonce   Shell 集成的 nonce（用于帧过滤）
     * @param scheduler       超时调度用的线程池
     */
    public PtyCommandScheduler(SshTerminalSession terminalSession,
                               String expectedNonce,
                               ScheduledExecutorService scheduler) {
        this(terminalSession, expectedNonce, scheduler, DEFAULT_TIMEOUT_MS, DEFAULT_INTERRUPT_WATCH_MS);
    }

    /**
     * 构造调度器（可配置超时——供测试使用）。
     *
     * @param timeoutMs       命令执行超时（毫秒）
     * @param interruptWatchMs 中断后等待完成证据的窗口（毫秒）
     */
    public PtyCommandScheduler(SshTerminalSession terminalSession,
                               String expectedNonce,
                               ScheduledExecutorService scheduler,
                               long timeoutMs,
                               long interruptWatchMs) {
        this(terminalSession, expectedNonce, scheduler, timeoutMs, interruptWatchMs, DEFAULT_MAX_ABSOLUTE_MS);
    }

    /**
     * 构造调度器（含绝对上限的完整配置——供测试与未来配置化使用）。
     *
     * @param maxAbsoluteMs 单条命令绝对执行上限（毫秒）
     */
    public PtyCommandScheduler(SshTerminalSession terminalSession,
                               String expectedNonce,
                               ScheduledExecutorService scheduler,
                               long timeoutMs,
                               long interruptWatchMs,
                               long maxAbsoluteMs) {
        this(terminalSession, expectedNonce, scheduler, timeoutMs, interruptWatchMs,
                maxAbsoluteMs, DEFAULT_BUSY_EXPIRE_MS);
    }

    /**
     * 构造调度器（含 busy 自愈时限的全参构造——供测试使用）。
     *
     * @param busyExpireMs MANUAL_BUSY 静默自愈时限（毫秒）
     */
    public PtyCommandScheduler(SshTerminalSession terminalSession,
                               String expectedNonce,
                               ScheduledExecutorService scheduler,
                               long timeoutMs,
                               long interruptWatchMs,
                               long maxAbsoluteMs,
                               long busyExpireMs) {
        this(terminalSession, expectedNonce, scheduler, timeoutMs, interruptWatchMs,
                maxAbsoluteMs, busyExpireMs, DEFAULT_NESTED_DETECT_TIMEOUT_MS);
    }

    /**
     * 全参构造（含嵌套 Shell 检测超时）。
     *
     * @param nestedDetectTimeoutMs 嵌套 Shell 帧超时检测阈值（毫秒），0 表示禁用
     */
    public PtyCommandScheduler(SshTerminalSession terminalSession,
                               String expectedNonce,
                               ScheduledExecutorService scheduler,
                               long timeoutMs,
                               long interruptWatchMs,
                               long maxAbsoluteMs,
                               long busyExpireMs,
                               long nestedDetectTimeoutMs) {
        this.terminalSession = Objects.requireNonNull(terminalSession, "terminalSession 不得为 null");
        this.expectedNonce = Objects.requireNonNull(expectedNonce, "expectedNonce 不得为 null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler 不得为 null");
        this.timeoutMs = timeoutMs;
        this.interruptWatchMs = interruptWatchMs;
        this.maxAbsoluteMs = maxAbsoluteMs;
        this.busyExpireMs = busyExpireMs;
        this.nestedDetectTimeoutMs = nestedDetectTimeoutMs;
    }

    // ==================================================================
    // 公开 API
    // ==================================================================

    /** 当前状态。 */
    public State state() {
        return state;
    }

    /** 排队等待的命令数。 */
    public synchronized int queuedCount() {
        return commandQueue.size();
    }

    /** 当前工作目录（最近一次 CWD 帧的值）。 */
    public synchronized String currentWorkingDirectory() {
        return currentCommand != null ? currentCommand.lastCwd : null;
    }

    /**
     * 设置嵌套 Shell 检测回调（探测确认后调用）。
     *
     * <p>WHY 用 Runnable 而非具体类型：调度器不依赖上层服务类，
     * 回调由 SshTerminalService 在构造后注入，触发集成重安装。</p>
     */
    public void setNestedShellCallback(Runnable callback) {
        this.nestedShellCallback = callback;
    }

    /** 嵌套降级标志：true 表示集成重安装失败，submitCommand 将拒绝并走 exec 回落。 */
    public boolean isNestedFallback() {
        return nestedState == NestedState.FALLBACK;
    }

    /**
     * 当前是否处于嵌套 Shell 环境（探测已确认或已降级）。
     *
     * <p>WHY 暴露给上层：Agent 系统提示需要告知模型用户可能进入了 Docker 容器等
     * 嵌套环境，否则模型不知道 Shell 环境已变化，给出基于宿主机的错误建议。</p>
     *
     * @return true 表示探测已确认嵌套 Shell（确认即降级）或已进入 exec 通道模式
     */
    public boolean isNestedShell() {
        return nestedState == NestedState.FALLBACK;
    }

    /**
     * 设置人工命令完成回调。
     *
     * <p>WHY 用回调而非直接依赖：调度器不依赖 ConversationService 等上层服务类，
     * 回调由 SshTerminalService 在构造后注入，负责将人工命令持久化到对话历史。</p>
     *
     * @param listener 回调；可为 null（清除回调）
     */
    public void setManualCommandListener(ManualCommandListener listener) {
        this.manualCommandListener = listener;
    }

    // ==================================================================
    // 集成事件
    // ==================================================================

    /**
     * Shell 集成成功安装——进入 {@code manual_idle}。
     *
     * <p>WHY 需要显式调用而不是在构造器里自动设置：构造器在 SessionRuntime 初始化时调用，
     * 而集成安装是异步的（需要等 PTY 输出确认）。只有安装成功后才能开始调度。</p>
     */
    public void onIntegrationSuccess() {
        synchronized (this) {
            this.state = State.MANUAL_IDLE;
            this.lastFrameNanos = System.nanoTime();
            this.nestedState = NestedState.IDLE;
            this.nestedReinstallAttempts = 0;
        }
        LOG.debug("Shell 集成成功，调度器进入 manual_idle");
        // WHY 在锁外启动探测：避免死锁（scheduleNestedProbe 内部 synchronized）
        scheduleNestedProbe();
    }

    /**
     * Shell 集成被跳过（不支持的 Shell）——进入 {@code unknown}。
     *
     * <p>WHY 是 unknown 而非 manual_idle：不支持的 Shell 没有帧协议，
     * 调度器无法识别命令边界，因此不能自动执行任何命令。</p>
     */
    public void onIntegrationSkipped() {
        synchronized (this) {
            this.state = State.UNKNOWN;
        }
        LOG.debug("Shell 集成被跳过，调度器进入 unknown");
    }

    // ==================================================================
    // 人工输入事件
    // ==================================================================

    /**
     * 检测到人工输入活动（半行/粘贴/全屏程序）。
     *
     * <p>仅在 {@code manual_idle} 时生效。{@code agent_owned} 期间的人工输入
     * 由停止/接管机制处理，不改变状态。</p>
     */
    public void onManualBusy() {
        synchronized (this) {
            if (state == State.MANUAL_IDLE) {
                state = State.MANUAL_BUSY;
            }
            // WHY 每次按键重排而非仅首次：自愈过期从最后一次按键起算，
            // 连续打字（粘贴同理）期间不会被中途清行打断
            if (state == State.MANUAL_BUSY) {
                scheduleBusyExpire();
            }
        }
    }

    /**
     * 人工输入活动结束（回到空提示符）。
     *
     * <p>仅在 {@code manual_busy} 时生效。恢复到 {@code manual_idle}，
     * Agent 可以再次领取输入权。</p>
     */
    public void onManualIdle() {
        synchronized (this) {
            if (state == State.MANUAL_BUSY) {
                state = State.MANUAL_IDLE;
                cancelBusyExpire();
                // WHY 尝试派发：可能有人工忙碌期间排队的命令等待执行
                tryDispatchNext();
            }
        }
    }

    /** 调度（或重排）busy 自愈任务：静默到期后若仍无 PROMPT 恢复证据则清行回 idle。 */
    private void scheduleBusyExpire() {
        cancelBusyExpire();
        pendingBusyExpire = scheduler.schedule(
                this::onBusyExpired, busyExpireMs, TimeUnit.MILLISECONDS);
    }

    /** 取消 busy 自愈任务（PROMPT 恢复/前台命令在跑/显式 idle/停止时调用）。 */
    private void cancelBusyExpire() {
        if (pendingBusyExpire != null) {
            pendingBusyExpire.cancel(false);
            pendingBusyExpire = null;
        }
    }

    /**
     * busy 自愈到期：用户停手超过 busyExpireMs 且无前台命令在跑。
     *
     * <p>发 Ctrl-C 清除疑似残留半行（参照现行 ssh 客户端注入前清行；空提示符上
     * Ctrl-C 只会产生新提示符，无副作用），再回 manual_idle 并派发排队命令，
     * 打破"busy 拒绝提交→不发命令→无 PROMPT→永久 busy"死锁。</p>
     */
    private void onBusyExpired() {
        synchronized (this) {
            // currentCommand != null 说明 Agent 命令在飞（state 应为 AGENT_OWNED），
            // 不在自愈范围内；STOPPING/UNKNOWN 同样不自愈
            if (state != State.MANUAL_BUSY || currentCommand != null) {
                return;
            }
            LOG.info("manual_busy 静默到期，清除疑似半行后自愈回 manual_idle");
            terminalSession.send(CTRL_C);
            state = State.MANUAL_IDLE;
            pendingBusyExpire = null;
            tryDispatchNext();
        }
    }

    /**
     * 正在停止——不再接受新命令。
     *
     * <p>如果有正在执行的命令，发送中断并取消超时。
     * 当前命令完成后（或超时后）状态不会回到 manual_idle。</p>
     */
    public void onStopping() {
        synchronized (this) {
            state = State.STOPPING;
            cancelTimeout();
            cancelWatch();
            cancelBusyExpire();
            cancelNestedProbe();
            if (currentCommand != null) {
                terminalSession.send(CTRL_C);
            }
            // 清空队列——不再执行排队的命令
            for (PendingCommand cmd : commandQueue) {
                cmd.future.completeExceptionally(
                        new IllegalStateException("调度器正在停止"));
            }
            commandQueue.clear();
        }
    }

    // ==================================================================
    // 命令提交
    // ==================================================================

    /**
     * 提交一条命令到调度器。
     *
     * <p>根据当前状态决定行为：</p>
     * <ul>
     *   <li>{@code manual_idle}：立即领取输入权，发送命令</li>
     *   <li>{@code agent_owned}：排队等待当前命令完成</li>
     *   <li>{@code manual_busy}：排队等待自愈（旧语义直接拒绝是死锁闭环的一环）</li>
     *   <li>其他状态：拒绝，返回已完成的异常 future</li>
     * </ul>
     *
     * @param command 要执行的 shell 命令
     * @return 命令结果的 future；被拒绝时已完成异常
     */
    public CompletableFuture<CommandResult> submitCommand(String command) {
        Objects.requireNonNull(command, "command 不得为 null");
        if (command.isEmpty()) {
            return failedFuture(new IllegalArgumentException("命令不得为空"));
        }

        synchronized (this) {
            // WHY 嵌套降级优先于状态机判定：集成重安装失败后帧协议不可用，
            // 命令边界无法识别，继续经 PTY 提交只会挂死——必须走 exec 回落
            if (nestedState == NestedState.FALLBACK) {
                return failedFuture(new IllegalArgumentException(
                        "嵌套 Shell 降级模式：PTY 集成不可用，请经 exec 通道执行"));
            }
            switch (state) {
                case MANUAL_IDLE:
                    return dispatchCommand(command);
                case AGENT_OWNED:
                    return enqueueCommand(command);
                case MANUAL_BUSY:
                    // WHY 排队而非拒绝：busy 可能来自无命令可跑的半行，拒绝会形成
                    // "拒绝→无 PROMPT→永久 busy"死锁；排队后由自愈到期或 PROMPT 恢复派发
                    return enqueueCommand(command);
                default:
                    return failedFuture(new IllegalStateException(
                            "当前状态 " + state + " 不接受命令提交"));
            }
        }
    }

    // ==================================================================
    // 帧处理（由 ShellFrameDecoder 回调）
    // ==================================================================

    /**
     * 处理一个解析出的 Shell 帧。
     *
     * <p>由运行时在 ShellFrameDecoder 的帧回调中调用。
     * 调度器根据帧类型和当前状态更新命令执行状态。</p>
     *
     * @param frame 解析出的控制帧
     */
    public void onFrame(ShellFrame frame) {
        if (frame == null) {
            return;
        }

        synchronized (this) {
            // 任何控制帧都是帧活动的证据——刷新嵌套检测计时器
            lastFrameNanos = System.nanoTime();
            // 帧到达说明集成正常工作，重置嵌套探测状态
            if (nestedState != NestedState.IDLE && nestedState != NestedState.FALLBACK) {
                cancelNestedProbe();
                nestedState = NestedState.IDLE;
                // WHY 同步清回显标志：帧到达即钩子存活，本窗口裁决已终结，
                // 残留标志混入下一窗口会造成假确认
                probeEchoed = false;
            }
            // WHY 帧后重排探测（兑现上方「刷新嵌套检测计时器」的语义）：帧只是暂时
            // 打断空闲判定，命令结束后仍需持续监测嵌套 Shell——docker exec 进容器
            // 后钩子失效是运行时常态；旧实现只在集成成功时装一次探测、帧一到即
            // 永久休眠，嵌套场景从此失察（AI 命令在容器内挂到超时，只能文本回复）
            scheduleNestedProbe();
            // 任何控制帧都是命令存活的证据（如长命令末尾的 CWD/CMD_END 帧），
            // 刷新空闲计时起点
            if (currentCommand != null) {
                currentCommand.lastActivityNanos = System.nanoTime();
            }
            switch (frame.type()) {
                case CMD_START:
                    handleCmdStart(frame);
                    break;
                case CMD_END:
                    handleCmdEnd(frame);
                    break;
                case CWD:
                    handleCwd(frame);
                    break;
                case PROMPT:
                    handlePrompt(frame);
                    break;
            }
        }
    }

    // ==================================================================
    // 输出采集
    // ==================================================================

    /**
     * 采集一段命令输出。
     *
     * <p>由运行时在收到 PTY 输出时调用。输出在 {@code agent_owned} 状态下
     * 被收集到当前命令的缓冲区中。超过 64 KiB 上限后丢弃超额内容，
     * 但标记 {@code truncated}。</p>
     *
     * <p>WHY 继续排空而不停止采集：PTY 是共享的，停止读取会让远端写满窗口
     * 阻塞，命令永远不结束。继续排空确保命令能正常完成。</p>
     *
     * @param data 一段终端输出（已经 ShellFrameDecoder 剥离控制帧后的干净文本）
     * @return 应继续转发给用户终端的文本；探针回显块返回空串（抑制透传）
     */
    public String collectOutput(String data) {
        if (data == null || data.isEmpty()) {
            return data == null ? "" : data;
        }
        synchronized (this) {
            // WHY 始终刷新帧活动时刻：嵌套 Shell 检测需要在无在飞命令时也能判定
            // "Shell 应该持续产出帧但实际没有"——任何 PTY 输出都说明 Shell 活跃
            lastFrameNanos = System.nanoTime();

            // WHY 探针回显只记录不确认：正常 bash 也会回显 echo 命令及其输出，
            // 「回显到达」不构成嵌套证据——嵌套与否取决于探针后有无 PROMPT 帧
            // （钩子活着必产 PROMPT）。旧逻辑回显即确认，健康 shell 空闲即被
            // 误判嵌套 → 重安装 → 新调度器再探测 → 死循环（内网实测多行探针
            // 泄漏 + 连接被循环重装打断）。确认改由探测超时统一裁决。
            if (nestedState == NestedState.PROBE_SENT && data.contains(NESTED_PROBE_MARKER)) {
                probeEchoed = true;
                // WHY 整块抑制探针回显：探针是普通 echo，其命令回显行与输出行都不在
                // OSC 帧协议内，解码器剥不掉；透传会直接漏到用户终端（内网实测 4 行
                // 探针泄漏）。探测窗口内终端无其他活动，含标记的数据块整体吞掉，
                // 噪声抑制优先于块内逐行保真；返回空串即通知转发方丢弃本块
                return "";
            }

            if (currentCommand != null && state == State.AGENT_OWNED) {
                // 输出活动刷新空闲计时：持续刷进度的安装命令不得被误杀（BUG-A）
                currentCommand.lastActivityNanos = System.nanoTime();
                currentCommand.appendOutput(data);
            } else if (currentCommand == null && manualCmdStartReceived) {
                // WHY 累积人工命令输出：CMD_START 后的 PTY 输出属于该命令的回显+结果，
                // 供回调提取命令文本与输出摘要，写入对话历史让 Agent 可见
                appendManualOutput(data);
            }

            // 未命中探针抑制：原样返回，转发方以返回值为透传依据
            return data;
        }
    }

    // ==================================================================
    // 内部：命令派发
    // ==================================================================

    private CompletableFuture<CommandResult> dispatchCommand(String command) {
        String cmdId = String.valueOf(commandIdCounter.incrementAndGet());
        PendingCommand pending = new PendingCommand(cmdId, command);
        // 存活计时从派发时刻起算：排队命令的构造时间不能计入空闲/绝对时长
        pending.dispatchedNanos = System.nanoTime();
        pending.lastActivityNanos = pending.dispatchedNanos;
        this.currentCommand = pending;
        this.state = State.AGENT_OWNED;
        this.cmdEndReceived = false;
        // WHY 重置人工命令追踪：Agent 命令接管 PTY 后，之前的人工命令追踪状态
        // 不再有意义，不清理会导致后续 PROMPT 帧误触发人工命令回调
        this.manualCmdStartReceived = false;

        // WHY 以换行结尾：PTY 的 stdin 模拟键盘输入，
        // 命令文本 + Enter 才会被 Shell 解析执行
        terminalSession.send(command + "\n");

        // 启动超时计时
        pendingTimeout = scheduler.schedule(
                this::onTimeout, timeoutMs, TimeUnit.MILLISECONDS);

        LOG.debug("命令已派发: cmdId={} command={}", cmdId, command);
        return pending.future;
    }

    private CompletableFuture<CommandResult> enqueueCommand(String command) {
        PendingCommand pending = new PendingCommand(
                String.valueOf(commandIdCounter.incrementAndGet()), command);
        commandQueue.add(pending);
        LOG.debug("命令已排队: cmdId={} queueSize={}", pending.commandId, commandQueue.size());
        return pending.future;
    }

    /** 尝试派发队列中的下一条命令（仅在 manual_idle 时生效）。 */
    private void tryDispatchNext() {
        if (state != State.MANUAL_IDLE || commandQueue.isEmpty()) {
            return;
        }
        PendingCommand next = commandQueue.poll();
        if (next != null) {
            dispatchCommand(next.commandText);
        }
    }

    // ==================================================================
    // 内部：帧处理
    // ==================================================================

    private void handleCmdStart(ShellFrame frame) {
        // WHY 仅记录日志：CMD_START 确认命令开始执行，
        // 但不改变状态机的转换——派发时已进入 agent_owned
        if (currentCommand != null) {
            LOG.trace("CMD_START: cmdId={}", frame.commandId());
        } else if (state == State.MANUAL_BUSY) {
            // busy 且无 Agent 命令在飞时收到 CMD_START = 用户前台命令在跑
            // （如 top/vim/嵌套 Shell），取消自愈——否则 Ctrl-C 会杀死用户程序，
            // 恢复重新依赖 PROMPT 链路（用例③语义）
            cancelBusyExpire();
            // WHY 标记人工命令开始：CMD_START 是帧协议确认的"命令正在执行"证据，
            // 后续的 collectOutput 输出才属于该命令（而非用户打字回显或 Shell 噪声）。
            // 重置输出缓冲以准备累积该命令的输出
            manualCmdStartReceived = true;
            manualOutputBuffer.setLength(0);
            manualCollectedBytes = 0;
            manualOutputTruncated = false;
            manualExitCode = ExecOutcome.EXIT_CODE_UNKNOWN;
        }
    }

    private void handleCmdEnd(ShellFrame frame) {
        // 解析退出码（Agent 命令与人工命令共用解析逻辑）
        int exitCode;
        try {
            exitCode = Integer.parseInt(frame.payload());
        } catch (NumberFormatException e) {
            exitCode = ExecOutcome.EXIT_CODE_UNKNOWN;
        }

        if (currentCommand != null && state == State.AGENT_OWNED) {
            currentCommand.exitCode = exitCode;
            cmdEndReceived = true;
            LOG.trace("CMD_END: cmdId={} exitCode={}", frame.commandId(), exitCode);
            // WHY 不在此处完成命令：还需等 PROMPT 帧确认 Shell 已回到提示符，
            // 否则下一条命令的输入可能与上一条的输出混在一起
        } else if (currentCommand == null && manualCmdStartReceived) {
            // WHY 记录人工命令退出码：CMD_END 帧的 cmdId=0 或不属于 Agent 命令，
            // 但退出码是人工命令完成的证据之一，供回调传递给上层持久化
            manualExitCode = exitCode;
            LOG.trace("CMD_END (manual): exitCode={}", exitCode);
        }
    }

    private void handleCwd(ShellFrame frame) {
        // 会话级 cwd 无条件更新：人工 cd（cmdId=0）与 Agent 命令的帧都是真实状态
        this.sessionCwd = frame.payload();
        if (currentCommand != null) {
            currentCommand.lastCwd = frame.payload();
        }
    }

    /**
     * 会话当前工作目录（供 Agent 系统提示词注入）。
     *
     * @return 最新 CWD 帧的路径；尚未收到任何 cwd 帧时为 null
     */
    public String sessionCwd() {
        return sessionCwd;
    }

    private void handlePrompt(ShellFrame frame) {
        if (currentCommand == null) {
            // WHY 不是直接 return：接线后 PROMPT 是"人工活动结束、回到空提示符"的
            // 唯一证据——WS input 钩子把状态标成 busy 后，若无此路径则永久 busy，
            // 后续获准命令全被拒（Shell → Agent 交接失效的根因之一）
            if (state == State.MANUAL_BUSY) {
                // WHY 人工命令完成回调：有 CMD_START 证据说明用户确实跑了一条命令，
                // 此时 PROMPT 标志着命令已完成（Shell 回到空提示符）。
                // 将命令原文、退出码、输出摘要传递给上层，供持久化到对话历史
                if (manualCmdStartReceived) {
                    fireManualCommandComplete();
                }
                state = State.MANUAL_IDLE;
                // PROMPT 是比自愈更强的恢复证据，取消待触发的清行任务
                cancelBusyExpire();
                // 重置人工命令追踪状态
                manualCmdStartReceived = false;
                // 可能有人工忙碌期间排队的命令等待执行
                tryDispatchNext();
            }
            return;
        }

        if (state == State.AGENT_OWNED && cmdEndReceived) {
            // 命令正常完成：CMD_END + PROMPT 序列
            cancelTimeout();
            completeCurrentCommand(false, false);
            state = State.STOPPING.equals(state) ? State.STOPPING : State.MANUAL_IDLE;
            // WHY 在状态恢复后再派发下一条：保证串行
            tryDispatchNext();
        } else if (state == State.AGENT_OWNED && !cmdEndReceived) {
            // PROMPT 但没有 CMD_END：可能是命令被中断后的提示符
            // 如果我们在等待中断结果（有 pendingWatch），这算完成证据
            if (pendingWatch != null) {
                cancelWatch();
                if (currentCommand.exitCode == null) {
                    currentCommand.exitCode = ExecOutcome.EXIT_CODE_UNKNOWN;
                }
                completeCurrentCommand(false, true);
                state = State.MANUAL_IDLE;
                tryDispatchNext();
            }
        }
    }

    // ==================================================================
    // 内部：超时与中断
    // ==================================================================

    /**
     * 超时检查点（watchdog 式）：空闲判定 + 绝对上限判定，活跃则续排。
     *
     * <p>WHY 续排而非一次性判定：旧实现在固定 60s 处无条件 Ctrl-C，
     * 安装类分钟级命令被误杀（BUG-A）。现在只有“连续 timeoutMs 无任何
     * 输出/帧”或“超过绝对上限”才中断；存活时按“距下次到期的时间”续排，
     * 检查频率与 timeoutMs 同量级，不引入轮询风暴。</p>
     */
    private void onTimeout() {
        synchronized (this) {
            if (currentCommand == null || state != State.AGENT_OWNED) {
                return;
            }
            long now = System.nanoTime();
            long idleMs = TimeUnit.NANOSECONDS.toMillis(now - currentCommand.lastActivityNanos);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(now - currentCommand.dispatchedNanos);

            if (idleMs < timeoutMs && elapsedMs < maxAbsoluteMs) {
                // 命令仍活跃：续排到下一个判定窗口（取两个条件中更早到期者）
                long delay = Math.min(timeoutMs - idleMs, maxAbsoluteMs - elapsedMs);
                pendingTimeout = scheduler.schedule(this::onTimeout,
                        Math.max(delay, 20), TimeUnit.MILLISECONDS);
                return;
            }

            if (elapsedMs >= maxAbsoluteMs) {
                LOG.warn("命令达到绝对执行上限（{}ms），发送中断: cmdId={}", maxAbsoluteMs, currentCommand.commandId);
            } else {
                LOG.warn("命令空闲超时（{}ms 无输出），发送中断: cmdId={}", idleMs, currentCommand.commandId);
            }
            interruptCurrentLocked();
        }
    }

    /**
     * 主动中断当前在飞命令（用户停止 Agent 回合时由 AiAgentService 经网关调用）。
     *
     * <p>与超时中断同链路：发 Ctrl-C 后进观察窗口，等到 PROMPT/CMD_END
     * 完成证据则回 manual_idle；无证据则转 unknown。区别仅在于不带
     * onStopping 的“永久停止”语义，会话后续仍可继续接受命令。</p>
     */
    public void interruptCurrent() {
        synchronized (this) {
            if (currentCommand == null || state != State.AGENT_OWNED) {
                return; // 无在飞命令：无副作用
            }
            LOG.info("主动中断在飞命令（用户停止回合）: cmdId={}", currentCommand.commandId);
            interruptCurrentLocked();
        }
    }

    /** 中断当前命令（需在锁内）：Ctrl-C + 观察窗口等待完成证据。 */
    private void interruptCurrentLocked() {
        cancelTimeout();
        // 发送 Ctrl-C 中断命令
        terminalSession.send(CTRL_C);
        currentCommand.timedOut = true;

        // 启动观察窗口：interruptWatchMs 内等待完成证据
        pendingWatch = scheduler.schedule(
                this::onWatchExpired, interruptWatchMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 观察窗口到期——中断后无完成证据，转为 unknown。
     *
     * <p>design.md D3：中断后 3 秒仍无完成证据则标记 unknown，
     * 停止自动推进，提示人工检查或重新连接。</p>
     */
    private void onWatchExpired() {
        synchronized (this) {
            if (currentCommand == null || state != State.AGENT_OWNED) {
                return; // 已在其他路径完成
            }
            LOG.warn("中断后观察窗口到期，无完成证据，转为 unknown");
            // 完成当前命令（标记超时 + 退出码未知）
            if (currentCommand.exitCode == null) {
                currentCommand.exitCode = ExecOutcome.EXIT_CODE_UNKNOWN;
            }
            completeCurrentCommand(true, true);
            state = State.UNKNOWN;

            // 清空队列——无法判定命令边界，不再自动执行
            for (PendingCommand cmd : commandQueue) {
                cmd.future.completeExceptionally(
                        new IllegalStateException("调度器状态为 unknown，无法继续执行"));
            }
            commandQueue.clear();
        }
    }

    private void completeCurrentCommand(boolean forceUnknown, boolean cancelTimers) {
        if (currentCommand == null) {
            return;
        }
        if (cancelTimers) {
            cancelTimeout();
            cancelWatch();
        }

        CommandResult result = new CommandResult(
                currentCommand.exitCode != null ? currentCommand.exitCode : ExecOutcome.EXIT_CODE_UNKNOWN,
                currentCommand.collectedOutput(),
                currentCommand.truncated,
                currentCommand.timedOut,
                currentCommand.lastCwd);

        currentCommand.future.complete(result);
        currentCommand = null;
        cmdEndReceived = false;
    }

    private void cancelTimeout() {
        if (pendingTimeout != null) {
            pendingTimeout.cancel(false);
            pendingTimeout = null;
        }
    }

    private void cancelWatch() {
        if (pendingWatch != null) {
            pendingWatch.cancel(false);
            pendingWatch = null;
        }
    }

    // ==================================================================
    // 嵌套 Shell 探测
    // ==================================================================

    /**
     * 调度嵌套 Shell 探测任务：MANUAL_IDLE 且距上次帧活动超过阈值时触发。
     *
     * <p>WHY 在 onIntegrationSuccess 后启动而非构造器：集成未成功时探测无意义，
     * 且 onIntegrationSuccess 会重置 lastFrameNanos 作为首次探测的起算点。</p>
     */
    private void scheduleNestedProbe() {
        if (nestedDetectTimeoutMs <= 0) {
            return; // 禁用嵌套检测
        }
        cancelNestedProbe();
        pendingNestedProbe = scheduler.schedule(
                this::onNestedProbeTimeout, nestedDetectTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /** 取消嵌套探测任务（帧到达/命令派发/停止时调用）。 */
    private void cancelNestedProbe() {
        if (pendingNestedProbe != null) {
            pendingNestedProbe.cancel(false);
            pendingNestedProbe = null;
        }
    }

    /**
     * 嵌套探测定时器到期：检查帧活动是否恢复。
     *
     * <p>若仍处于 MANUAL_IDLE 且无帧活动，发送探测命令。若已发送探测但未收到回显，
     * 再等一个超时后降级。</p>
     */
    private void onNestedProbeTimeout() {
        synchronized (this) {
            if (state != State.MANUAL_IDLE || currentCommand != null) {
                // 有命令在飞或状态不是 idle：本轮探测无意义——重置并顺延到下个周期
                // （WHY 顺延：命令在飞期间定时器若中断则无人重启，命令结束后
                // 嵌套检测将永久失察）
                nestedState = NestedState.IDLE;
                scheduleNestedProbe();
                return;
            }

            long idleMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lastFrameNanos);
            if (idleMs < nestedDetectTimeoutMs) {
                // 帧活动已恢复（用户刚敲过键或收到帧），重新调度
                scheduleNestedProbe();
                return;
            }

            if (nestedState == NestedState.IDLE) {
                // 发送探测命令（WHY debug 级：探测修复后会周期性复发于空闲会话，
                // info 会刷日志；确认嵌套的关键事件另有 info 日志）
                LOG.debug("帧活动超时（{}ms），发送嵌套 Shell 探测命令", idleMs);
                nestedState = NestedState.PROBE_SENT;
                // WHY 每轮探测前重置回显标志：上一窗口的残留标志不得参与本轮裁决
                probeEchoed = false;
                terminalSession.send("echo " + NESTED_PROBE_MARKER + "$$\n");
                // 再等一个超时周期确认探测结果
                scheduleNestedProbe();
            } else if (nestedState == NestedState.PROBE_SENT) {
                if (probeEchoed) {
                    // WHY 回显+窗口内无帧才构成嵌套确认：窗口内回显到达只证明 shell
                    // 存活（正常 bash 也回显 echo），整个窗口没有产生任何集成帧才证明
                    // 钩子失效（钩子活着必产 PROMPT）。旧逻辑「回显即确认」把健康 shell
                    // 的空闲也判成嵌套 → 重安装 → 新调度器再探测 → 死循环（内网实测：
                    // 探针泄漏 + 连接被反复重装打断 + 会话重建丢对话记忆）。
                    // WHY 确认后直接降级且不再重排：重安装回调会以新调度器替换本实例，
                    // 本调度器若继续探测只会重复触发重安装；若回调异常未替换成功，
                    // FALLBACK 让后续命令走 exec 通道兜底，不再空耗探测周期
                    LOG.info("嵌套 Shell 确认：探针有回显但窗口内无集成帧，触发集成重安装");
                    Runnable cb = nestedShellCallback;
                    nestedState = NestedState.FALLBACK;
                    probeEchoed = false;
                    if (cb != null) {
                        cb.run();
                    }
                } else {
                    // 探测已发送但未收到回显（也无帧）：可能是用户离开座位，
                    // 不一定是嵌套 Shell——重置为 IDLE 避免误判
                    LOG.debug("嵌套探测已发送但无回显，重置为 IDLE（可能是用户离开）");
                    nestedState = NestedState.IDLE;
                    scheduleNestedProbe();
                }
            }
        }
    }

    // ==================================================================
    // 人工命令追踪：输出累积与回调
    // ==================================================================

    /**
     * 追加一段输出到人工命令缓冲。
     *
     * <p>与 Agent 命令的 {@link PendingCommand#appendOutput} 逻辑类似，
     * 但独立缓冲——人工命令输出不进 Agent 命令的采集缓冲区。</p>
     */
    private void appendManualOutput(String data) {
        byte[] bytes = data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int room = MAX_COLLECTION_BYTES - manualCollectedBytes;
        if (room > 0) {
            int take = Math.min(room, bytes.length);
            manualOutputBuffer.append(data, 0, Math.min(take, data.length()));
            manualCollectedBytes += take;
        }
        if (manualCollectedBytes >= MAX_COLLECTION_BYTES) {
            manualOutputTruncated = true;
        }
    }

    /**
     * 触发人工命令完成回调：提取命令文本（从输出缓冲的第一行回显），
     * 构造 {@link ManualCommandInfo} 并通知上层。
     */
    private void fireManualCommandComplete() {
        String fullOutput = manualOutputBuffer.toString();
        String commandText = extractCommandFromEcho(fullOutput);
        String cwd = this.sessionCwd;

        ManualCommandListener listener = this.manualCommandListener;
        if (listener != null) {
            ManualCommandInfo info = new ManualCommandInfo(
                    commandText, manualExitCode, fullOutput,
                    manualOutputTruncated, cwd);
            try {
                listener.onManualCommandComplete(info);
            } catch (Exception e) {
                LOG.warn("人工命令回调异常: command={} cause={}", commandText, e.getMessage());
            }
        } else {
            LOG.debug("人工命令完成但无回调注册: command={} exitCode={}", commandText, manualExitCode);
        }
    }

    /**
     * 从 PTY 输出缓冲中提取命令文本（第一行回显）。
     *
     * <p>PTY 输出包含命令回显（用户键入的文本被终端回显）和命令输出。
     * 命令文本通常是第一行（去掉首尾空白和 \r）。</p>
     *
     * @param output 完整的 PTY 输出缓冲
     * @return 提取的命令文本；无法提取时返回空字符串
     */
    static String extractCommandFromEcho(String output) {
        if (output == null || output.isEmpty()) {
            return "";
        }
        // 取第一行作为命令回显
        int nlIdx = output.indexOf('\n');
        String firstLine = nlIdx >= 0 ? output.substring(0, nlIdx) : output;
        // 去掉 \r 和首尾空白
        return firstLine.replace("\r", "").trim();
    }

    /**
     * 人工命令完成信息。
     *
     * @param command   命令原文（从 PTY 回显提取）
     * @param exitCode  退出码
     * @param output    完整输出（含命令回显 + 命令结果）
     * @param truncated 输出是否被截断
     * @param cwd       命令执行后的工作目录
     */
    public record ManualCommandInfo(
            String command,
            int exitCode,
            String output,
            boolean truncated,
            String cwd) {}

    /**
     * 人工命令完成回调接口。
     *
     * <p>WHY 独立接口：调度器不依赖 ConversationService 等上层服务类，
     * 通过回调解耦，保持调度器的纯 SSH 层职责。</p>
     */
    @FunctionalInterface
    public interface ManualCommandListener {
        void onManualCommandComplete(ManualCommandInfo info);
    }

    // ==================================================================
    // 结果类型
    // ==================================================================

    /**
     * 一条命令的执行结果。
     *
     * @param exitCode       远端退出码
     * @param stdout         采集的标准输出（已按上限截断）
     * @param truncated      输出是否因超过采集上限而被截断
     * @param timedOut       命令是否因超时被中断
     * @param workingDirectory 命令执行后的工作目录（可能为 null）
     */
    public record CommandResult(
            int exitCode,
            String stdout,
            boolean truncated,
            boolean timedOut,
            String workingDirectory) {

        public CommandResult {
            stdout = stdout == null ? "" : stdout;
        }
    }

    // ==================================================================
    // 内部：挂起的命令
    // ==================================================================

    /**
     * 正在执行或排队的命令。
     *
     * <p>WHY 不用 record：命令在执行过程中需要累积状态（输出、退出码、cwd），
     * 这些字段需要可变。</p>
     */
    private final class PendingCommand {
        final String commandId;
        final String commandText;
        final CompletableFuture<CommandResult> future = new CompletableFuture<>();

        /** 派发时刻（纳秒）：绝对上限的起算点（锁内访问，排队期不计入）。 */
        long dispatchedNanos;
        /** 最近一次输出/控制帧时刻（纳秒）：空闲超时的起算点（锁内访问）。 */
        long lastActivityNanos;

        // 累积状态
        final StringBuilder outputBuffer = new StringBuilder();
        int collectedBytes = 0;
        boolean truncated = false;
        boolean timedOut = false;
        Integer exitCode = null;
        String lastCwd = null;

        PendingCommand(String commandId, String commandText) {
            this.commandId = commandId;
            this.commandText = commandText;
        }

        /**
         * 追加一段输出到采集缓冲区。
         *
         * <p>超过 {@link #MAX_COLLECTION_BYTES} 后丢弃超额内容但标记截断。
         * WHY 继续排空：见 {@link #collectOutput} 的注释。</p>
         */
        void appendOutput(String data) {
            byte[] bytes = data.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int room = MAX_COLLECTION_BYTES - collectedBytes;
            if (room > 0) {
                int take = Math.min(room, bytes.length);
                // 按字符追加（可能比字节少，但不会超过上限）
                outputBuffer.append(data, 0, Math.min(take, data.length()));
                collectedBytes += take;
            }
            if (collectedBytes >= MAX_COLLECTION_BYTES) {
                truncated = true;
            }
        }

        String collectedOutput() {
            return outputBuffer.toString();
        }
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    private static <T> CompletableFuture<T> failedFuture(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }
}
