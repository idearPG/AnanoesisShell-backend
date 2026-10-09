package com.ananoesis.shell.ssh;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.service.CommandExecutionService;
import com.ananoesis.shell.service.ConversationService;

import com.ananoesis.shell.support.FakeSshServer;
import com.ananoesis.shell.support.SshTestDoubles.FixedTargetResolver;
import com.ananoesis.shell.support.SshTestDoubles.InMemorySessionRecorder;
import com.ananoesis.shell.support.SshTestDoubles.RecordingTerminalListener;
import com.ananoesis.shell.support.TestWait;
import static com.ananoesis.shell.support.TestWait.until;

/**
 * tasks 6.3 / 6.6：交互式 PTY 通道与会话生命周期。
 *
 * <p>覆盖 ssh-connection spec「交互式终端会话」的三个 Scenario
 * （命令实时回显、交互式程序可用/按键转发、中断信号 Ctrl-C）
 * 与「连接会话生命周期」的两个 Scenario（主动断开、远端关闭连接），
 * 外加超时释放（{@code EndReason.timeout}）。</p>
 *
 * <p>WHY PTY 分配要被断言而不能想当然：
 * 忘了 {@code allocatePTY} 的 shell 在远端看来不是终端——彩色输出、光标控制、
 * {@code top}/{@code vim} 全部失效，而且**不会报错**，只会表现为一堆乱码。
 * 让替身回报"服务端是否看到了 PTY 请求"，是唯一能挡住这类静默退化的断言。</p>
 *
 * <p>WHY 资源释放要看服务端脸色（{@code activeSessionCount()}）：
 * 只断言 {@code session.isOpen() == false} 是自我证明——我们把自己的标志位改成 false，
 * 断言自然通过，而底层 socket 与读线程可能还活着。让 SSH 服务端报告剩余会话数，
 * 才能证明连接真的被对端确认关闭了。</p>
 */
class SshTerminalServiceTest {

    private static FakeSshServer fake;

    private SshTerminalService terminalService;
    private TerminalSessionRegistry registry;
    private FixedTargetResolver resolver;
    private InMemorySessionRecorder recorder;
    private RecordingTerminalListener listener;
    private SshTerminalSession session;

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
        registry = new TerminalSessionRegistry();
        resolver = new FixedTargetResolver(passwordTarget());
        recorder = new InMemorySessionRecorder();
        terminalService = new SshTerminalService(
                new SshConnectionService(properties), properties, registry, resolver, recorder,
                mock(CommandExecutionService.class), mock(ConversationService.class));
        listener = new RecordingTerminalListener();
    }

    @AfterEach
    void releaseSession() {
        if (session != null) {
            session.close(SshCloseReason.USER_DISCONNECT);
        }
    }

    // ==================================================================
    // 6.3 交互式 PTY
    // ==================================================================

    @Test
    @DisplayName("打开终端时分配 PTY，且把配置的行列数送达远端")
    void ptyIsAllocatedWithConfiguredDimensions() {
        // WHY 先记 before 再断言增量：fake 是 @BeforeAll 起的**静态共享**服务器，
        // ptyAllocations 跨用例累积且无重置入口。若写成 containsExactly(true)，
        // 本用例只要不是第一个执行就必然红——那是执行顺序的偶发，与被测行为无关。
        int before = fake.ptyAllocations().size();
        session = terminalService.open(passwordTarget(), listener);

        until("服务端应看到一次 shell 通道建立", () -> fake.ptyAllocations().size() > before);
        assertThat(fake.ptyAllocations().subList(before, fake.ptyAllocations().size()))
                .as("交互式 shell 必须请求 PTY")
                .containsExactly(true);

        send("cols");
        until("应回显 PTY 列数 80", () -> listener.stdout().contains("80"));
    }

    @Test
    @DisplayName("命令实时回显：stdout 分片流式到达，而非一次性返回")
    void commandOutputIsStreamedToListener() {
        session = terminalService.open(passwordTarget(), listener);

        send("echo first-line");
        until("第一行输出应到达", () -> listener.stdout().contains("first-line"));
        send("echo second-line");
        until("第二行输出应到达", () -> listener.stdout().contains("second-line"));

        // WHY 断言分片数 >= 2：若实现攒齐所有输出再回调，"实时回显"就名存实亡，
        // 前端 xterm.js 会表现为"卡住然后一次性刷出一屏"
        assertThat(listener.stdoutChunkCount()).as("输出应分多次流式到达").isGreaterThanOrEqualTo(2);
        assertThat(session.isOpen()).isTrue();
    }

    @Test
    @DisplayName("远端 tty 判定为 pty（交互式程序可用的前提）")
    void remoteSeesTerminalAsPty() {
        session = terminalService.open(passwordTarget(), listener);

        send("tty");
        until("应回显 pty", () -> listener.stdout().contains("pty"));
        assertThat(listener.stdout()).doesNotContain("notty");
    }

    @Test
    @DisplayName("stderr 走独立流，不与 stdout 混淆")
    void stderrArrivesOnSeparateStream() {
        session = terminalService.open(passwordTarget(), listener);

        // WHY 用 fail 而不是 echoerr，且断言的是 "fake-failure:" 这个**前缀**：
        // PTY 会把用户敲入的每个字符回显到 stdout（真实终端就是这样，
        // FakeSshServer.readLoop 也如实模拟了），所以"命令正文出现在 stdout"是**正确行为**，
        // 拿它当串流证据必然误报。只有"仅可能由 stderr 产生"的前缀才能证明
        // 我们把 shell 的 getInputStream()/getErrorStream() 接对了、没有互换。
        send("fail boom-on-stderr");
        until("stderr 应到达", () -> listener.stderr().contains("fake-failure: boom-on-stderr"));

        assertThat(listener.stderr()).contains("fake-failure: boom-on-stderr");
        assertThat(listener.stdout()).as("stderr 不得混入 stdout").doesNotContain("fake-failure:");
    }

    @Test
    @DisplayName("按键被逐字节转发（交互式程序的输入前提）")
    void keystrokesAreForwardedCharacterByCharacter() {
        session = terminalService.open(passwordTarget(), listener);

        // 逐字符发送，模拟真实终端的按键流；服务端应逐字节收到并回显
        for (char c : "echo key-by-key\r".toCharArray()) {
            session.send(String.valueOf(c));
        }

        until("命令输出应到达", () -> listener.stdout().contains("key-by-key"));
        until("服务端应收到全部按键", () -> fake.shellInputs().size() >= "echo key-by-key\r".length());
    }

    @Test
    @DisplayName("Ctrl-C 被转发为中断信号并终止前台命令")
    void ctrlCTerminatesForegroundCommand() {
        session = terminalService.open(passwordTarget(), listener);

        send("wait");
        until("前台命令应已开始运行", () -> fake.shellInputs().contains("t"));
        session.send("\u0003");

        until("服务端应记录到 INT 信号", () -> fake.observedSignals().contains("INT"));
        until("前台命令应被中断", () -> listener.stderr().contains("interrupted"));
        assertThat(listener.stdout()).as("终端应回显 ^C").contains("^C");
        assertThat(session.isOpen()).as("中断前台命令不应终结整个会话").isTrue();

        // 中断之后 shell 仍可用：这是"Ctrl-C 终止当前命令"与"连接被断开"的本质区别
        send("echo still-alive");
        until("会话应仍然可用", () -> listener.stdout().contains("still-alive"));
    }

    @Test
    @DisplayName("resize 把新的窗口尺寸转发到远端 PTY")
    void resizeChangesRemotePtyDimensions() {
        session = terminalService.open(passwordTarget(), listener);

        session.resize(120, 40);
        send("cols");
        until("远端应看到新的列数 120", () -> listener.stdout().contains("120"));
    }

    // ==================================================================
    // 6.6 生命周期与资源释放
    // ==================================================================

    @Test
    @DisplayName("主动断开：上报 user_disconnect、释放连接、幂等")
    void userCloseReleasesResources() {
        session = terminalService.open(passwordTarget(), listener);
        until("会话应已建立", () -> fake.activeSessionCount() > 0);

        session.close(SshCloseReason.USER_DISCONNECT);
        session.close(SshCloseReason.USER_DISCONNECT);

        assertThat(session.isOpen()).isFalse();
        assertThat(listener.closedReasons())
                .as("重复关闭不得重复通知前端，否则会看到两条 closed 事件")
                .containsExactly(SshCloseReason.USER_DISCONNECT);
        until("服务端会话应被释放", () -> fake.activeSessionCount() == 0);
        until("注册表应清空", () -> registry.size() == 0);
    }

    @Test
    @DisplayName("远端优雅关闭（hangup）：上报 remote_close 并清理本地状态")
    void remoteHangupIsReportedAsRemoteClose() {
        session = terminalService.open(passwordTarget(), listener);

        send("hangup");
        until("应收到远端关闭事件", () -> listener.isClosed());

        assertThat(listener.closedReasons()).containsExactly(SshCloseReason.REMOTE_CLOSED);
        assertThat(session.isOpen()).as("远端关闭后本地状态必须同步失效").isFalse();
        until("注册表应清空", () -> registry.size() == 0);
        until("服务端会话应被释放", () -> fake.activeSessionCount() == 0);
    }

    @Test
    @DisplayName("远端粗暴断开（拔网线）：同样上报 remote_close，不抛出未捕获异常")
    void abruptServerShutdownIsReportedAsRemoteClose() throws IOException {
        try (FakeSshServer doomed = FakeSshServer.start()) {
            SshTarget target = SshTarget.of("127.0.0.1", doomed.port(), FakeSshServer.USERNAME,
                    SshAuthMethod.password(FakeSshServer.PASSWORD));
            SshTerminalSession doomedSession = terminalService.open(target, listener);
            until("会话应已建立", () -> doomed.activeSessionCount() > 0);

            doomed.stopAbruptly();

            until("应检测到远端断开", () -> listener.isClosed());
            assertThat(listener.closedReasons()).containsExactly(SshCloseReason.REMOTE_CLOSED);
            assertThat(doomedSession.isOpen()).isFalse();
            until("注册表应清空", () -> registry.size() == 0);
        }
    }

    @Test
    @DisplayName("空闲超时：到期会话被主动关闭并上报 timeout")
    void idleSessionIsExpiredWithTimeoutReason() {
        session = terminalService.open(passwordTarget(), listener);
        until("会话应已建立", () -> fake.activeSessionCount() > 0);

        int expired = registry.expireIdleSessions(Duration.ZERO);

        assertThat(expired).as("应恰好回收一个空闲会话").isEqualTo(1);
        until("应收到超时关闭事件", () -> listener.isClosed());
        assertThat(listener.closedReasons()).containsExactly(SshCloseReason.TIMEOUT);
        until("服务端会话应被释放", () -> fake.activeSessionCount() == 0);

        session = null;
    }

    @Test
    @DisplayName("未超时的会话不被回收")
    void activeSessionIsNotExpired() {
        session = terminalService.open(passwordTarget(), listener);

        int expired = registry.expireIdleSessions(Duration.ofMinutes(30));

        assertThat(expired).isZero();
        assertThat(session.isOpen()).isTrue();
        assertThat(listener.isClosed()).isFalse();
    }

    @Test
    @DisplayName("认证失败：抛出受控异常，既不注册会话也不误报 closed")
    void connectFailureThrowsAndRegistersNothing() {
        SshTarget badTarget = SshTarget.of("127.0.0.1", fake.port(), FakeSshServer.USERNAME,
                SshAuthMethod.password("Wrong-P@ssw0rd"));

        assertThatThrownBy(() -> terminalService.open(badTarget, listener))
                .isInstanceOf(SshConnectException.class)
                .satisfies(thrown -> assertThat(((SshConnectException) thrown).userMessage())
                        .isEqualTo("认证失败"));

        assertThat(registry.size()).as("失败的连接不该留下注册项").isZero();
        assertThat(listener.isClosed()).as("连接从未建立，不应发出 closed 事件").isFalse();
        until("服务端不应残留会话", () -> fake.activeSessionCount() == 0);
    }

    @Test
    @DisplayName("按 hostId 打开：经解析器取目标、会话 id 与审计记录一致、生命周期完整落库")
    void openByHostIdRecordsFullLifecycle() {
        UUID hostId = UUID.randomUUID();

        SessionRuntime runtime = terminalService.open(hostId, listener); session = runtime.terminalSession();

        assertThat(resolver.requestedHostIds()).as("应解析用户选中的主机").containsExactly(hostId);
        InMemorySessionRecorder.Entry started = recorder.entries().get(0);
        assertThat(started.hostId()).isEqualTo(hostId);
        assertThat(started.kind()).as("交互式终端必须记为 interactive_pty").isEqualTo(SessionKind.INTERACTIVE_PTY);
        assertThat(session.id()).as("下发给前端的 session_id 必须与审计记录同一个")
                .isEqualTo(started.sessionId());
        until("应记录 open", () -> recorder.entries().stream().anyMatch(e -> "open".equals(e.status())));

        session.close(SshCloseReason.USER_DISCONNECT);

        until("应记录 end", () -> recorder.lastEnd() != null);
        assertThat(recorder.lastEnd().sessionId()).isEqualTo(session.id());
        assertThat(recorder.lastEnd().closeReason()).isEqualTo(SshCloseReason.USER_DISCONNECT);

        session = null;
    }

    @Test
    @DisplayName("注册表按 id 可查，供 WebSocket 层路由 input/close")
    void registryFindsSessionById() {
        session = terminalService.open(passwordTarget(), listener);

        assertThat(registry.find(session.id())).contains(session);
        assertThat(registry.all()).containsExactly(session);
    }

    // ==================================================================
    // known-issues #19：首连登录 prompt 抢在闸门接入前泄漏 → 同一行双提示符
    // ==================================================================

    @Test
    @DisplayName("bash 会话：登录 banner/首个 prompt 从第一个字节起静音，不得泄漏给前端")
    void bashSessionSilencesLoginBannerFromFirstByte() {
        // 真实 bash 登录后不等输入立即打印 banner+prompt；修复前 relay 初始就是
        // 原监听器，这些字节在 installer 接入闸门之前直接漏给前端（双 prompt 根因）
        fake.setLoginBanner("Last login: ...\r\n[root@localhost ~]# ");
        try {
            UUID hostId = UUID.randomUUID();
            SessionRuntime runtime = terminalService.open(hostId, listener);
            session = runtime.terminalSession();

            // 等到安装代码已送达远端（静音窗口必然已过），再给泄漏字节留反应时间
            until("应收到集成安装代码输入", () -> !fake.shellInputs().isEmpty());
            TestWait.sleep(300);

            assertThat(listener.stdout())
                    .as("bash 会话的登录 banner 必须被预安装静音吞掉，前端只应看到安装后的干净 prompt")
                    .doesNotContain("[root@localhost ~]#");
        } finally {
            fake.setLoginBanner(null);
        }
    }

    @Test
    @DisplayName("非 bash 会话：不装静音也不装闸门，登录 banner 照常透传人工终端")
    void unsupportedShellStillStreamsLoginBanner() {
        // 降级承诺（design D3）：非 bash 的输出链必须从第一个字节起就是原监听器，
        // 防止 #19 的静音修复把 banner 透传也一并误伤
        var properties = SshPropertiesFixture.fast();
        properties.setShellType("generic");
        terminalService = new SshTerminalService(
                new SshConnectionService(properties), properties, registry, resolver, recorder,
                mock(CommandExecutionService.class), mock(ConversationService.class));
        fake.setLoginBanner("[guest@host ~]$ ");
        try {
            SessionRuntime runtime = terminalService.open(UUID.randomUUID(), listener);
            session = runtime.terminalSession();

            until("generic 会话的登录 banner 应原样到达前端",
                    () -> listener.stdout().contains("[guest@host ~]$"));
        } finally {
            fake.setLoginBanner(null);
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 发送一行命令并补上回车（远端迷你 shell 以 CR/LF 为行结束）。 */
    private void send(String line) {
        session.send(line + "\r");
    }

    private static SshTarget passwordTarget() {
        return SshTarget.of("127.0.0.1", fake.port(), FakeSshServer.USERNAME,
                SshAuthMethod.password(FakeSshServer.PASSWORD));
    }
}

