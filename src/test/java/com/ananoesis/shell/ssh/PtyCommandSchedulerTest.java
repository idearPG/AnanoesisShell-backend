package com.ananoesis.shell.ssh;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

            scheduler.onFrame(new ShellFrame(ShellFrameType.CWD, NONCE, "0", "/tmp/acceptance"));

            assertThat(scheduler.sessionCwd()).isEqualTo("/tmp/acceptance");

            // 后续 agent 命令的 cwd 帧（cmdId 非 0）同样更新，不互相覆盖出旧值
            scheduler.submitCommand("pwd");
            scheduler.onFrame(new ShellFrame(ShellFrameType.CWD, NONCE, "c1", "/var/log"));
            assertThat(scheduler.sessionCwd()).isEqualTo("/var/log");
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
