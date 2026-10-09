package com.ananoesis.shell.ssh;

import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

/**
 * 把 5.2-5.5 的 Shell 集成组件<b>接线</b>到一条新建立的 PTY 会话（task 5.2/5.3）。
 *
 * <h2>WHY 需要这个类</h2>
 * <p>{@link ShellIntegration}、{@link ShellFrameDecoder}、{@link PtyCommandScheduler}
 * 各自单测全绿，但没有任何代码把它们装配进 {@link SessionRuntime}——真实运行时
 * {@code runtime.scheduler()} 恒为 null，{@code PtyCommandGateway} 永远抛
 * "会话未安装 Shell 集成"，获准命令与只读工具全部回落 exec，Shell → Agent 的
 * cwd/env 交接失效。本类就是缺失的那段接缝：安装钩子 → 构造解码器与调度器 →
 * 产出"剥帧 + 采集"的输出包装监听器。</p>
 *
 * <h2>数据流（安装成功后）</h2>
 * <pre>
 * PTY 输出 → 闸门监听器.onStdout
 *              ├─ decoder.decode()：剥离 OSC 控制帧（首个真实帧同时打开闸门）
 *              ├─ scheduler.collectOutput(干净文本)：agent_owned 时采集命令输出（不受闸门影响）
 *              └─ 闸门已开 → delegate.onStdout(干净文本)；未开 → 吞掉安装回显
 * </pre>
 *
 * <h2>为什么要服务端闸门</h2>
 * <p>安装代码是“打”进交互式 bash 的，而 bash readline 会自己回显输入的字符，
 * <b>不受 tty ECHO 标志控制</b>（stty -echo 压不住，见 known-issues #14）；回显的又是
 * 安装代码的字面源码（反斜杠+033 文本），解码器也剥不掉。唯一可靠的治理点：
 * 写安装代码<b>前</b>先把输出链切到闸门监听器，吞掉首个真实控制帧之前的一切转发；
 * 钩子生效（首个帧到达）后自动放行。超时兜底防钩子永不生效时把用户终端哑掉。</p>
 *
 * <h2>失败姿势</h2>
 * <p>不支持的 Shell（非 bash）：不写任何钩子代码、不装调度器、不动输出链——
 * 人工终端与 SFTP 完全不受影响（design D3 的降级承诺）。</p>
 */
public final class ShellIntegrationInstaller {

    private static final Logger LOG = LoggerFactory.getLogger(ShellIntegrationInstaller.class);

    /**
     * 生产闸门兜底超时。
     *
     * <p>WHY 5 秒：正常链路里钩子安装代码在 ~450ms 内执行完并回第一个 prompt 帧；
     * 超 5 秒无帧说明远端钩子未生效（异常 bash/权限限制），此时宁可暴露噪声
     * 也不能把用户终端永久闭嘴——输出可见性优先于美观。</p>
     */
    static final long DEFAULT_GATE_TIMEOUT_MS = 5_000;

    private ShellIntegrationInstaller() {
    }

    /**
     * 接线结果。
     *
     * @param scheduler 已就绪的调度器；Shell 不受支持时为 null（不安装即不拦截）
     * @param listener  必须替换进 runtime 输出链的监听器；未安装时就是原 delegate
     * @param nonce     集成 nonce（日志与排障用；未安装时为空串）
     */
    public record Outcome(@Nullable PtyCommandScheduler scheduler,
                          TerminalOutputListener listener,
                          String nonce) {
    }

    /**
     * 在既有 PTY 会话上安装 Shell 集成并完成组件接线（生产超时）。
     *
     * @param terminal           已建立的 PTY 会话（安装代码经其 send 写入远端）
     * @param shellType          远端 Shell 类型（配置提供，第一阶段仅 bash 受支持）
     * @param timeouts           命令超时/观察窗口/闸门兜底的共享调度线程池
     * @param delegate           下游输出监听器（WS 桥接），包装后向其转发干净文本
     * @param outputChainSwitch  输出链切换回调；实现方（SshTerminalService）传入
     *                           relay::switchTo，installer 在<b>写安装代码之前</b>
     *                           经它把闸门接进输出链
     * @return 接线结果；调用方 MUST 用 {@code outcome.listener()} 替换输出链，
     *         并在 scheduler 非 null 时 {@code runtime.setScheduler(...)}
     */
    public static Outcome install(SshTerminalSession terminal, String shellType,
                                  ScheduledExecutorService timeouts,
                                  TerminalOutputListener delegate,
                                  Consumer<TerminalOutputListener> outputChainSwitch) {
        return install(terminal, shellType, timeouts, delegate, outputChainSwitch,
                DEFAULT_GATE_TIMEOUT_MS);
    }

    /**
     * 可指定闸门兜底超时的包内重载（测试用短超时，避免等待生产 5 秒窗口）。
     */
    static Outcome install(SshTerminalSession terminal, String shellType,
                           ScheduledExecutorService timeouts,
                           TerminalOutputListener delegate,
                           Consumer<TerminalOutputListener> outputChainSwitch,
                           long gateTimeoutMillis) {
        Objects.requireNonNull(terminal, "terminal 不得为 null");
        Objects.requireNonNull(timeouts, "timeouts 不得为 null");
        Objects.requireNonNull(delegate, "delegate 不得为 null");
        Objects.requireNonNull(outputChainSwitch, "outputChainSwitch 不得为 null");

        ShellIntegration integration = new ShellIntegration(terminal, shellType);
        if (!ShellIntegration.SHELL_BASH.equals(shellType)) {
            // 降级即设计：无帧协议 → 无法界定命令边界 → 不装调度器也不装闸门，
            // 保留纯人工终端；没有安装代码就没有噪声，输出链保持原样
            LOG.info("Shell 集成未安装（type={}），会话保持人工模式: session={}",
                    shellType, terminal.id());
            return new Outcome(null, delegate, "");
        }

        String nonce = ShellIntegration.newNonce();
        PtyCommandScheduler scheduler =
                new PtyCommandScheduler(terminal, nonce, timeouts);
        // 闸门状态：首个真实帧到达（onFrame 回调）或兜底超时后置开。
        // WHY volatile：读泵线程写/读，超时调度线程写，无锁协调只需可见性保证
        final boolean[] gateOpen = {false};
        ShellFrameDecoder decoder = new ShellFrameDecoder(nonce, frame -> {
            gateOpen[0] = true;
            scheduler.onFrame(frame);
        });
        // 钩子代码已写入且协议可用：状态机进入 manual_idle，Agent 可领取输入权
        scheduler.onIntegrationSuccess();

        TerminalOutputListener wrapping = new TerminalOutputListener() {

            @Override
            public void onStdout(String data) {
                // WHY 先剥帧再采集再判闸门：三段顺序缺一不可——
                // 帧驱动调度器的 CMD_START/CMD_END/PROMPT 状态转换；
                // 干净文本供采集（agent 命令输出）——采集 MUST NOT 被闸门吞，
                // 否则 Agent 命令输出丢失；展示转发才受闸门控制
                String clean = decoder.decode(data);
                // WHY 以采集返回值为转发依据：嵌套探针回显块由 collectOutput 判定
                // 并抑制（返回空串），转发方必须尊重返回值，探针噪声才不会漏到用户终端
                String forward = scheduler.collectOutput(clean);
                if (gateOpen[0] && !forward.isEmpty()) {
                    delegate.onStdout(forward);
                }
            }

            @Override
            public void onStderr(String data) {
                // stderr 读泵与 stdout 是独立通道，PTY 模式下实际已合并进 stdout；
                // 安装期噪声全在 stdout，stderr 直接透传保持既有行为
                delegate.onStderr(data);
            }

            @Override
            public void onClosed(SshCloseReason reason) {
                // 会话终结必须联动调度器：中断在飞命令、拒绝新提交、清掉排队命令，
                // 否则 future 悬挂会拖住审批/工具线程；closed 通知不受闸门拦截
                scheduler.onStopping();
                delegate.onClosed(reason);
            }
        };

        // 顺序是本修复的全部意义：先把闸门接进输出链，再往 PTY 写安装代码；
        // 颠倒则安装回显经旧链路泄漏给前端（旧版 bug，见 known-issues #14）
        outputChainSwitch.accept(wrapping);
        integration.install(nonce);
        timeouts.schedule(() -> gateOpen[0] = true,
                gateTimeoutMillis, TimeUnit.MILLISECONDS);
        return new Outcome(scheduler, wrapping, nonce);
    }

    /**
     * 可配置嵌套检测超时的安装（生产环境使用）。
     *
     * @param nestedDetectTimeoutMs 嵌套 Shell 帧超时检测阈值（毫秒），0 表示禁用
     */
    public static Outcome install(SshTerminalSession terminal, String shellType,
                                  ScheduledExecutorService timeouts,
                                  TerminalOutputListener delegate,
                                  Consumer<TerminalOutputListener> outputChainSwitch,
                                  long gateTimeoutMillis,
                                  long nestedDetectTimeoutMs) {
        // WHY 与无 nestedDetectTimeoutMs 参数版本相同逻辑，只是调度器构造时传入自定义超时
        Objects.requireNonNull(terminal, "terminal 不得为 null");
        Objects.requireNonNull(timeouts, "timeouts 不得为 null");
        Objects.requireNonNull(delegate, "delegate 不得为 null");
        Objects.requireNonNull(outputChainSwitch, "outputChainSwitch 不得为 null");

        ShellIntegration integration = new ShellIntegration(terminal, shellType);
        if (!ShellIntegration.SHELL_BASH.equals(shellType)) {
            return new Outcome(null, delegate, "");
        }

        String nonce = ShellIntegration.newNonce();
        PtyCommandScheduler scheduler = new PtyCommandScheduler(
                terminal, nonce, timeouts,
                PtyCommandScheduler.DEFAULT_TIMEOUT_MS,
                PtyCommandScheduler.DEFAULT_INTERRUPT_WATCH_MS,
                PtyCommandScheduler.DEFAULT_MAX_ABSOLUTE_MS,
                PtyCommandScheduler.DEFAULT_BUSY_EXPIRE_MS,
                nestedDetectTimeoutMs);
        final boolean[] gateOpen = {false};
        ShellFrameDecoder decoder = new ShellFrameDecoder(nonce, frame -> {
            gateOpen[0] = true;
            scheduler.onFrame(frame);
        });
        scheduler.onIntegrationSuccess();

        TerminalOutputListener wrapping = new TerminalOutputListener() {
            @Override
            public void onStdout(String data) {
                String clean = decoder.decode(data);
                // WHY 以采集返回值为转发依据：嵌套探针回显块由 collectOutput 判定
                // 并抑制（返回空串），转发方必须尊重返回值，探针噪声才不会漏到用户终端
                String forward = scheduler.collectOutput(clean);
                if (gateOpen[0] && !forward.isEmpty()) {
                    delegate.onStdout(forward);
                }
            }
            @Override
            public void onStderr(String data) {
                delegate.onStderr(data);
            }
            @Override
            public void onClosed(SshCloseReason reason) {
                scheduler.onStopping();
                delegate.onClosed(reason);
            }
        };

        outputChainSwitch.accept(wrapping);
        integration.install(nonce);
        timeouts.schedule(() -> gateOpen[0] = true,
                gateTimeoutMillis, TimeUnit.MILLISECONDS);
        return new Outcome(scheduler, wrapping, nonce);
    }

    /**
     * 在既有 PTY 会话上重新安装 Shell 集成（嵌套 Shell 检测后使用新 nonce）。
     *
     * <p>复用现有 install 路径，但使用新 nonce 和新调度器。返回新 Outcome，
     * 调用方负责替换 runtime 的 scheduler 和输出链。</p>
     */
    public static Outcome reinstall(SshTerminalSession terminal, String shellType,
                                    ScheduledExecutorService timeouts,
                                    TerminalOutputListener delegate,
                                    Consumer<TerminalOutputListener> outputChainSwitch) {
        return install(terminal, shellType, timeouts, delegate, outputChainSwitch);
    }
}
