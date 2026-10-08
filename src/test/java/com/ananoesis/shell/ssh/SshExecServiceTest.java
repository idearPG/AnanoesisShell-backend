package com.ananoesis.shell.ssh;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.support.FakeSshServer;
import com.ananoesis.shell.support.SshTestDoubles.FixedTargetResolver;
import static com.ananoesis.shell.support.TestWait.until;

/**
 * task 6.5：独立于 PTY 的 exec channel 原语。
 *
 * <p>这条原语是 Wave 3 里 AI 工具 {@code run_command} 与只读工具
 * （{@code list_dir}/{@code read_file}/{@code system_info}）的唯一执行入口，
 * 因此它的契约必须**现在就**稳定下来：返回 stdout / stderr / exit code 三者分离、
 * 非交互、带执行超时与输出上限（design.md D4 的输出约束）。</p>
 *
 * <p>WHY 强调"非交互"并专门断言 PTY 未被分配：
 * 若 exec 误用了 PTY，{@code sudo}/{@code vim} 这类程序会把通道挂死等待输入，
 * AI 的一次工具调用就会永久卡住。断言 {@code tty} 命令回报 {@code notty}
 * 是唯一能真正锁住这条性质的办法。</p>
 *
 * <p>WHY 断言"每次调用都自己开一条连接并在结束时释放"：
 * D4 要求两条通道彼此独立——AI 跑命令绝不能干扰用户正在使用的终端。
 * 复用同一条 SSHClient 看似省资源，实际会把 AI 的输出限制、超时中断
 * 传染到人的交互式会话上。</p>
 */
class SshExecServiceTest {

    private static FakeSshServer fake;

    private SshExecService execService;
    private FixedTargetResolver resolver;

    @BeforeAll
    static void startFakeServer() throws IOException {
        fake = FakeSshServer.start();
    }

    @AfterAll
    static void stopFakeServer() {
        fake.close();
    }

    @BeforeEach
    void newService() {
        var properties = SshPropertiesFixture.fast();
        resolver = new FixedTargetResolver(passwordTarget());
        execService = new SshExecService(new SshConnectionService(properties), properties, resolver);
    }

    @Test
    @DisplayName("stdout / stderr / exit code 三者分离且各自正确")
    void capturesStdoutStderrAndExitCodeSeparately() {
        ExecOutcome success = execService.execute(passwordTarget(), "echo hello-world");
        assertThat(success.exitCode()).as("正常命令退出码应为 0").isZero();
        assertThat(success.stdout()).isEqualTo("hello-world\n");
        assertThat(success.stderr()).isEmpty();
        assertThat(success.timedOut()).isFalse();
        assertThat(success.truncated()).isFalse();

        ExecOutcome failure = execService.execute(passwordTarget(), "fail boom");
        assertThat(failure.exitCode()).as("失败命令必须回传远端真实退出码").isEqualTo(3);
        assertThat(failure.stdout()).isEmpty();
        assertThat(failure.stderr()).isEqualTo("fake-failure: boom\n");

        ExecOutcome stderrOnly = execService.execute(passwordTarget(), "echoerr just-stderr");
        assertThat(stderrOnly.exitCode()).isZero();
        assertThat(stderrOnly.stdout()).as("stderr 不得混进 stdout").isEmpty();
        assertThat(stderrOnly.stderr()).isEqualTo("just-stderr\n");
    }

    @Test
    @DisplayName("未知命令回传 127 与 stderr 提示")
    void unknownCommandYieldsExit127() {
        ExecOutcome outcome = execService.execute(passwordTarget(), "no-such-command-xyz");

        assertThat(outcome.exitCode()).isEqualTo(127);
        assertThat(outcome.stderr()).contains("command not found");
    }

    @Test
    @DisplayName("exec 通道不分配 PTY（非交互）")
    void execDoesNotAllocatePty() {
        ExecOutcome outcome = execService.execute(passwordTarget(), "tty");

        assertThat(outcome.stdout().trim()).as("带 PTY 会是 pty，非交互必须是 notty").isEqualTo("notty");
        assertThat(fake.ptyAllocations()).as("exec 不该触发任何 shell/PTY 分配").isEmpty();
        assertThat(fake.execCommands()).as("服务端应原样收到我们下发的命令").contains("tty");
    }

    @Test
    @DisplayName("命令超时被中断并标记 timedOut，不会一直挂住")
    void commandTimeoutIsReported() {
        ExecLimits limits = new ExecLimits(Duration.ofMillis(400), 65_536);

        long startedAt = System.nanoTime();
        ExecOutcome outcome = execService.execute(passwordTarget(), "sleep 5000", limits);
        long elapsed = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(outcome.timedOut()).as("超时必须被显式标记，供 Wave 3 映射为 execution_timeout").isTrue();
        assertThat(elapsed).as("应在超时上限附近返回，而不是等满 5 秒").isLessThan(4_000L);
    }

    @Test
    @DisplayName("超过输出上限的内容被截断并标记 truncated")
    void oversizedOutputIsTruncatedAndFlagged() {
        ExecLimits limits = new ExecLimits(Duration.ofSeconds(5), 1_000);

        ExecOutcome outcome = execService.execute(passwordTarget(), "big 20000", limits);

        assertThat(outcome.truncated()).as("截断必须被显式标记，不能静默丢数据").isTrue();
        assertThat(outcome.stdout()).hasSize(1_000);
        assertThat(outcome.exitCode()).as("截断不改变命令自身的退出码").isZero();
    }

    @Test
    @DisplayName("上限之内不截断")
    void outputWithinLimitIsNotTruncated() {
        ExecOutcome outcome = execService.execute(passwordTarget(), "big 500",
                new ExecLimits(Duration.ofSeconds(5), 4_096));

        assertThat(outcome.truncated()).isFalse();
        assertThat(outcome.stdout()).hasSize(500);
    }

    @Test
    @DisplayName("Wave 3 入口：按 hostId 解析目标后执行，并向解析器询问正确的 id")
    void executeForHostResolvesTargetThroughResolver() {
        UUID hostId = UUID.randomUUID();

        ExecOutcome outcome = execService.executeForHost(hostId, "echo via-host-id");

        assertThat(outcome.stdout()).isEqualTo("via-host-id\n");
        assertThat(resolver.requestedHostIds())
                .as("必须解析用户真正选中的那台主机")
                .containsExactly(hostId);
    }

    @Test
    @DisplayName("每次执行都自建并释放连接，连跑多次不泄漏会话")
    void eachExecutionReleasesItsConnection() {
        for (int i = 0; i < 3; i++) {
            ExecOutcome outcome = execService.execute(passwordTarget(), "echo round-" + i);
            assertThat(outcome.stdout()).isEqualTo("round-" + i + "\n");
        }

        until("服务端会话数应回落到 0（连接确已释放）", () -> fake.activeSessionCount() == 0);
    }

    @Test
    @DisplayName("连接失败时抛出受控异常，而不是把 IOException 泄漏给上层")
    void connectFailurePropagatesAsSshConnectException() {
        SshTarget unreachable = SshTarget.of("127.0.0.1", 1, FakeSshServer.USERNAME,
                SshAuthMethod.password(FakeSshServer.PASSWORD));

        assertThatThrownBy(() -> execService.execute(unreachable, "echo never"))
                .isInstanceOf(SshConnectException.class)
                .satisfies(thrown -> assertThat(((SshConnectException) thrown).userMessage())
                        .as("对外文案不得含内部细节").isEqualTo("连接失败：主机不可达"));
    }

    @Test
    @DisplayName("空命令不发往远端，直接返回空结果")
    void blankCommandIsRejectedLocally() {
        int execCommandsBefore = fake.execCommands().size();

        ExecOutcome outcome = execService.execute(passwordTarget(), "   ");

        assertThat(outcome.exitCode()).as("空命令按参数错误处理，退出码非 0").isNotZero();
        assertThat(outcome.stderr()).contains("命令");
        // WHY 比较前后数量而非断言为空：fake 服务器被本类所有用例共享，
        // 之前用例下发的命令仍在记录里，断言 isEmpty 会让结果依赖用例执行顺序。
        assertThat(fake.execCommands()).as("不该为一个空命令去打扰远端").hasSize(execCommandsBefore);
    }

    private static SshTarget passwordTarget() {
        return SshTarget.of("127.0.0.1", fake.port(), FakeSshServer.USERNAME,
                SshAuthMethod.password(FakeSshServer.PASSWORD));
    }
}
