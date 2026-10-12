package com.ananoesis.shell.ssh;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * PtyCommandScheduler 测试（tasks 5.3 + 5.4）。
 *
 * <p>验证输入状态机、串行执行、超时与采集上限、中断后 unknown 转换。</p>
 */
@DisplayName("PtyCommandScheduler")
class PtyCommandSchedulerTest {

    private static final String NONCE = "test-nonce-12345";

    private CapturingTerminalSession terminal;
    private ScheduledExecutorService executor;

    @BeforeEach
    void setUp() {
        terminal = new CapturingTerminalSession();
        executor = Executors.newScheduledThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    /** 创建默认配置的调度器（短超时便于测试）。 */
    private PtyCommandScheduler createScheduler() {
        return new PtyCommandScheduler(terminal, NONCE, executor, 200, 150);
    }

    /** 创建自定义超时配置的调度器。 */
    private PtyCommandScheduler createScheduler(long timeoutMs, long watchMs) {
        return new PtyCommandScheduler(terminal, NONCE, executor, timeoutMs, watchMs);
    }

    /** 创建自定义三项超时配置的调度器（绝对上限可测）。 */
    private PtyCommandScheduler createScheduler(long timeoutMs, long watchMs, long maxAbsoluteMs) {
        return new PtyCommandScheduler(terminal, NONCE, executor, timeoutMs, watchMs, maxAbsoluteMs);
    }

    /** 创建含 busy 自愈时限的调度器（MANUAL_BUSY 死锁修复专项）。 */
    private PtyCommandScheduler createSchedulerWithBusyExpire(long busyExpireMs) {
        return createSchedulerWithBusyExpire(200, busyExpireMs);
    }

    /** 指定命令超时与 busy 自愈时限（自愈类用例需长命令超时，避免 sleep 期间 watchdog 误中断）。 */
    private PtyCommandScheduler createSchedulerWithBusyExpire(long timeoutMs, long busyExpireMs) {
        return new PtyCommandScheduler(terminal, NONCE, executor, timeoutMs, 150,
                PtyCommandScheduler.DEFAULT_MAX_ABSOLUTE_MS, busyExpireMs);
    }

    // ==================================================================
    // 状态机
    // ==================================================================

    @Nested
    @DisplayName("状态机")
    class StateMachine {

        @Test
        @DisplayName("初始状态为 manual_idle（集成成功后）")
        void initialStateIsManualIdle() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
        }

        @Test
        @DisplayName("null 帧与空输出是无副作用防御路径")
        void nullFrameAndEmptyOutputAreNoOps() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.onFrame(null);
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);

            assertThat(scheduler.collectOutput(null)).isEmpty();
            assertThat(scheduler.collectOutput("")).isEmpty();
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
            assertThat(terminal.sentData).isEmpty();
        }

        @Test
        @DisplayName("集成未成功时状态为 unknown")
        void stateIsUnknownWhenIntegrationFailed() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSkipped();

            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.UNKNOWN);
        }

        @Test
        @DisplayName("提交命令后状态变为 agent_owned")
        void submitCommandChangesStateToAgentOwned() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.submitCommand("echo hello");

            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.AGENT_OWNED);
        }

        @Test
        @DisplayName("命令完成后状态回到 manual_idle")
        void commandCompleteReturnsToManualIdle() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.submitCommand("echo hello");
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "cmd1", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "cmd1", ""));

            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
        }

        @Test
        @DisplayName("人工 cd 的 cwd 帧（无在飞命令）也更新会话级 sessionCwd：Agent 提示词需要知道用户切到了哪里")
        void manualCwdFrameUpdatesSessionCwd() {
            // 浏览器验收发现：用户 cd /tmp/acceptance 后问 Agent“列出当前目录”，
            // 模型不知道 cwd 只能猜 /。旧实现 handleCwd 只在 currentCommand != null
            // 时记录（agent 命令结果），人工 cd 的 cmdId=0 帧被丢弃
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();
            assertThat(scheduler.sessionCwd()).isNull();
            assertThat(scheduler.currentWorkingDirectory()).isNull();

            scheduler.onFrame(new ShellFrame(ShellFrameType.CWD, NONCE, "0", "/tmp/acceptance"));

            assertThat(scheduler.sessionCwd()).isEqualTo("/tmp/acceptance");

            // 后续 agent 命令的 cwd 帧（cmdId 非 0）同样更新，不互相覆盖出旧值
            scheduler.submitCommand("pwd");
            scheduler.onFrame(new ShellFrame(ShellFrameType.CWD, NONCE, "c1", "/var/log"));
            assertThat(scheduler.sessionCwd()).isEqualTo("/var/log");
            assertThat(scheduler.currentWorkingDirectory()).isEqualTo("/var/log");
        }

        @Test
        @DisplayName("agent_owned 时提交命令被排队")
        void submitQueuedWhenAgentOwned() throws Exception {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.submitCommand("first");
            CompletableFuture<PtyCommandScheduler.CommandResult> second =
                    scheduler.submitCommand("second");

            // 第二条被排队，尚未完成
            assertThat(second.isDone()).isFalse();
            assertThat(scheduler.queuedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("stopping 状态时提交命令被拒绝")
        void submitRejectedWhenStopping() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();
            scheduler.onStopping();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("echo hello");

            assertThat(future).isCompletedExceptionally();
        }

        @Test
        @DisplayName("unknown 状态时提交命令被拒绝")
        void submitRejectedWhenUnknown() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSkipped(); // → unknown

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("echo hello");

            assertThat(future).isCompletedExceptionally();
        }

        @Test
        @DisplayName("agent_owned 时的人工输入事件不改变状态")
        void manualBusyIgnoredWhenAgentOwned() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();
            scheduler.submitCommand("echo hello"); // → agent_owned

            scheduler.onManualBusy();

            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.AGENT_OWNED);
        }

        @Test
        @DisplayName("非 busy 状态下 onManualIdle 无副作用")
        void manualIdleNoopWhenNotBusy() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.onManualIdle();

            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
        }

        @Test
        @DisplayName("busy 自愈到期但已非 busy：无副作用早退")
        void busyExpireNoopWhenNotBusy() throws Exception {
            PtyCommandScheduler scheduler = createSchedulerWithBusyExpire(100);
            scheduler.onIntegrationSuccess();

            // 未标 busy，自愈任务到期后必须无副作用（不误发 Ctrl-C）
            Thread.sleep(200);

            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
            assertThat(terminal.sentData).doesNotContain("\u0003");
        }

        @Test
        @DisplayName("无在飞命令时 interruptCurrent 无副作用")
        void interruptCurrentNoopWithoutCommand() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.interruptCurrent();

            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
            assertThat(terminal.sentData).doesNotContain("\u0003");
        }

        @Test
        @DisplayName("onStopping 在命令在飞且队列非空时：发 Ctrl-C 并清空队列")
        void stoppingInterruptsInflightAndFailsQueued() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();
            scheduler.submitCommand("first");
            CompletableFuture<PtyCommandScheduler.CommandResult> queued =
                    scheduler.submitCommand("second");

            scheduler.onStopping();

            assertThat(terminal.sentData).contains("\u0003");
            assertThat(queued).isCompletedExceptionally();
            assertThat(scheduler.queuedCount()).isZero();
        }
    }

    // ==================================================================
    // 串行执行
    // ==================================================================

    @Nested
    @DisplayName("串行执行")
    class SerialExecution {

        @Test
        @DisplayName("命令写入 PTY（以换行结尾）")
        void commandWrittenToPty() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.submitCommand("echo hello");

            assertThat(terminal.sentData).contains("echo hello\n");
        }

        @Test
        @DisplayName("只有收到完成帧后才允许下一条命令")
        void nextCommandOnlyAfterCompletionFrame() throws Exception {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.submitCommand("first");
            scheduler.submitCommand("second");

            // 此时只应发送第一条
            assertThat(terminal.sentData).contains("first\n");
            assertThat(terminal.sentData).doesNotContain("second\n");

            // 完成第一条
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "c1", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c1", ""));

            // 现在第二条应该被发送
            assertThat(terminal.sentData).contains("second\n");
        }

        @Test
        @DisplayName("cmd_start 帧前到达的提示符文本不采集进命令 stdout（wc 总行数提示符污染回归）")
        void promptTextBeforeCmdStartIsNotCollected() throws Exception {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("wc -l '/etc/passwd'");

            // 内网实测：上一条命令完成（PROMPT 帧）后调度器立即派发本命令，而上一条
            // 命令的完成提示符文本随后才到达——它必须透传给用户终端，但绝不能采集进
            // 本命令的 stdout（旧行为让 wc 的 stdout 首行变成提示符，总行数解析失败）
            assertThat(scheduler.collectOutput("[root@localhost ~]# "))
                    .as("提示符文本仍须原样透传给用户终端").isEqualTo("[root@localhost ~]# ");

            // cmd_start 帧之后到达的回显与结果才属于本命令
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_START, NONCE, "c1", "wc -l '/etc/passwd'"));
            scheduler.collectOutput("wc -l '/etc/passwd'\r\n19 /etc/passwd\r\n");
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "c1", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c1", ""));

            PtyCommandScheduler.CommandResult result = future.get(2, TimeUnit.SECONDS);
            assertThat(result.stdout())
                    .as("cmd_start 帧前到达的提示符文本不得进入命令 stdout")
                    .doesNotContain("[root@localhost ~]#");
            assertThat(result.stdout())
                    .as("cmd_start 帧后的回显与结果正常采集")
                    .contains("wc -l '/etc/passwd'")
                    .contains("19 /etc/passwd");
        }

        @Test
        @DisplayName("串行执行三条命令")
        void serialExecutionOfThreeCommands() throws Exception {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            CompletableFuture<PtyCommandScheduler.CommandResult> f1 = scheduler.submitCommand("a");
            CompletableFuture<PtyCommandScheduler.CommandResult> f2 = scheduler.submitCommand("b");
            CompletableFuture<PtyCommandScheduler.CommandResult> f3 = scheduler.submitCommand("c");

            // 只发送了第一条
            assertThat(terminal.sentData).contains("a\n");
            assertThat(terminal.sentData).doesNotContain("b\n");

            // 完成第一条
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "c1", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c1", ""));

            // 第二条被发送
            assertThat(terminal.sentData).contains("b\n");

            // 完成第二条
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "c2", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c2", ""));

            // 第三条被发送
            assertThat(terminal.sentData).contains("c\n");
        }

        @Test
        @DisplayName("完成帧携带退出码被正确记录")
        void exitCodeRecordedFromCompletionFrame() throws Exception {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("exit 42");

            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "c1", "42"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c1", ""));

            PtyCommandScheduler.CommandResult result = future.get(1, TimeUnit.SECONDS);
            assertThat(result.exitCode()).isEqualTo(42);
        }

        @Test
        @DisplayName("CWD 帧被记录")
        void cwdFrameRecorded() throws Exception {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("cd /tmp");

            scheduler.onFrame(new ShellFrame(ShellFrameType.CWD, NONCE, "c1", "/tmp"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "c1", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c1", ""));

            PtyCommandScheduler.CommandResult result = future.get(1, TimeUnit.SECONDS);
            assertThat(result.workingDirectory()).isEqualTo("/tmp");
        }
    }

    // ==================================================================
    // 超时与采集（task 5.4）
    // ==================================================================

    @Nested
    @DisplayName("超时与采集")
    class TimeoutAndCollection {

        @Test
        @DisplayName("超时后发送 Ctrl-C 中断")
        void timeoutSendsCtrlC() throws Exception {
            PtyCommandScheduler scheduler = createScheduler(200, 150);
            scheduler.onIntegrationSuccess();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("sleep 999");

            // 等待超时触发
            Thread.sleep(600);

            // Ctrl-C (0x03) 应被发送
            assertThat(terminal.sentData).contains("\u0003");
        }

        @Test
        @DisplayName("采集超过 64 KiB 上限后丢弃超额内容但继续排空")
        void collectionOver64KiBDiscardsExcess() throws Exception {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("yes");
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_START, NONCE, "c1", "yes"));

            // 采集大量数据（超过 64 KiB = 65536 字节）
            StringBuilder largeOutput = new StringBuilder();
            for (int i = 0; i < 7000; i++) {
                largeOutput.append("0123456789"); // 每轮 10 字节，共 70000 字节
            }
            scheduler.collectOutput(largeOutput.toString());

            // 完成命令
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "c1", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c1", ""));

            PtyCommandScheduler.CommandResult result = future.get(2, TimeUnit.SECONDS);
            // 采集应被截断到 64 KiB
            assertThat(result.stdout().length()).isLessThanOrEqualTo(65536);
            assertThat(result.truncated()).isTrue();
        }

        @Test
        @DisplayName("未超限时结果不包含超时标记")
        void normalCompletionNoTimeout() throws Exception {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("echo ok");
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_START, NONCE, "c1", "echo ok"));

            scheduler.collectOutput("ok\n");
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "c1", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c1", ""));

            PtyCommandScheduler.CommandResult result = future.get(2, TimeUnit.SECONDS);
            assertThat(result.timedOut()).isFalse();
            assertThat(result.stdout()).isEqualTo("ok\n");
        }

        @Test
        @DisplayName("持续输出的命令不被空闲超时打断；停止活动后才发 Ctrl-C（BUG-A）")
        void activeOutputExtendsTimeout() throws Exception {
            // 安装 JDK 这类分钟级命令持续刷进度条，旧实现的 60s 绝对超时
            // 直接发 Ctrl-C 杀掉正在正常工作的安装进程——必须改为空闲超时
            // WHY 超时 500ms / 活动间隔 100ms = 5:1 裕量：旧 2:1 比值在 Windows
            // 线程调度抖动下会误触发（scheduler 线程在两次 collectOutput 间隙判定空闲 >200ms）
            PtyCommandScheduler scheduler = createScheduler(500, 150);
            scheduler.onIntegrationSuccess();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("yum install -y java-1.8.0-openjdk");
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_START, NONCE, "c1", "yum install -y java-1.8.0-openjdk"));

            // 模拟 600ms 持续输出（活动间隔 100ms，裕量为空闲阈值 500ms 的 1/5）
            long deadline = System.currentTimeMillis() + 600;
            while (System.currentTimeMillis() < deadline) {
                scheduler.collectOutput("Downloading...\n");
                Thread.sleep(100);
            }
            // 活动期内不得中断：Ctrl-C 未发、命令仍在飞
            assertThat(terminal.sentData).as("持续输出的命令不应被空闲超时打断").doesNotContain("\u0003");
            assertThat(future.isDone()).as("命令应仍在执行").isFalse();

            // 停止一切输出后，空闲达到 500ms 阈值才发 Ctrl-C（等 1200ms 留足裕量）
            Thread.sleep(1200);
            assertThat(terminal.sentData).as("停止活动后空闲超时应中断").contains("\u0003");
        }

        @Test
        @DisplayName("持续活动但超过绝对上限仍被中断（防跑飞兜底）")
        void absoluteCapInterruptsEvenIfActive() throws Exception {
            // 空闲超时会让真正卡死的命令无限占用输入权，故叠加绝对上限：
            // timeout=200/maxAbs=400，每 50ms 输出一次（idle 永不超时），
            // 但 elapsed 过 400ms 必须被中断
            PtyCommandScheduler scheduler = createScheduler(200, 150, 400);
            scheduler.onIntegrationSuccess();

            scheduler.submitCommand("yes | tee /dev/null");
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_START, NONCE, "c1", "yes | tee /dev/null"));

            long deadline = System.currentTimeMillis() + 800;
            while (System.currentTimeMillis() < deadline) {
                scheduler.collectOutput("y\n");
                Thread.sleep(50);
            }
            assertThat(terminal.sentData).as("超过绝对上限应中断，即使仍在输出").contains("\u0003");
        }

        @Test
        @DisplayName("PTY 等待上限覆盖绝对上限（runner/tools 不得提前弃等回落 exec）")
        void ptyWaitCeilingCoversAbsoluteCap() {
            // BUG-A/B 交叉点：调度器自己会在绝对上限处中断并完成 future，
            // 等待方（ApprovedCommandRunner/AgentTools）的 get 上限必须更晚，
            // 否则会超时回落 exec 把同一命令重跑一遍
            assertThat(PtyCommandScheduler.ptyWaitCeilingSeconds(60))
                    .isGreaterThanOrEqualTo(PtyCommandScheduler.DEFAULT_MAX_ABSOLUTE_MS / 1000);
            // settings 时限大于上限时仍以 settings 为准（用户显式配置优先）
            assertThat(PtyCommandScheduler.ptyWaitCeilingSeconds(7200))
                    .isGreaterThanOrEqualTo(7200);
        }
    }

    // ==================================================================
    // 在飞命令主动中断（BUG-B 配套：用户打断回合时同步 Ctrl-C 远端命令）
    // ==================================================================

    @Nested
    @DisplayName("interruptCurrent 主动中断")
    class InterruptCurrent {

        @Test
        @DisplayName("发 Ctrl-C 并进观察窗口；PROMPT 证据后回 manual_idle 且带超时标记")
        void interruptThenPromptEvidenceRecoversToManualIdle() throws Exception {
            PtyCommandScheduler scheduler = createScheduler(10_000, 300);
            scheduler.onIntegrationSuccess();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("sleep 999");

            scheduler.interruptCurrent();

            assertThat(terminal.sentData).as("应主动向远端发 Ctrl-C").contains("\u0003");
            assertThat(future.isDone()).as("观察窗口内等待完成证据，不立即完成").isFalse();

            // 远端回到提示符（无 CMD_END，属中断后路径）→ 完成且回 manual_idle
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c1", ""));

            PtyCommandScheduler.CommandResult result = future.get(1, TimeUnit.SECONDS);
            assertThat(result.timedOut()).as("主动中断的回合应带超时/中断标记").isTrue();
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
        }

        @Test
        @DisplayName("无在飞命令时 interruptCurrent 无副作用")
        void interruptCurrentNoopWithoutInFlight() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.interruptCurrent();

            assertThat(terminal.sentData).doesNotContain("\u0003");
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
        }
    }

    // ==================================================================
    // 中断后 unknown 转换（task 5.4）
    // ==================================================================

    @Nested
    @DisplayName("中断后 unknown 转换")
    class InterruptToUnknown {

        @Test
        @DisplayName("中断后 3 秒无完成证据转为 unknown")
        void interruptThenNoCompletionBecomesUnknown() throws Exception {
            // 使用短超时以加速测试
            PtyCommandScheduler scheduler = createScheduler(100, 150);
            scheduler.onIntegrationSuccess();

            scheduler.submitCommand("sleep 999");

            // 等待超时 + 观察窗口
            Thread.sleep(600);

            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.UNKNOWN);
        }

        @Test
        @DisplayName("中断后收到完成帧则正常完成而非 unknown")
        void interruptThenCompletionCompletesNormally() throws Exception {
            PtyCommandScheduler scheduler = createScheduler(100, 500);
            scheduler.onIntegrationSuccess();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("slow_cmd");

            // 等待超时触发（发送 Ctrl-C）
            Thread.sleep(250);

            // 在观察窗口内发送完成帧
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "c1", "130"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "c1", ""));

            // 应正常完成而非 unknown
            PtyCommandScheduler.CommandResult result = future.get(1, TimeUnit.SECONDS);
            assertThat(result.exitCode()).isEqualTo(130);
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
        }
    }

    // ==================================================================
    // 人工输入干预
    // ==================================================================

    @Nested
    @DisplayName("人工输入干预")
    class ManualIntervention {

        @Test
        @DisplayName("onManualBusy 期间 Agent 提交排队而非拒绝（旧拒绝语义是 MANUAL_BUSY 死锁闭环的一环）")
        void manualBusyQueuesAgentSubmit() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.onManualBusy();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("echo hello");
            // 新语义：不被拒、也不立即发送（人工忙碌不得注入），排队等待自愈/恢复派发
            assertThat(future).isNotCompletedExceptionally();
            assertThat(future).isNotDone();
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_BUSY);
        }

        @Test
        @DisplayName("onManualIdle 恢复 Agent 提交能力")
        void manualIdleRestoresAgentSubmit() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();

            scheduler.onManualBusy();
            scheduler.onManualIdle();

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("echo hello");
            // 应成功提交
            assertThat(future.isDone()).isFalse(); // 未完成但也没被拒绝
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.AGENT_OWNED);
        }

        @Test
        @DisplayName("人工活动后的空 PROMPT 帧自动恢复 idle（接线场景：无此路径则永久 busy）")
        void promptFrameAloneRecoversIdleAfterManualBusy() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();
            // 用户在人工终端里敲了一行（WS input 钩子标 busy）
            scheduler.onManualBusy();

            // 人工命令执行完毕，bash 钩子回 PROMPT 帧——此时没有 Agent 命令在飞
            // （currentCommand 为 null），旧实现直接 return，状态永卡在 busy，
            // 后续获准命令全被拒；接线后 PROMPT 即"空提示符"的唯一证据
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "manual", ""));

            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
            // 恢复后 Agent 可重新领取输入权
            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("pwd");
            assertThat(future).isNotCompletedExceptionally();
        }
    }

    // ==================================================================
    // MANUAL_BUSY 自愈（停止后卡死 bug）
    // ==================================================================

    @Nested
    @DisplayName("MANUAL_BUSY 自愈过期")
    class ManualBusySelfHeal {

        @Test
        @DisplayName("无后续按键时 busy 到期自动回到 idle（旧设计唯一恢复证据是 PROMPT 帧，半行输入后用户停手即永久卡死）")
        void busySelfHealsToIdleAfterQuietPeriod() throws Exception {
            PtyCommandScheduler scheduler = createSchedulerWithBusyExpire(250);
            scheduler.onIntegrationSuccess();

            scheduler.onManualBusy();
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_BUSY);

            // 静默超过 busyExpire → 自愈回 idle
            Thread.sleep(500);
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
        }

        @Test
        @DisplayName("busy 期间提交的命令排队而非拒绝；自愈后先 Ctrl-C 清疑似半行再派发（参照现行 ssh 客户端注入前清行）")
        void submitWhileBusyQueuesAndDispatchesAfterSelfHeal() throws Exception {
            // 命令超时 5s：自愈后的 sleep + 断言窗口内 watchdog 不会 interrupt 在飞的 pwd
            PtyCommandScheduler scheduler = createSchedulerWithBusyExpire(5000, 250);
            scheduler.onIntegrationSuccess();

            scheduler.onManualBusy();
            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("pwd");
            // 排队：未被拒绝、也未立即发送（人工忙碌不得注入）
            assertThat(future).isNotCompletedExceptionally();
            assertThat(future).isNotDone();
            assertThat(terminal.sentData).noneMatch(s -> s.startsWith("pwd"));

            Thread.sleep(500);
            // 自愈后：先 Ctrl-C 清行，再派发排队命令（须在 200ms 命令超时前断言，
            // 否则 watchdog 会中断在飞命令补发 Ctrl-C）
            assertThat(terminal.sentData).containsExactly("\u0003", "pwd\n");
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.AGENT_OWNED);

            // 命令正常完成，避免悬挂的在飞命令被超时中断干扰断言
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "1", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "1", ""));
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
        }

        @Test
        @DisplayName("busy 期间用户前台命令在跑（CMD_START 证据）不自愈不清行，恢复仍靠 PROMPT 链路")
        void runningForegroundCommandIsNotSelfHealed() throws Exception {
            PtyCommandScheduler scheduler = createSchedulerWithBusyExpire(200);
            scheduler.onIntegrationSuccess();

            scheduler.onManualBusy();
            // 用户按了回车，命令开始执行（trap DEBUG 上报 CMD_START）——
            // 此时若自愈发 Ctrl-C 会杀死用户的前台/全屏程序
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_START, NONCE, "manual", "sleep 60"));

            Thread.sleep(500);
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_BUSY);
            assertThat(terminal.sentData).doesNotContain("\u0003");

            // 前台命令结束，PROMPT 帧恢复 idle（既有路径不受影响）
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "manual", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "manual", ""));
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
        }

        @Test
        @DisplayName("PROMPT 正常恢复时取消自愈任务，不再补发多余 Ctrl-C")
        void promptRecoveryCancelsSelfHeal() throws Exception {
            PtyCommandScheduler scheduler = createSchedulerWithBusyExpire(300);
            scheduler.onIntegrationSuccess();

            // 第一次 busy：调度旧自愈任务（t≈10 到期于 t≈310）
            scheduler.onManualBusy();
            // PROMPT 恢复——必须取消旧任务，否则其在第二次 busy 窗口内到期会误清行
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "manual", ""));
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
            terminal.sentData.clear();

            // 制造新旧窗口差：旧任务未取消会在 t≈310 触发；新任务到期于 t≈560
            Thread.sleep(250);
            scheduler.onManualBusy();
            Thread.sleep(250);
            // t≈510：若旧任务未取消，已在 t≈310（此时处于第二次 busy）误发 Ctrl-C 并提前回 idle
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_BUSY);
            assertThat(terminal.sentData).doesNotContain("\u0003");
        }

        @Test
        @DisplayName("连续按键刷新自愈计时：过期时刻从最后一次按键起算")
        void continuousKeystrokesPushOutExpiry() throws Exception {
            PtyCommandScheduler scheduler = createSchedulerWithBusyExpire(300);
            scheduler.onIntegrationSuccess();

            scheduler.onManualBusy();
            Thread.sleep(150);
            // 用户仍在打字（input 帧持续到达）→ 重新计时
            scheduler.onManualBusy();
            Thread.sleep(200);
            // 距第二次按键仅 200ms < 300ms，不得提前自愈
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_BUSY);

            Thread.sleep(250);
            assertThat(scheduler.state()).isEqualTo(PtyCommandScheduler.State.MANUAL_IDLE);
        }
    }

    // ==================================================================
    // 嵌套 Shell 检测
    // ==================================================================

    @Nested
    @DisplayName("嵌套 Shell 检测")
    class NestedShellDetection {

        /** 创建含嵌套检测超时的调度器（短超时便于测试）。 */
        private PtyCommandScheduler createSchedulerWithNestedDetect(long nestedDetectMs) {
            return new PtyCommandScheduler(terminal, NONCE, executor, 200, 150,
                    PtyCommandScheduler.DEFAULT_MAX_ABSOLUTE_MS,
                    PtyCommandScheduler.DEFAULT_BUSY_EXPIRE_MS,
                    nestedDetectMs);
        }

        @Test
        @DisplayName("帧超时后发送探测命令，探测标记回显触发回调（嵌套 Shell 检测完整链路）")
        void nestedShellDetectedAfterFrameTimeout() throws Exception {
            // WHY 500ms 嵌套检测超时：需保证探测发送后有足够的窗口在下一个探测定时到期前
            //      注入 collectOutput，100ms 在 Windows 线程调度下竞态必败
            PtyCommandScheduler scheduler = createSchedulerWithNestedDetect(500);
            AtomicBoolean callbackInvoked = new AtomicBoolean(false);
            scheduler.setNestedShellCallback(() -> callbackInvoked.set(true));
            scheduler.onIntegrationSuccess();

            // 不发送任何帧，等待帧超时 → 探测命令写入 PTY
            Thread.sleep(700);

            // 探测命令 echo _ANANOESIS_NESTED_PROBE_$$ 已发送
            assertThat(terminal.sentData).anyMatch(
                    s -> s.contains(PtyCommandScheduler.NESTED_PROBE_MARKER));
            assertThat(callbackInvoked.get()).as("探测标记回显前应未触发回调").isFalse();

            // 模拟探测标记回显：健康与嵌套 shell 都会回显——回显本身不是嵌套证据
            scheduler.collectOutput("_ANANOESIS_NESTED_PROBE_12345");

            // 新语义：回显只记录，不立即确认（旧逻辑「回显即确认」是内网死循环根因：
            // 健康 shell 空闲同样回显探针 → 误判嵌套 → 重安装 → 新调度器再探测 → 循环）
            assertThat(callbackInvoked.get()).as("仅有回显不得立即确认嵌套").isFalse();

            // 探测窗口到期仍无任何集成帧 → 回显+无帧 = 嵌套确认 → 回调
            // （WHY 900ms：回显刷新帧活动时刻会顺延一轮裁决，需跨两个窗口）
            Thread.sleep(900);
            assertThat(callbackInvoked.get()).as("回显+窗口内无帧必须触发重安装回调").isTrue();
        }

        @Test
        @DisplayName("健康 Shell：探针回显后 PROMPT 帧到达，撤销探测且不触发重安装")
        void healthyShellPromptFrameCancelsProbe() throws Exception {
            PtyCommandScheduler scheduler = createSchedulerWithNestedDetect(500);
            AtomicBoolean callbackInvoked = new AtomicBoolean(false);
            scheduler.setNestedShellCallback(() -> callbackInvoked.set(true));
            scheduler.onIntegrationSuccess();

            Thread.sleep(700); // 探测已发送
            assertThat(terminal.sentData).anyMatch(
                    s -> s.contains(PtyCommandScheduler.NESTED_PROBE_MARKER));

            // 健康 shell 的完整响应：探针回显 + PROMPT 帧（钩子存活证据）
            scheduler.collectOutput("_ANANOESIS_NESTED_PROBE_12345");
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "0", ""));

            // 帧到达即重置探测并顺延：健康 shell 永远不该被误判（内网死循环回归）
            Thread.sleep(1100);
            assertThat(callbackInvoked.get())
                    .as("回显+PROMPT 帧=钩子存活，不得触发重安装").isFalse();
        }

        @Test
        @DisplayName("探测窗口内一切文本被抑制（探针回显、echo 输出、提示符），裁决后恢复透传")
        void probeWindowSuppressesAllOutputUntilAdjudication() throws Exception {
            PtyCommandScheduler scheduler = createSchedulerWithNestedDetect(500);
            AtomicBoolean callbackInvoked = new AtomicBoolean(false);
            scheduler.setNestedShellCallback(() -> callbackInvoked.set(true));
            scheduler.onIntegrationSuccess();

            Thread.sleep(700); // 进入 PROBE_SENT
            // WHY 整块抑制：探针命令回显、echo 输出行、bash 重新打印的提示符
            // 都不在 OSC 帧协议内、解码器剥不掉，只有窗口内全抑制才不泄漏到
            // 用户终端（内网实测 4 行探针 + 重复提示符均源于只抑制含标记块）
            assertThat(scheduler.collectOutput("_ANANOESIS_NESTED_PROBE_12345")).isEmpty();
            assertThat(scheduler.collectOutput("普通输出\n")).as("窗口内非探针文本同样抑制").isEmpty();

            // WHY 900ms：两次 collectOutput 刷新了帧活动时刻，裁决顺延一轮，
            // 需跨两个窗口（回显+无帧 → 嵌套确认 → FALLBACK）
            Thread.sleep(900);
            assertThat(callbackInvoked.get()).as("回显+窗口内无帧必须触发重安装回调").isTrue();

            // 裁决处已清理抑制：用户后续输出恢复透传
            assertThat(scheduler.collectOutput("裁决后的用户输出\n")).isEqualTo("裁决后的用户输出\n");
        }

        @Test
        @DisplayName("bash 5 系竞态回归：CMD_START 帧先于输出行到达时，回显行仍被抑制（探针泄漏根因）")
        void frameBeforeOutputLineStillSuppressesProbeEcho() throws Exception {
            PtyCommandScheduler scheduler = createSchedulerWithNestedDetect(500);
            AtomicBoolean callbackInvoked = new AtomicBoolean(false);
            scheduler.setNestedShellCallback(() -> callbackInvoked.set(true));
            scheduler.onIntegrationSuccess();

            Thread.sleep(700); // 进入 PROBE_SENT

            // bash 5 系（openEuler）DEBUG trap 在命令执行前发 cmd_start 帧：
            // onFrame 立即把 nestedState 重置回 IDLE，echo 输出行随后才到达。
            // 旧实现以 nestedState==PROBE_SENT 为抑制条件，输出行在此竞态下
            // 漏到用户终端（内网实测探针泄漏）。
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_START, NONCE, "", ""));
            assertThat(scheduler.collectOutput("_ANANOESIS_NESTED_PROBE_12345\n"))
                    .as("帧已重置状态后，探针回显输出行仍必须被抑制").isEmpty();

            // 帧到达即撤销探测：不触发重安装
            Thread.sleep(1100);
            assertThat(callbackInvoked.get()).as("帧到达说明钩子存活，不得触发重安装").isFalse();
        }

        @Test
        @DisplayName("重复提示符回归：PROMPT 帧后的提示符文本被吞掉，随后恢复透传")
        void promptTextAfterPromptFrameIsSwallowedThenPassthrough() throws Exception {
            PtyCommandScheduler scheduler = createSchedulerWithNestedDetect(500);
            AtomicBoolean callbackInvoked = new AtomicBoolean(false);
            scheduler.setNestedShellCallback(() -> callbackInvoked.set(true));
            scheduler.onIntegrationSuccess();

            Thread.sleep(700); // 进入 PROBE_SENT

            // 健康 shell 的完整响应帧序：回显行 → PROMPT 帧 → 提示符文本
            //（CentOS 7 实测：提示符文本紧跟 PROMPT 帧到达，帧与文本可能分块）
            assertThat(scheduler.collectOutput("_ANANOESIS_NESTED_PROBE_12345")).isEmpty();
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "0", ""));

            // 吞尾窗口内（PROMPT 帧后 300ms）：提示符文本必须被吞掉——
            // 旧实现透传导致用户终端每个探测周期多出一个空提示符行
            //（内网实测「[root@localhost ~]# [root@localhost ~]#」重复提示符）
            assertThat(scheduler.collectOutput("[root@localhost ~]# "))
                    .as("探测产生的提示符文本必须被吞掉").isEmpty();

            // WHY 350ms：越过 300ms 吞尾窗口但仍在下一探测周期（PROMPT 帧后
            // 500ms）之前——窗口过后用户输出恢复透传
            Thread.sleep(350);
            assertThat(scheduler.collectOutput("正常输出\n")).isEqualTo("正常输出\n");

            // PROMPT 帧说明钩子存活：不触发重安装
            Thread.sleep(1100);
            assertThat(callbackInvoked.get()).as("帧到达说明钩子存活，不得触发重安装").isFalse();
        }

        @Test
        @DisplayName("无回显也无帧：裁决重置 IDLE 并清理抑制，恢复透传（用户离开场景）")
        void noEchoNoFrameResetsToIdleAndRestoresPassthrough() throws Exception {
            PtyCommandScheduler scheduler = createSchedulerWithNestedDetect(500);
            AtomicBoolean callbackInvoked = new AtomicBoolean(false);
            scheduler.setNestedShellCallback(() -> callbackInvoked.set(true));
            scheduler.onIntegrationSuccess();

            Thread.sleep(700); // 进入 PROBE_SENT
            // 不再注入任何回显/帧，等待裁决：无回显 → 重置 IDLE（用户离开场景）
            Thread.sleep(700);

            assertThat(callbackInvoked.get()).as("无回显不得确认嵌套").isFalse();
            // 裁决处已清理抑制：用户回来后输出恢复透传
            assertThat(scheduler.collectOutput("用户回来后的输出\n")).isEqualTo("用户回来后的输出\n");
        }

        @Test
        @DisplayName("重装失败后 submitCommand 拒绝并走 exec 回落（嵌套降级闭环）")
        void nestedShellFallbackToExecAfterFailedReinstall() throws Exception {
            PtyCommandScheduler scheduler = createSchedulerWithNestedDetect(500);
            AtomicBoolean callbackInvoked = new AtomicBoolean(false);
            scheduler.setNestedShellCallback(() -> callbackInvoked.set(true));
            scheduler.onIntegrationSuccess();

            // 完成探测链路：超时→探测→回显记录→窗口到期裁决→回调（重安装）
            Thread.sleep(700);
            scheduler.collectOutput("_ANANOESIS_NESTED_PROBE_12345");
            // 新语义：裁决在探测窗口到期（回显刷新帧活动时刻，跨两个窗口）
            Thread.sleep(900);
            assertThat(callbackInvoked.get()).isTrue();

            // 模拟重安装后仍无帧（嵌套 bash 中新集成代码未生效），
            // 再经过多次探测周期（每周期 500ms）后降级为 FALLBACK
            Thread.sleep(4000);
            assertThat(scheduler.isNestedFallback()).as("多次无帧应降级为 exec 通道模式").isTrue();
            assertThat(scheduler.isNestedShell()).as("降级后应告知上层已进入嵌套 Shell 环境").isTrue();

            // submitCommand 被拒绝（调用方走 exec 回落路径）
            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("echo hello");
            assertThat(future).isCompletedExceptionally();
        }

        @Test
        @DisplayName("探测抑制窗口内提交命令：排队至抑制结束派发，输出完整采集（list_dir 空输出回归）")
        void commandSubmittedDuringProbeSuppressionIsQueuedAndCollected() throws Exception {
            // WHY 命令超时 3s：硬截止（1500ms）触发派发的时刻由探测周期决定，
            // 测试线程醒来后需余量发输出与完成帧，短超时会被空闲中断抢先（实测 200ms 超时在 sleep 内触发）
            PtyCommandScheduler scheduler = new PtyCommandScheduler(terminal, NONCE, executor, 3000, 150,
                    PtyCommandScheduler.DEFAULT_MAX_ABSOLUTE_MS,
                    PtyCommandScheduler.DEFAULT_BUSY_EXPIRE_MS, 500);
            AtomicBoolean callbackInvoked = new AtomicBoolean(false);
            scheduler.setNestedShellCallback(() -> callbackInvoked.set(true));
            scheduler.onIntegrationSuccess();

            Thread.sleep(700); // 探测已发送（probeSuppressActive=true）
            // 健康 shell 的响应：回显 + PROMPT 帧（吞尾窗口开始）
            scheduler.collectOutput("_ANANOESIS_NESTED_PROBE_12345");
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "0", ""));

            // WHY 本用例锚定内网实测：list_dir 的 ls 命令恰在抑制窗口内派发，
            // 其回显与输出被 collectOutput 的抑制分支整块吞掉且不采集，
            // 结果 exit=0 但 stdout 空 → 模型因空输出幻觉文件列表
            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("echo hello");
            // 排队语义：抑制未结束时命令不得写入 PTY，否则探针残留会混入采集
            assertThat(terminal.sentData)
                    .as("抑制期间命令不得直接派发").doesNotContain("echo hello\n");

            // 轮询等待硬截止（PROBE_SUPPRESS_MAX_MS=1500ms）派发排队命令
            long deadline = System.currentTimeMillis() + 3000;
            while (!terminal.sentData.contains("echo hello\n")
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertThat(terminal.sentData)
                    .as("抑制结束后命令应自动派发").contains("echo hello\n");

            // 命令输出与完成帧序列
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_START, NONCE, "1", "echo hello"));
            scheduler.collectOutput("hello\n");
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "1", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "1", ""));

            PtyCommandScheduler.CommandResult result = future.get(3, TimeUnit.SECONDS);
            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout())
                    .as("命令输出不得被探测抑制吞掉").contains("hello");
        }

        @Test
        @DisplayName("吞尾窗口结束后，抑制期间排队的命令自动派发且输出完整采集")
        void queuedCommandDispatchedAfterTailWindow() throws Exception {
            // WHY 命令超时 3s：恢复时刻由吞尾窗口（300ms）决定，需余量发输出与完成帧
            PtyCommandScheduler scheduler = new PtyCommandScheduler(terminal, NONCE, executor, 3000, 150,
                    PtyCommandScheduler.DEFAULT_MAX_ABSOLUTE_MS,
                    PtyCommandScheduler.DEFAULT_BUSY_EXPIRE_MS, 500);
            AtomicBoolean callbackInvoked = new AtomicBoolean(false);
            scheduler.setNestedShellCallback(() -> callbackInvoked.set(true));
            scheduler.onIntegrationSuccess();

            Thread.sleep(700); // 探测已发送
            scheduler.collectOutput("_ANANOESIS_NESTED_PROBE_12345");
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "0", ""));

            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("echo hello");
            assertThat(terminal.sentData).doesNotContain("echo hello\n");

            // 越过 300ms 吞尾窗口后新文本块到达：恢复透传并派发排队命令
            Thread.sleep(350);
            assertThat(scheduler.collectOutput("窗口外输出\n"))
                    .as("恢复点的文本块必须原样透传，不得混入命令采集").isEqualTo("窗口外输出\n");
            assertThat(terminal.sentData)
                    .as("吞尾窗口结束后排队命令应已派发").contains("echo hello\n");

            // 命令输出与完成帧序列
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_START, NONCE, "1", "echo hello"));
            scheduler.collectOutput("hello\n");
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "1", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "1", ""));

            PtyCommandScheduler.CommandResult result = future.get(3, TimeUnit.SECONDS);
            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout()).contains("hello");
        }

        @Test
        @DisplayName("抑制期间排队的命令在嵌套确认降级时以异常完成（走 exec 回落）")
        void queuedCommandFailsFastWhenProbeAdjudicatesFallback() throws Exception {
            PtyCommandScheduler scheduler = createSchedulerWithNestedDetect(500);
            AtomicBoolean callbackInvoked = new AtomicBoolean(false);
            scheduler.setNestedShellCallback(() -> callbackInvoked.set(true));
            scheduler.onIntegrationSuccess();

            Thread.sleep(700); // 探测已发送（抑制激活）
            // 抑制期间提交：排队而非派发
            CompletableFuture<PtyCommandScheduler.CommandResult> future =
                    scheduler.submitCommand("echo hello");
            assertThat(terminal.sentData).doesNotContain("echo hello\n");

            // 回显到达（窗口内无帧）：裁决确认嵌套 → FALLBACK → 队列命令异常完成
            scheduler.collectOutput("_ANANOESIS_NESTED_PROBE_12345");
            // WHY 900ms：回显刷新帧活动时刻，裁决顺延一轮，需跨两个窗口
            Thread.sleep(900);

            assertThat(callbackInvoked.get()).as("回显+窗口内无帧必须触发重安装回调").isTrue();
            assertThat(future).as("FALLBACK 时队列命令必须以异常完成，调用方走 exec 回落")
                    .isCompletedExceptionally();
        }

        @Test
        @DisplayName("探测到期前帧活动恢复：重排探测而非发送（帧活动顺延分支）")
        void frameActivityBeforeDeadlineReschedulesInsteadOfProbing() throws Exception {
            PtyCommandScheduler scheduler = createSchedulerWithNestedDetect(500);
            scheduler.onIntegrationSuccess();

            // 探测到期（500ms）前帧活动已恢复 → idleMs < 阈值 → 重排而非发送
            Thread.sleep(250);
            scheduler.onFrame(new ShellFrame(ShellFrameType.CWD, NONCE, "0", "/root"));
            Thread.sleep(300); // t≈550：此时 idleMs≈300 < 500，不得发探针
            assertThat(terminal.sentData).as("帧活动恢复后不得发送探测")
                    .noneMatch(s -> s.contains(PtyCommandScheduler.NESTED_PROBE_MARKER));

            // 持续无帧 → 下个周期（t≈1050）发送探测
            Thread.sleep(600);
            assertThat(terminal.sentData).as("帧活动停止后探测应正常发送")
                    .anyMatch(s -> s.contains(PtyCommandScheduler.NESTED_PROBE_MARKER));
        }

        @Test
        @DisplayName("嵌套检测超时配置为 0：完全禁用探测（旧行为保留开关）")
        void nestedDetectDisabledSendsNoProbe() throws Exception {
            PtyCommandScheduler scheduler = new PtyCommandScheduler(terminal, NONCE, executor, 200, 150,
                    PtyCommandScheduler.DEFAULT_MAX_ABSOLUTE_MS,
                    PtyCommandScheduler.DEFAULT_BUSY_EXPIRE_MS, 0);
            scheduler.onIntegrationSuccess();

            Thread.sleep(700);

            assertThat(terminal.sentData).as("禁用嵌套检测时不得发送探测")
                    .noneMatch(s -> s.contains(PtyCommandScheduler.NESTED_PROBE_MARKER));
        }
    }

    // ==================================================================
    // 人工命令追踪与回调（sync-shell-memory-to-agent）
    // ==================================================================

    @Nested
    @DisplayName("人工命令追踪")
    class ManualCommandTracking {

        @Test
        @DisplayName("extractCommandFromEcho 从第一行回显提取命令文本")
        void extractCommandFromEchoExtractsFirstLine() {
            String output = "docker ps\r\nCONTAINER ID   IMAGE\nabc123   nginx";
            assertThat(PtyCommandScheduler.extractCommandFromEcho(output))
                    .isEqualTo("docker ps");
        }

        @Test
        @DisplayName("extractCommandFromEcho 空输出返回空字符串")
        void extractCommandFromEchoHandlesEmpty() {
            assertThat(PtyCommandScheduler.extractCommandFromEcho("")).isEmpty();
            assertThat(PtyCommandScheduler.extractCommandFromEcho(null)).isEmpty();
        }

        @Test
        @DisplayName("extractCommandFromEcho 无换行时返回整行去回车")
        void extractCommandFromEchoHandlesNoNewline() {
            assertThat(PtyCommandScheduler.extractCommandFromEcho("ls -la\r"))
                    .isEqualTo("ls -la");
        }

        @Test
        @DisplayName("isNestedShell 初始状态返回 false")
        void isNestedShellInitiallyFalse() {
            PtyCommandScheduler scheduler = createScheduler();
            scheduler.onIntegrationSuccess();
            assertThat(scheduler.isNestedShell()).isFalse();
        }

        @Test
        @DisplayName("人工命令完成后触发回调且参数正确")
        void manualCommandCallbackFiresOnComplete() throws Exception {
            PtyCommandScheduler scheduler = createSchedulerWithBusyExpire(5000);
            scheduler.onIntegrationSuccess();

            // 收集回调信息
            java.util.concurrent.atomic.AtomicReference<PtyCommandScheduler.ManualCommandInfo> captured =
                    new java.util.concurrent.atomic.AtomicReference<>();
            scheduler.setManualCommandListener(captured::set);

            // 模拟人工命令流程：busy → CMD_START → 输出 → CMD_END → PROMPT
            scheduler.onManualBusy();
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_START, NONCE, "0", "ls"));
            scheduler.collectOutput("ls\r\nfile1.txt\nfile2.txt\n");
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "0", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "0", ""));

            // 回调应被触发
            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().command()).isEqualTo("ls");
            assertThat(captured.get().exitCode()).isEqualTo(0);
            assertThat(captured.get().output()).contains("file1.txt");
        }

        @Test
        @DisplayName("无 CMD_START 证据时 PROMPT 不触发回调（用户只是敲了 Enter）")
        void noCallbackWithoutCmdStart() {
            PtyCommandScheduler scheduler = createSchedulerWithBusyExpire(5000);
            scheduler.onIntegrationSuccess();

            java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
            scheduler.setManualCommandListener(info -> fired.set(true));

            // 模拟用户只是敲了 Enter（无 CMD_START）
            scheduler.onManualBusy();
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "0", ""));

            assertThat(fired.get()).isFalse();
        }

        @Test
        @DisplayName("Agent 命令派发后重置人工命令追踪状态")
        void dispatchResetsManualTracking() throws Exception {
            PtyCommandScheduler scheduler = createSchedulerWithBusyExpire(5000);
            scheduler.onIntegrationSuccess();

            java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
            scheduler.setManualCommandListener(info -> fired.set(true));

            // 先开始人工命令追踪
            scheduler.onManualBusy();
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_START, NONCE, "0", "ls"));

            // Agent 命令接管（派发重置人工追踪状态）
            scheduler.onManualIdle();
            scheduler.submitCommand("pwd");

            // 人工命令的 PROMPT 不应触发回调（已被 Agent 重置）
            // 完成 Agent 命令
            scheduler.onFrame(new ShellFrame(ShellFrameType.CMD_END, NONCE, "1", "0"));
            scheduler.onFrame(new ShellFrame(ShellFrameType.PROMPT, NONCE, "1", ""));

            assertThat(fired.get()).isFalse();
        }
    }

    // ==================================================================
    // Stubs
    // ==================================================================

    /**
     * 捕获所有 send() 调用的终端会话桩。
     */
    static class CapturingTerminalSession extends SshTerminalSession {
        final List<String> sentData = new ArrayList<>();

        CapturingTerminalSession() {
            super(UUID.randomUUID().toString(), null, null, null,
                    new SessionRuntimeTest.StubOutputListener(), reason -> { });
        }

        @Override
        public void send(String data) {
            if (data != null && !data.isEmpty()) {
                sentData.add(data);
            }
        }

        @Override
        public void close(SshCloseReason reason) { }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void resize(int columns, int rows) { }
    }
}
