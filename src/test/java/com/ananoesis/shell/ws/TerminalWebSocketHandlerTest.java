package com.ananoesis.shell.ws;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;

import com.ananoesis.shell.config.SshProperties;
import com.ananoesis.shell.service.HostNotFoundException;
import com.ananoesis.shell.ssh.SessionKind;
import com.ananoesis.shell.ssh.SshAuthMethod;
import com.ananoesis.shell.ssh.SshCloseReason;
import com.ananoesis.shell.ssh.SshConnectionService;
import com.ananoesis.shell.ssh.SshTarget;
import com.ananoesis.shell.ssh.SshTargetResolver;
import com.ananoesis.shell.ssh.SshTerminalService;
import com.ananoesis.shell.ssh.TerminalSessionRegistry;
import com.ananoesis.shell.support.FakeSshServer;
import com.ananoesis.shell.support.FakeWebSocketSession;
import com.ananoesis.shell.support.SshTestDoubles.FixedTargetResolver;
import com.ananoesis.shell.support.SshTestDoubles.InMemorySessionRecorder;
import static com.ananoesis.shell.support.TestWait.until;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * task 6.3 的传输层：{@code terminal_input} / {@code terminal_output} 的协议行为。
 *
 * <p>覆盖指挥官对 Q1 的裁定（{@code action=open} 带 host_id 发起 → 后端分配 session_id
 * 并经 terminal_output 回执；{@code input} 转发按键；{@code close} 主动断开），
 * 以及 asyncapi.yaml {@code /terminal} 通道上三类输出事件（data / error / closed）。</p>
 *
 * <p>WHY 直接用真实的 {@link SshTerminalService} + {@link FakeSshServer}，
 * 而不给处理器喂一个假的终端服务：本层要验证的是"契约帧与 SSH 会话之间的接线是否正确"，
 * 把 SSH 那一头换成 mock 就只剩"我们自己写的假行为能不能对上我们自己写的真行为"，
 * 接线错误（例如把 stderr 发到 stdout 流、closed 帧漏了 end_reason）反而测不出来。
 * 只有 {@code WebSocketSession} 这一侧用替身——它才是本层真正的外部边界。</p>
 *
 * <p>WHY 断言直接读 JSON 树而不是反序列化回 {@link TerminalOutput}：
 * 反序列化会用**同一份**注解把字段名映射回来，"字段名写错"这种漂移会被自动抵消掉。
 * 读原始 JSON 才能证明线上真正发出的键名符合契约。</p>
 */
class TerminalWebSocketHandlerTest {

    private static FakeSshServer fake;

    private ObjectMapper mapper;
    private TerminalWebSocketHandler handler;
    private FakeWebSocketSession ws;
    private TerminalSessionRegistry registry;
    private InMemorySessionRecorder recorder;
    private UUID hostId;
    /** 已消费的帧数：每次等待只在其后查找，避免历史帧造成假阳性（见 nextFrame）。 */
    private int cursor;

    @BeforeAll
    static void startFakeServer() throws IOException {
        fake = FakeSshServer.start();
    }

    @AfterAll
    static void stopFakeServer() {
        fake.close();
    }

    @BeforeEach
    void newHandler() throws Exception {
        mapper = new ObjectMapper();
        hostId = UUID.randomUUID();
        build(new FixedTargetResolver(workingTarget()));
    }

    @AfterEach
    void releaseConnection() throws Exception {
        // 兜底：任何用例结束时都把 WS 当作"用户关掉了标签页"，
        // 否则一个泄漏的 SSH 会话会拖慢甚至污染后续用例（fake 是静态共享的）
        handler.afterConnectionClosed(ws, CloseStatus.NORMAL);
    }

    // ==================================================================
    // Q1：open 握手
    // ==================================================================

    @Test
    @DisplayName("action=open 返回带 session_id 的回执帧，且审计同步落账")
    void openHandshakeReturnsSessionId() throws Exception {
        send(openFrame());

        JsonNode receipt = nextFrame("open 的回执帧", frame ->
                "data".equals(type(frame)) && frame.hasNonNull("session_id"));

        String sessionId = receipt.get("session_id").asText();
        assertThat(sessionId).as("session_id 必须是可解析的 UUID（契约 format: uuid）")
                .satisfies(value -> assertThat(UUID.fromString(value)).isNotNull());
        // WHY 回执帧的 data 为空串而不是省略：见 TerminalOutput.opened() 的论证——
        // 契约 type 枚举没有 opened，只能借 data 帧下发 session_id
        assertThat(receipt.path("data").asText()).isEmpty();
        assertThat(receipt.path("stream").asText()).isEqualTo("stdout");

        assertThat(recorder.entries())
                .as("open 成功后 sessions 表应先有 connecting 再转 open")
                .extracting(entry -> entry.status())
                .contains("connecting", "open");
        assertThat(recorder.entries())
                .extracting(entry -> entry.kind())
                .contains(SessionKind.INTERACTIVE_PTY);
        assertThat(registry.size()).as("会话应已注册，空闲回收才能看到它").isEqualTo(1);
    }

    @Test
    @DisplayName("action=input 转发按键，远端回显经 data 帧流回并带 session_id")
    void inputIsForwardedAndEchoed() throws Exception {
        String sessionId = openTerminal();

        send(inputFrame(sessionId, "echo hello-ws\r"));

        JsonNode echoed = nextFrame("远端回显", frame ->
                "data".equals(type(frame)) && frame.path("data").asText().contains("hello-ws"));
        assertThat(echoed.get("session_id").asText())
                .as("每一帧输出都必须带 session_id，前端才知道该往哪个终端窗格里写")
                .isEqualTo(sessionId);
        assertThat(echoed.path("stream").asText()).isEqualTo("stdout");
    }

    @Test
    @DisplayName("stderr 以 stream=stderr 下发，不与 stdout 混淆")
    void stderrUsesSeparateStreamValue() throws Exception {
        String sessionId = openTerminal();

        send(inputFrame(sessionId, "fail ws-stderr\r"));

        nextFrame("stderr 帧", frame ->
                "stderr".equals(frame.path("stream").asText())
                        && frame.path("data").asText().contains("fake-failure: ws-stderr"));
    }

    @Test
    @DisplayName("Ctrl-C 控制字节原样透传，远端看到 INT 信号")
    void controlBytesAreForwardedVerbatim() throws Exception {
        String sessionId = openTerminal();

        send(inputFrame(sessionId, "wait\r"));
        until("前台命令应已开始运行", () -> fake.shellInputs().contains("t"));
        send(inputFrame(sessionId, "\u0003"));

        until("远端应记录到 INT 信号（ssh-connection spec「中断运行中的命令」）",
                () -> fake.observedSignals().contains("INT"));
    }

    // ==================================================================
    // Q1：close 与会话生命周期
    // ==================================================================

    @Test
    @DisplayName("action=close 主动断开：closed 帧带 end_reason=user_disconnect 且资源释放")
    void closeActionEndsSessionWithUserDisconnect() throws Exception {
        String sessionId = openTerminal();
        until("服务端应看到会话", () -> fake.activeSessionCount() > 0);

        send(closeFrame(sessionId));

        JsonNode closed = nextFrame("closed 帧", frame -> "closed".equals(type(frame)));
        assertThat(closed.path("end_reason").asText())
                .as("spec「主动断开」→ 契约 EndReason.user_disconnect")
                .isEqualTo("user_disconnect");
        assertThat(closed.get("session_id").asText()).isEqualTo(sessionId);

        until("注册表应清空", () -> registry.size() == 0);
        // WHY 看服务端脸色：只断言本地 isOpen()==false 是自我证明，
        // 底层 socket 与两条读泵线程可能还活着
        until("服务端会话应被释放", () -> fake.activeSessionCount() == 0);
        assertThat(recorder.lastEnd()).isNotNull();
        assertThat(recorder.lastEnd().closeReason()).isEqualTo(SshCloseReason.USER_DISCONNECT);
    }

    @Test
    @DisplayName("远端挂断 → closed 帧带 end_reason=remote_close")
    void remoteHangupEmitsRemoteClose() throws Exception {
        String sessionId = openTerminal();

        send(inputFrame(sessionId, "hangup\r"));

        JsonNode closed = nextFrame("closed 帧", frame -> "closed".equals(type(frame)));
        assertThat(closed.path("end_reason").asText())
                .as("spec「远端关闭连接」→ 契约 EndReason.remote_close")
                .isEqualTo("remote_close");
        until("注册表应清空", () -> registry.size() == 0);
    }

    @Test
    @DisplayName("WS 连接断开（用户关掉标签页）时，本连接下所有 SSH 会话都被释放")
    void transportCloseReleasesAllTerminals() throws Exception {
        openTerminal();
        openTerminal();
        until("应建立两条服务端会话", () -> fake.activeSessionCount() >= 2);

        handler.afterConnectionClosed(ws, CloseStatus.NORMAL);

        until("两条会话都应被释放", () -> fake.activeSessionCount() == 0);
        assertThat(registry.size()).isZero();
    }

    @Test
    @DisplayName("发送失败（对端已消失）不得沿读泵线程上抛，会话仍被正常关闭")
    void sendFailureDoesNotBreakReadPump() throws Exception {
        String sessionId = openTerminal();

        // WHY 这一条值得单独测：真实场景里"用户直接关掉标签页"必然导致写失败。
        // 若异常沿读泵线程冒泡，SshTerminalSession.close() 的 finally 链会被打断，
        // socket 与线程就泄漏了——桌面应用表现为越用越卡。
        ws.failOnSend(true);
        send(inputFrame(sessionId, "hangup\r"));

        until("即使写不出去，会话也必须被释放", () -> registry.size() == 0);
        until("服务端会话应被释放", () -> fake.activeSessionCount() == 0);
    }

    // ==================================================================
    // 错误帧：error_code 与不泄露内部细节
    // ==================================================================

    @Test
    @DisplayName("认证失败 → error 帧 error_code=auth_failed，文案为契约固定语")
    void authFailureIsReportedAsAuthFailed() throws Exception {
        build(new FixedTargetResolver(SshTarget.of("127.0.0.1", fake.port(),
                FakeSshServer.USERNAME, SshAuthMethod.password("wrong-password"))));

        send(openFrame());

        JsonNode error = nextFrame("error 帧", frame -> "error".equals(type(frame)));
        assertThat(error.path("error_code").asText()).isEqualTo("auth_failed");
        assertThat(error.path("message").asText()).isEqualTo("认证失败");
        assertThat(error.has("session_id")).as("open 失败时尚未下发 session_id").isFalse();
        assertThat(registry.size()).as("失败的连接不得留下会话").isZero();
    }

    @Test
    @DisplayName("主机不可达 → error 帧 error_code=host_unreachable")
    void unreachableHostIsReportedAsHostUnreachable() throws Exception {
        build(new FixedTargetResolver(SshTarget.of("127.0.0.1", closedPort(),
                FakeSshServer.USERNAME, SshAuthMethod.password(FakeSshServer.PASSWORD))));

        send(openFrame());

        JsonNode error = nextFrame("error 帧", frame -> "error".equals(type(frame)));
        assertThat(error.path("error_code").asText()).isEqualTo("host_unreachable");
        assertThat(error.path("message").asText()).isEqualTo("连接失败：主机不可达");
    }

    @Test
    @DisplayName("主机配置不存在 → error 帧 error_code=not_found")
    void unknownHostIsReportedAsNotFound() throws Exception {
        build(new SshTargetResolver() {
            @Override
            public <T> T withTarget(UUID requested, Function<SshTarget, T> action) {
                throw HostNotFoundException.forId(requested.toString());
            }
        });

        send(openFrame());

        JsonNode error = nextFrame("error 帧", frame -> "error".equals(type(frame)));
        assertThat(error.path("error_code").asText()).isEqualTo("not_found");
    }

    @Test
    @DisplayName("error 帧 MUST NOT 泄露内部细节（地址/端口/异常类型/密码）")
    void errorFrameDoesNotLeakInternals() throws Exception {
        build(new FixedTargetResolver(SshTarget.of("127.0.0.1", fake.port(),
                FakeSshServer.USERNAME, SshAuthMethod.password("wrong-password"))));

        send(openFrame());

        JsonNode error = nextFrame("error 帧", frame -> "error".equals(type(frame)));
        String message = error.path("message").asText();
        // WHY 逐项排查：ssh-connection spec 要求"不得泄露服务器内部细节"，
        // 而 SshConnectException.getMessage() 里恰恰装着地址、端口与底层异常文案。
        // 一旦有人图省事把 getMessage() 填进 message 字段，这条断言就会红。
        assertThat(message)
                .doesNotContain("127.0.0.1")
                .doesNotContain(String.valueOf(fake.port()))
                .doesNotContain("wrong-password")
                .doesNotContain("Exception")
                .doesNotContain("TransportException");
    }

    // ==================================================================
    // 协议校验
    // ==================================================================

    @Test
    @DisplayName("非法 JSON → validation_error，且连接不被掐断")
    void malformedJsonIsValidationError() throws Exception {
        handler.handleMessage(ws, new TextMessage("{not-json"));

        JsonNode error = nextFrame("error 帧", frame -> "error".equals(type(frame)));
        assertThat(error.path("error_code").asText()).isEqualTo("validation_error");
        assertThat(ws.isOpen()).as("一次坏帧不该终结整条连接").isTrue();
    }

    @Test
    @DisplayName("action=open 缺 host_id → validation_error")
    void openWithoutHostIdIsValidationError() throws Exception {
        send(frame().put("action", "open"));

        JsonNode error = nextFrame("error 帧", frame -> "error".equals(type(frame)));
        assertThat(error.path("error_code").asText()).isEqualTo("validation_error");
        assertThat(error.path("message").asText()).contains("host_id");
    }

    @Test
    @DisplayName("action=input 缺 session_id → validation_error")
    void inputWithoutSessionIdIsValidationError() throws Exception {
        send(frame().put("action", "input").put("data", "ls\r"));

        JsonNode error = nextFrame("error 帧", frame -> "error".equals(type(frame)));
        assertThat(error.path("error_code").asText()).isEqualTo("validation_error");
        assertThat(error.path("message").asText()).contains("session_id");
    }

    @Test
    @DisplayName("session_id 不属于本连接 → not_found（不泄露该会话是否存在）")
    void foreignSessionIdIsNotFound() throws Exception {
        openTerminal();

        send(inputFrame(UUID.randomUUID().toString(), "ls\r"));

        JsonNode error = nextFrame("error 帧", frame -> "error".equals(type(frame)));
        // WHY 必须是 not_found 而不是"该会话属于别的连接"：后者等于告诉攻击者
        // "这个 id 是真实存在的"，配合暴力枚举就能劫持他人终端
        assertThat(error.path("error_code").asText()).isEqualTo("not_found");
    }

    @Test
    @DisplayName("action 缺失或未知 → validation_error")
    void missingOrUnknownActionIsValidationError() throws Exception {
        send(frame().put("data", "ls\r"));
        nextFrame("缺 action 的 error 帧", f -> "error".equals(type(f)));

        // WHY 断言 error_code 而不是 message 内容：线上的未知取值到不了处理器的 switch default——
        // Jackson 默认拒绝未知枚举常量（READ_UNKNOWN_ENUM_VALUES_AS_NULL 关闭），
        // 它在反序列化阶段就失败，走的是"帧无法解析"分支。两条路径都必须给出 validation_error，
        // 前端因此只需处理一个错误码。
        send(frame().put("action", "resize"));
        JsonNode unknownAction = nextFrame("未知 action 的 error 帧", f -> "error".equals(type(f)));
        assertThat(unknownAction.path("error_code").asText()).isEqualTo("validation_error");
    }

    @Test
    @DisplayName("同一条 WS 上可并存多个终端，各自只收到自己会话的输出")
    void multipleTerminalsOnOneConnectionAreIsolated() throws Exception {
        String first = openTerminal();
        String second = openTerminal();
        assertThat(second).as("两次 open 必须分配到不同 session_id").isNotEqualTo(first);

        send(inputFrame(first, "echo only-first\r"));

        nextFrame("第一个终端的回显", f ->
                first.equals(f.path("session_id").asText()) && f.path("data").asText().contains("only-first"));
        assertThat(frames().stream()
                .filter(f -> second.equals(f.path("session_id").asText()))
                .anyMatch(f -> f.path("data").asText().contains("only-first")))
                .as("输出不得串到另一个会话")
                .isFalse();
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private void build(SshTargetResolver resolver) throws Exception {
        if (ws != null) {
            handler.afterConnectionClosed(ws, CloseStatus.NORMAL);
        }
        SshProperties properties = fastProperties();
        registry = new TerminalSessionRegistry();
        recorder = new InMemorySessionRecorder();
        SshTerminalService terminalService = new SshTerminalService(
                new SshConnectionService(properties), properties, registry, resolver, recorder);
        handler = new TerminalWebSocketHandler(terminalService, mapper);
        ws = new FakeWebSocketSession();
        cursor = 0;
        handler.afterConnectionEstablished(ws);
    }

    /**
     * 快失败配置。
     * WHY 不用生产默认值：{@code connectTimeout} 默认 15 秒，"主机不可达"用例会白等那么久。
     */
    private static SshProperties fastProperties() {
        SshProperties properties = new SshProperties();
        properties.setConnectTimeout(Duration.ofSeconds(3));
        properties.setExecTimeout(Duration.ofSeconds(2));
        properties.setTerminalIdleTimeout(Duration.ofMinutes(5));
        properties.setPtyTerm("xterm-256color");
        properties.setPtyColumns(80);
        properties.setPtyRows(24);
        // WHY 声明非 bash：FakeSshServer 的 shell 不是真 bash，若走默认 shellType=bash，
        // open 接线会把钩子安装代码当命令逐行回显 "command not found"，污染
        // open 回执帧的顺序断言。install() 对不支持的类型不向 PTY 写任何数据，
        // 降级人工终端正是预期行为；集成安装路径由 ShellIntegrationInstallerTest 覆盖
        properties.setShellType("generic");
        return properties;
    }

    private static SshTarget workingTarget() {
        return SshTarget.of("127.0.0.1", fake.port(),
                FakeSshServer.USERNAME, SshAuthMethod.password(FakeSshServer.PASSWORD));
    }

    /** 取一个当前无监听的端口：先占用再释放，释放后它极大概率仍然空着。 */
    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("无法分配测试端口", e);
        }
    }

    /** open 一个终端并返回其 session_id，即 Q1 回执帧所携带的那个。 */
    private String openTerminal() throws Exception {
        send(openFrame());
        JsonNode receipt = nextFrame("open 的回执帧", frame ->
                "data".equals(type(frame)) && frame.hasNonNull("session_id"));
        return receipt.get("session_id").asText();
    }

    private ObjectNode frame() {
        return mapper.createObjectNode();
    }

    private ObjectNode openFrame() {
        return frame().put("action", "open").put("host_id", hostId.toString());
    }

    private ObjectNode inputFrame(String sessionId, String data) {
        return frame().put("action", "input").put("session_id", sessionId).put("data", data);
    }

    private ObjectNode closeFrame(String sessionId) {
        return frame().put("action", "close").put("session_id", sessionId);
    }

    private void send(ObjectNode frame) throws Exception {
        handler.handleMessage(ws, new TextMessage(mapper.writeValueAsString(frame)));
    }

    private List<JsonNode> frames() {
        return ws.sentPayloads().stream().map(this::parse).toList();
    }

    private JsonNode parse(String payload) {
        try {
            return mapper.readTree(payload);
        } catch (IOException e) {
            throw new AssertionError("发出的帧不是合法 JSON: " + payload, e);
        }
    }

    private static String type(JsonNode frame) {
        return frame.path("type").asText();
    }

    /**
     * 轮询直到<b>游标之后</b>出现满足条件的帧，返回它并把游标推进到其后。
     *
     * <p>WHY 轮询：输出经 stdout/stderr 两条读泵线程异步到达，固定睡眠既可能不够又拖慢构建。</p>
     *
     * <p>WHY 必须带游标、不能每次从头扫：帧是<b>累积</b>的。第二次 open 时，
     * 第一次的回执帧同样满足"data 且带 session_id"，从头扫就会把它当成新回执返回——
     * 于是"两次 open 应分配到不同 session_id"这条断言必然失败，而失败点看起来像生产 bug。
     * 更隐蔽的是假阳性：若某个等待的谓词恰好也匹配更早的帧，断言会在
     * "被测帧根本没产生"的情况下通过。游标把"我在等哪一帧"变成显式状态，两类错误同时消失。</p>
     */
    private JsonNode nextFrame(String what, Predicate<JsonNode> match) {
        try {
            until(what, () -> indexOfNext(match) >= 0);
        } catch (AssertionError e) {
            // WHY 超时才把已收到的帧打出来：正常路径不需要它，失败时它是唯一的线索
            throw new AssertionError(what + "；实际收到的帧=" + ws.sentPayloads(), e);
        }
        int index = indexOfNext(match);
        cursor = index + 1;
        return frames().get(index);
    }

    /** @return 游标之后第一个匹配帧的下标；没有则为 -1 */
    private int indexOfNext(Predicate<JsonNode> match) {
        List<JsonNode> all = frames();
        for (int i = cursor; i < all.size(); i++) {
            if (match.test(all.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
