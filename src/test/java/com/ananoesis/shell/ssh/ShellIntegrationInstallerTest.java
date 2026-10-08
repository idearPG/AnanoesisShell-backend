package com.ananoesis.shell.ssh;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ShellIntegrationInstaller 接线测试（task 5.2-5.5 的组装缺口）。
 *
 * <p>5.x 各组件（安装器/解码器/调度器/网关）单测全绿，但没有任何代码把它们
 * <b>装到连接 runtime 上</b>——真实运行时 {@code runtime.scheduler()} 恒为 null，
 * 获准命令与只读工具永远回落 exec，Shell → Agent 的 cwd 交接失效。
 * 本类锁定接线契约：安装成功产出可用的调度器与"剥帧 + 采集"的输出包装；
 * 安装失败（不支持的 Shell）保持人工终端原样、不装调度器。</p>
 */
@DisplayName("ShellIntegrationInstaller")
class ShellIntegrationInstallerTest {

    private ShellIntegrationTest.CapturingTerminalSession terminal;
    private CollectingListener delegate;
    private ScheduledExecutorService timeouts;

    @BeforeEach
    void setUp() {
        terminal = new ShellIntegrationTest.CapturingTerminalSession();
        delegate = new CollectingListener();
        timeouts = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void tearDown() {
        timeouts.shutdownNow();
    }

    @Test
    @DisplayName("bash：装载调度器，包装监听器剥离控制帧并把干净文本转发给 delegate")
    void bashWiresSchedulerAndFrameStrippingListener() {
        ShellIntegrationInstaller.Outcome outcome =
                ShellIntegrationInstaller.install(terminal, "bash", timeouts, delegate, NOOP_SWITCH);

        assertThat(outcome.scheduler()).isNotNull();
        assertThat(outcome.listener()).isNotSameAs(delegate);
        assertThat(outcome.nonce()).isNotEmpty();
        // 安装钩子代码已写入 PTY
        assertThat(String.join("", terminal.sentData)).contains(outcome.nonce());

        // 喂入「文本 + 控制帧 + 文本」：delegate 只应看到干净文本
        outcome.listener().onStdout("hello"
                + "\u001B]1337;cwd;" + outcome.nonce() + ";c1;/var/log\u0007"
                + "world");
        assertThat(delegate.stdout).containsExactly("helloworld");
    }

    @Test
    @DisplayName("不支持的 Shell：不装调度器、不切输出链、原样透传监听器，人工终端不受影响")
    void unsupportedShellKeepsManualTerminalUntouched() {
        List<TerminalOutputListener> switched = new ArrayList<>();
        ShellIntegrationInstaller.Outcome outcome = ShellIntegrationInstaller.install(
                terminal, "zsh", timeouts, delegate, switched::add);

        assertThat(outcome.scheduler()).isNull();
        assertThat(outcome.listener()).isSameAs(delegate);
        assertThat(terminal.sentData).isEmpty();
        assertThat(switched).as("无安装代码即无噪声，不得装闸门改变输出链").isEmpty();
    }

    @Test
    @DisplayName("bash：安装回显在首个控制帧前一律吞掉——readline 自回显不受 stty 影响，只能服务端闸门")
    void bashMutesInstallEchoUntilFirstFrame() {
        List<TerminalOutputListener> switched = new ArrayList<>();
        ShellIntegrationInstaller.Outcome outcome = ShellIntegrationInstaller.install(
                terminal, "bash", timeouts, delegate, switched::add);

        assertThat(switched).as("写安装代码前必须先把输出链切到闸门监听器").hasSize(1);
        TerminalOutputListener gate = switched.get(0);

        // readline 回显的是安装代码的**字面源码**（反斜杠+033 文本，非真实 ESC 字节），
        // 解码器剥不掉——这正是真实 CentOS 首连噪声的形态，必须由闸门吞掉
        gate.onStdout("[root@localhost ~]# stty -echo\n");
        gate.onStdout("[root@localhost ~]# { _ANANOESIS_NONCE='...'; printf '\\033]1337;...\n");
        assertThat(delegate.stdout).as("首个真实帧之前的输出不得转发给前端").isEmpty();

        // 钩子生效后的第一个真实帧（ESC 0x1B + BEL 0x07）：剥帧后的干净文本放行，
        // 用户由此看到第一个正常提示符
        gate.onStdout("\u001B]1337;prompt;" + outcome.nonce() + ";0;\u0007[root@localhost ~]# ");
        assertThat(delegate.stdout).containsExactly("[root@localhost ~]# ");
    }

    @Test
    @DisplayName("闸门超时兜底：钩子始终不回帧时放行输出，不把用户终端哑掉")
    void gateOpensByTimeoutWhenNoFrameEverArrives() throws Exception {
        List<TerminalOutputListener> switched = new ArrayList<>();
        // 用短超时避免测试等待生产兜底窗口（5 秒）
        ShellIntegrationInstaller.install(terminal, "bash", timeouts, delegate, switched::add, 150);
        TerminalOutputListener gate = switched.get(0);

        gate.onStdout("before-timeout");
        assertThat(delegate.stdout).isEmpty();

        Thread.sleep(400);
        gate.onStdout("after-timeout");
        assertThat(delegate.stdout).containsExactly("after-timeout");
    }

    @Test
    @DisplayName("端到端（帧模拟）：提交命令经 PTY 执行，输出采集、退出码与 cwd 由帧驱动完成")
    void submittedCommandCompletesThroughFrames() throws Exception {
        ShellIntegrationInstaller.Outcome outcome =
                ShellIntegrationInstaller.install(terminal, "bash", timeouts, delegate, NOOP_SWITCH);
        PtyCommandScheduler scheduler = outcome.scheduler();
        String nonce = outcome.nonce();

        CompletableFuture<PtyCommandScheduler.CommandResult> future = scheduler.submitCommand("pwd");
        // 命令文本 + 换行 已写入 PTY
        assertThat(String.join("", terminal.sentData)).contains("pwd\n");
        assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.AGENT_OWNED);

        // 模拟远端 Bash 钩子回帧：cmd_start → 输出 → cmd_end(0) → cwd → prompt
        // WHY cmd_start 先行：闸门在第一个真实帧打开，与真实 bash 的 DEBUG 钩子时序一致
        outcome.listener().onStdout("\u001B]1337;cmd_start;" + nonce + ";1;\u0007");
        outcome.listener().onStdout("/var/log\r\n");
        outcome.listener().onStdout("\u001B]1337;cmd_end;" + nonce + ";1;0\u0007");
        outcome.listener().onStdout("\u001B]1337;cwd;" + nonce + ";1;/var/log\u0007");
        outcome.listener().onStdout("\u001B]1337;prompt;" + nonce + ";1;\u0007");

        PtyCommandScheduler.CommandResult result = future.get(5, TimeUnit.SECONDS);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("/var/log");
        assertThat(result.workingDirectory()).isEqualTo("/var/log");
        // agent 命令的输出不转发给 delegate（避免与回流文本重复展示由后续策略决定），
        // 但控制帧必须被剥离——delegate 收到的每段文本都不含 OSC 帧
        assertThat(delegate.stdout).allSatisfy(text -> assertThat(text).doesNotContain("1337"));
        // 命令完成后回到人工空闲，等待下一次交接
        assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
    }

    @Test
    @DisplayName("会话关闭联动调度器停止：排队与在飞命令不再自动推进")
    void closedSessionStopsScheduler() {
        ShellIntegrationInstaller.Outcome outcome =
                ShellIntegrationInstaller.install(terminal, "bash", timeouts, delegate, NOOP_SWITCH);

        outcome.listener().onClosed(SshCloseReason.USER_DISCONNECT);

        assertThat(outcome.scheduler().state()).isEqualTo(PtyCommandScheduler.State.STOPPING);
        assertThat(delegate.closedReasons).containsExactly(SshCloseReason.USER_DISCONNECT);
    }

    /** 不关心输出链切换的用例传入的空回调。 */
    private static final java.util.function.Consumer<TerminalOutputListener> NOOP_SWITCH = l -> { };

    /** 记录转发内容的 delegate 桩。 */
    private static final class CollectingListener implements TerminalOutputListener {
        final List<String> stdout = new ArrayList<>();
        final List<SshCloseReason> closedReasons = new ArrayList<>();

        @Override
        public void onStdout(String data) {
            stdout.add(data);
        }

        @Override
        public void onStderr(String data) {
            // 本测试不关心 stderr
        }

        @Override
        public void onClosed(SshCloseReason reason) {
            closedReasons.add(reason);
        }
    }
}
