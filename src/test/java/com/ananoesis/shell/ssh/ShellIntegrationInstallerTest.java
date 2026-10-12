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
    @DisplayName("输出与完成帧同批到达：输出必须先行采集，帧后处理（内网实测 exit=0 空输出根因）")
    void outputAndCompletionFramesInSameBatchCollectOutputFirst() throws Exception {
        ShellIntegrationInstaller.Outcome outcome =
                ShellIntegrationInstaller.install(terminal, "bash", timeouts, delegate, NOOP_SWITCH);
        PtyCommandScheduler scheduler = outcome.scheduler();
        String nonce = outcome.nonce();

        CompletableFuture<PtyCommandScheduler.CommandResult> future =
                scheduler.submitCommand("ls -Al '/root'");
        assertThat(String.join("", terminal.sentData)).contains("ls -Al '/root'\n");

        // 远端先回 CMD_START（真实 bash 的 DEBUG 钩子时序：命令执行前出帧）
        outcome.listener().onStdout("\u001B]1337;cmd_start;" + nonce + ";1;\u0007");

        // WHY 同批喂入输出 + 完成帧 + 提示符文本：SSH 分块是随机的，内网快命令的
        // 输出常与 CMD_END/PROMPT 帧落在同一批数据里。旧实现 decode 先同步回调帧
        // （PROMPT 处提前完成命令），输出随后在 collectOutput 里找不到采集目标被
        // 丢弃——工具拿到 exit=0 空 stdout，模型幻觉文件列表。修复后必须先采集
        // 整批文本、后处理帧，输出才能完整归属命令。
        outcome.listener().onStdout(
                "total 6844\r\n-rw-------. 1 root root 1257 anaconda-ks.cfg\r\n"
                        + "\u001B]1337;cmd_end;" + nonce + ";1;0\u0007"
                        + "\u001B]1337;prompt;" + nonce + ";1;\u0007"
                        + "[root@localhost ~]# ");

        PtyCommandScheduler.CommandResult result = future.get(5, TimeUnit.SECONDS);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("anaconda-ks.cfg");
        assertThat(result.stdout()).contains("total 6844");
        assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
    }

    @Test
    @DisplayName("CMD_START 帧与输出同批：帧先行打开采集闸门、输出随后采集（t15c 实测 sed 空输出根因）")
    void cmdStartAndOutputInSameBatchStillCollectsOutput() throws Exception {
        ShellIntegrationInstaller.Outcome outcome =
                ShellIntegrationInstaller.install(terminal, "bash", timeouts, delegate, NOOP_SWITCH);
        PtyCommandScheduler scheduler = outcome.scheduler();
        String nonce = outcome.nonce();

        CompletableFuture<PtyCommandScheduler.CommandResult> future =
                scheduler.submitCommand("sed -n '1,4p' '/etc/passwd'");
        assertThat(String.join("", terminal.sentData)).contains("sed -n '1,4p' '/etc/passwd'\n");

        // WHY 同批喂入 CMD_START 帧 + 命令输出：内网实测快速命令（sed）的 DEBUG 钩子帧
        // 与输出常落在同一批数据里，帧在流中位于输出之前。t12d 的「整批文本先于帧」
        // 顺序会把输出先于 CMD_START 帧处理，采集闸门（cmdStartSeen）未开即拒采，
        // 工具拿到 exit=0 空 stdout——修复后必须按流内顺序交替处理帧与文本。
        outcome.listener().onStdout(
                "\u001B]1337;cmd_start;" + nonce + ";1;\u0007"
                        + "root:x:0:0:root:/root:/bin/bash\r\n"
                        + "bin:x:1:1:bin:/bin:/sbin/nologin\r\n"
                        + "daemon:x:2:2:daemon:/sbin:/sbin/nologin\r\n"
                        + "adm:x:3:4:adm:/var/adm:/sbin/nologin\r\n");
        outcome.listener().onStdout("\u001B]1337;cmd_end;" + nonce + ";1;0\u0007");
        outcome.listener().onStdout("\u001B]1337;prompt;" + nonce + ";1;\u0007");

        PtyCommandScheduler.CommandResult result = future.get(5, TimeUnit.SECONDS);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("root:x:0:0");
        assertThat(result.stdout()).contains("bin:x:1:1");
        assertThat(result.stdout()).contains("adm:x:3:4");
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

    @Test
    @DisplayName("reinstall 产出新 Outcome（新调度器 + 新 nonce），与原始安装互不干扰")
    void reinstallProducesNewOutcome() {
        ShellIntegrationInstaller.Outcome original =
                ShellIntegrationInstaller.install(terminal, "bash", timeouts, delegate, NOOP_SWITCH);

        ShellIntegrationInstaller.Outcome reinstalled =
                ShellIntegrationInstaller.reinstall(terminal, "bash", timeouts, delegate, NOOP_SWITCH,
                        PtyCommandScheduler.DEFAULT_NESTED_DETECT_TIMEOUT_MS);

        assertThat(reinstalled.scheduler()).isNotNull();
        assertThat(reinstalled.scheduler()).isNotSameAs(original.scheduler());
        assertThat(reinstalled.nonce()).isNotEmpty();
        assertThat(reinstalled.nonce()).isNotEqualTo(original.nonce());
        // 重装集成代码已写入 PTY 且包含新 nonce
        String allSent = String.join("", terminal.sentData);
        assertThat(allSent).contains(reinstalled.nonce());
    }

    @Test
    @DisplayName("reinstall 监听器：同批帧与输出按 offset 交替处理，命令完整采集（7 参 install 包装路径）")
    void reinstalledListenerCollectsSameBatchFramesAndOutput() throws Exception {
        ShellIntegrationInstaller.Outcome outcome = ShellIntegrationInstaller.reinstall(
                terminal, "bash", timeouts, delegate, NOOP_SWITCH,
                PtyCommandScheduler.DEFAULT_NESTED_DETECT_TIMEOUT_MS);
        PtyCommandScheduler scheduler = outcome.scheduler();
        String nonce = outcome.nonce();

        CompletableFuture<PtyCommandScheduler.CommandResult> future =
                scheduler.submitCommand("sed -n '1,2p' '/etc/passwd'");

        // 批 1：CMD_START 帧在批首（offset=0）后跟输出——「帧前文本」段为空，
        // 采集闸门由帧先行打开、输出随后采集（t15e 实测 sed 空输出根因场景）
        outcome.listener().onStdout("\u001B]1337;cmd_start;" + nonce + ";1;\u0007"
                + "root:x:0:0:root:/root:/bin/bash\r\n");
        // 批 2：输出 + CMD_END + PROMPT 帧 + 尾随提示符文本同批——帧前段先采集、
        // 帧处完成命令、帧后提示符文本落回人工流（t12b 实测 exit=0 空输出根因场景）
        outcome.listener().onStdout("bin:x:1:1:bin:/bin:/sbin/nologin\r\n"
                + "\u001B]1337;cmd_end;" + nonce + ";1;0\u0007"
                + "\u001B]1337;prompt;" + nonce + ";1;\u0007"
                + "[root@localhost ~]# ");

        PtyCommandScheduler.CommandResult result = future.get(5, TimeUnit.SECONDS);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("root:x:0:0");
        assertThat(result.stdout()).contains("bin:x:1:1");
        assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
        // 控制帧被剥离：delegate 收到的每段文本都不含 OSC 帧
        assertThat(delegate.stdout).allSatisfy(text -> assertThat(text).doesNotContain("1337"));
    }

    @Test
    @DisplayName("reinstall 闸门：首个真实帧前吞掉重装回显，帧到达后放行干净文本")
    void reinstalledGateSwallowsEchoThenOpensOnFirstFrame() {
        ShellIntegrationInstaller.Outcome outcome = ShellIntegrationInstaller.reinstall(
                terminal, "bash", timeouts, delegate, NOOP_SWITCH,
                PtyCommandScheduler.DEFAULT_NESTED_DETECT_TIMEOUT_MS);

        outcome.listener().onStdout("[root@localhost ~]# stty -echo\n");
        assertThat(delegate.stdout).as("重装安装回显在首个帧前不得转发").isEmpty();

        outcome.listener().onStdout("\u001B]1337;prompt;" + outcome.nonce() + ";0;\u0007"
                + "[root@localhost ~]# ");
        assertThat(delegate.stdout).containsExactly("[root@localhost ~]# ");
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
