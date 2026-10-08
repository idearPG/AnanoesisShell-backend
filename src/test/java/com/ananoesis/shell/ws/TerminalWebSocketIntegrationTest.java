package com.ananoesis.shell.ws;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.ananoesis.shell.AbstractSqliteIntegrationTest;
import com.ananoesis.shell.config.WebSocketConfiguration;
import com.ananoesis.shell.contract.model.AuthType;
import com.ananoesis.shell.contract.model.EndReason;
import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.contract.model.Host;
import com.ananoesis.shell.contract.model.Session;
import com.ananoesis.shell.contract.model.SessionStatus;
import com.ananoesis.shell.support.FakeSshServer;
import static com.ananoesis.shell.support.TestWait.until;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code /ws/terminal} 的端到端验收（tasks 6.1 / 6.3 / 6.6，Q1 裁定）。
 *
 * <p>WHY 需要这一层，尽管 {@code TerminalWebSocketHandlerTest}（替身）和
 * {@code SshTerminalServiceTest}（真 SSH）都已覆盖各自的职责：那两个测试分别证明
 * "协议翻译正确"和 SSH 行为正确"，但<b>没有</b>证明三件只在真实容器里才成立的事：</p>
 * <ol>
 *   <li>端点确实在 Spring 挂载在 {@code WebSocketConfiguration.TERMINAL_ENDPOINT} 上
 *       ——常量对得上契约，与"容器真的在这个路径上监听"是两个命题；</li>
 *   <li>Origin 同源限定真的生效（{@code OriginHandshakeInterceptor} 和
 *       {@code AbstractWebSocketHandlerRegistration} 无条件注入，但"注入"不等于"拦得住"）；</li>
 *   <li>主机凭据经 REST 落库（密文）到 SSH 握手（内存解密）这条<b>完整链路</b>可用。
 *       本测试的主机一律经 {@code POST /api/hosts} 创建，因此走的是真实的
 *       {@code CredentialStoreService} 加密和 {@code CredentialBackedSshTargetResolver} 解密。</li>
 * </ol>
 *
 * <p>WHY 入站帧用 {@link TerminalInput} 序列化、出站帧反序列化为 {@link TerminalOutput}
 * 而不是手写 JSON 字符串：手写的字段名一旦拼错（{@code hostId} vs {@code host_id}），
 * 测试会以自己的错误前提去断言生产代码，红得莫名其妙、绿得毫无意义。
 * 复用生产类型让线上名字"只有一个来源"。</p>
 *
 * <p>WHY 内嵌 SSH 服务器用 {@code @BeforeAll} 静态共享：每个用例起停一次 SSHD 要额外
 * 生成主机密钥并绑定端口，几十个用例累计数十秒；共享一个实例后由每个用例创建<b>独立主机
 * 来隔离数据（沿用 {@code HostsApiIntegrationTest} 的纪律）。</p>
 *
 * <p>WHY 强制 shell-type=generic：{@code FakeSshServer} 的 MiniShell 不是真 bash，若走默认
 * bash 类型会在 open 链路上同步安装 Shell 集成钩子（含 sleep 防抖），产生两类问题：
 * 安装代码被 MiniShell 当未知命令逐行回显污染帧序断言；更致命的是 sleep 把 open 阻塞
 * 到握手完成后百毫秒级，测试客户端在 open 回执前关闭 WS 时，afterConnectionClosed
 * 先于 runtime attach 执行（released=0），会话泄漏进已死连接导致审计状态永停 open
 * （known-issues #12 同源预防：非 bash 替身必须配非 bash shellType）。真实 bash
 * 安装路径由 {@code ShellIntegrationInstallerTest} 覆盖。</p>
 */
@TestPropertySource(properties = "ananoesis.ssh.shell-type=generic")
class TerminalWebSocketIntegrationTest extends AbstractSqliteIntegrationTest {

    /** 握手与单帧等待的上限。WHY 比 {@code TestWait.DEFAULT_TIMEOUT} 短：握手是本地回环，超过 15s 一定是坏了。 */
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(15);

    /** 认证失败用例里刻意写错的密码；断言"它不会出现在错误帧里"。 */
    private static final String WRONG_PASSWORD = "Wr0ng-P@ssw0rd-DO-NOT-LEAK-8c41";

    private static FakeSshServer fake;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ObjectMapper objectMapper;

    /** 被测处理器本体；用它断言"连接登记数回到基线"，即没有连接泄漏。 */
    @Autowired
    private TerminalWebSocketHandler handler;

    @BeforeAll
    static void startFakeSshServer() throws IOException {
        fake = FakeSshServer.start();
    }

    @AfterAll
    static void stopFakeSshServer() {
        fake.close();
    }

    // ==================================================================
    // Q1 握手 + 按键转发 + 主动断开 + 审计（tasks 6.1 / 6.3 / 6.6）
    // ==================================================================

    @Test
    @DisplayName("Q1 全链路：open→session_id 回执→input 转发→close→closed 帧，sessions 表记为 user_disconnect")
    void openInputCloseRoundTripIsAudited() throws Exception {
        UUID hostId = createHost("ws-roundtrip", fake.password());
        int connectionsBefore = handler.connectionCount();
        int sshSessionsBefore = fake.activeSessionCount();

        try (TerminalClient client = connect(null)) {
            client.send(new TerminalInput(TerminalInput.Action.OPEN, hostId, null, null, null, null, null));

            // WHY 回执必须在"一个字节都没敲"时就到达：把 session_id 寄托在第一条真实输出上，
            // 遇到不打印提示符的远端（本替身正是如此）前端将永远拿不到 id，既不能敲键也不能关闭。
            TerminalOutput ack = client.nextFrame();
            assertThat(ack.type()).as("Q1：open 的回执应为 data 帧（契约没有 opened 类型）")
                    .isEqualTo(TerminalOutput.Type.DATA);
            assertThat(ack.sessionId()).as("Q1：回执必须携带后端分配的 session_id").isNotNull();
            UUID sessionId = ack.sessionId();

            client.send(new TerminalInput(TerminalInput.Action.INPUT, null, sessionId, "echo ws-roundtrip-ok\r", null, null, null));
            until("远端回显应经 PTY 流回前端",
                    () -> client.dataSoFar().contains("ws-roundtrip-ok"));

            until("服务端应看到一条活跃 SSH 会话", () -> fake.activeSessionCount() > sshSessionsBefore);

            client.send(new TerminalInput(TerminalInput.Action.CLOSE, null, sessionId, null, null, null, null));
            TerminalOutput closed = client.awaitClosed();
            assertThat(closed.sessionId()).as("closed 帧必须指明是哪条会话结束").isEqualTo(sessionId);
            assertThat(closed.endReason()).isEqualTo(EndReason.USER_DISCONNECT);

            // WHY "每一帧都有 session_id"：前端靠它把输出分派到正确的终端标签页。
            // 漏一帧，那一帧的输出就会掉进"无主"分支被丢弃，症状是终端偶发丢字符。
            assertThat(client.frames()).allSatisfy(frame ->
                    assertThat(frame.sessionId()).as("所有 terminal_output 帧都应带 session_id").isNotNull());
        }

        // 审计：客户端收到 closed 后 DB 行已更新（SshTerminalSession 在 finally 顺序保证），
        // 但服务端 afterConnectionClosed 与 REST 查询之间仍有微小窗口，故用轮询。
        Session audited = awaitSingleSession(hostId);
        assertThat(audited.getStatus()).isEqualTo(SessionStatus.ENDED);
        assertThat(audited.getEndReason()).isEqualTo(EndReason.USER_DISCONNECT);
        assertThat(audited.getEndedAt()).as("结束时间必须落库").isNotNull();
        assertThat(audited.getHostLabel()).as("host_label 即 hosts.name").isEqualTo("ws-roundtrip");

        until("SSH 会话应被对端确认关闭", () -> fake.activeSessionCount() <= sshSessionsBefore);
        until("WebSocket 连接登记应清零", () -> handler.connectionCount() <= connectionsBefore);
    }

    @Test
    @DisplayName("传输层断开（用户关掉标签页）也会释放 SSH 会话并记为 user_disconnect")
    void abruptWebSocketCloseReleasesSshSession() throws Exception {
        UUID hostId = createHost("ws-abrupt", fake.password());
        int sshSessionsBefore = fake.activeSessionCount();
        TerminalClient client = connect(null);
        try {
            client.send(new TerminalInput(TerminalInput.Action.OPEN, hostId, null, null, null, null, null));
            UUID sessionId = client.nextFrame().sessionId();
            until("服务端应看到一条活跃 SSH 会话", () -> fake.activeSessionCount() > sshSessionsBefore);

            // WHY 不发 action=close 就直接关 WebSocket：用户关掉浏览器标签页时前端来不及发帧。
            // 没有 afterConnectionClosed 的兜底释放，这条 SSH 连接会一直挂到进程退出。
            client.closeWebSocket();
            assertThat(client.awaitClosedLatch()).as("客户端应观察到连接关闭").isTrue();
            assertThat(sessionId).isNotNull();
        } finally {
            client.close();
        }

        Session audited = awaitSingleSession(hostId);
        assertThat(audited.getStatus()).isEqualTo(SessionStatus.ENDED);
        assertThat(audited.getEndReason()).as("传输层断开从用户视角就是主动断开")
                .isEqualTo(EndReason.USER_DISCONNECT);
        until("SSH 会话应被对端确认关闭", () -> fake.activeSessionCount() <= sshSessionsBefore);
    }

    // ==================================================================
    // task 6.2：认证失败经 WS 呈现为 error 帧，且不泄露内部细节
    // ==================================================================

    @Test
    @DisplayName("密码错误 → error 帧 auth_failed，不含密文主机/端口，且 sessions 记为连接期失败")
    void wrongPasswordYieldsAuthFailedFrameWithoutLeakingSecrets() throws Exception {
        UUID hostId = createHost("ws-auth-failed", WRONG_PASSWORD);

        try (TerminalClient client = connect(null)) {
            client.send(new TerminalInput(TerminalInput.Action.OPEN, hostId, null, null, null, null, null));
            TerminalOutput error = client.nextFrame();

            assertThat(error.type()).isEqualTo(TerminalOutput.Type.ERROR);
            assertThat(error.errorCode()).isEqualTo(ErrorCode.AUTH_FAILED);
            assertThat(error.message()).as("必须给用户一句可读的说明").isNotBlank();
            // WHY 逐字断言原始帧而不是解析后的字段：泄露往往发生在多序列化了一个字段上，
            // 解析成 DTO 后那些字段自然不存在，断言恒真。只有原始字节能证明它没被发出去。
            String raw = client.rawFrames().get(0);
            assertThat(raw).doesNotContain(WRONG_PASSWORD);
            assertThat(raw).doesNotContain(String.valueOf(fake.port()));
            assertThat(raw).doesNotContain("UserAuthException");
            assertThat(raw).doesNotContain("sshj");
            assertThat(error.sessionId()).as("open 失败时 session id 可能尚未下发，契约允许为 null").isNull();
        }

        Session audited = awaitSingleSession(hostId);
        assertThat(audited.getStatus()).as("连接期失败也要留下审计行，不得停在 open").isEqualTo(SessionStatus.ENDED);
        assertThat(audited.getEndReason())
                .as("auth_failed 是连接期失败，没有对应的契约 EndReason，应为 null")
                .isNull();
    }

    @Test
    @DisplayName("主机不存在 → error 帧 not_found，且连接保持可用")
    void unknownHostYieldsNotFoundAndKeepsConnectionAlive() throws Exception {
        try (TerminalClient client = connect(null)) {
            client.send(new TerminalInput(TerminalInput.Action.OPEN, UUID.randomUUID(), null, null, null, null, null));
            TerminalOutput error = client.nextFrame();
            assertThat(error.type()).isEqualTo(TerminalOutput.Type.ERROR);
            assertThat(error.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);

            // WHY 还要再发一帧：错误处理最常见的回归是"回完错误顺手把连接关了"，
            // 那会连带废掉用户在同一条连接上打开的其它终端标签页。
            client.send(new TerminalInput(null, null, null, null, null, null, null));
            TerminalOutput validation = client.nextFrame();
            assertThat(validation.errorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
            assertThat(client.session().isOpen()).as("坏帧不得导致连接关闭").isTrue();
        }
    }

    // ==================================================================
    // Origin 同源限定（CSWSH 防线）
    // ==================================================================

    @Test
    @DisplayName("浏览器同源握手被接受：Origin=http://localhost:{port} 能完成 open 回执")
    void sameOriginHandshakeIsAccepted() throws Exception {
        UUID hostId = createHost("ws-same-origin", fake.password());
        // WHY 必须单独验证：真实浏览器**总会**带 Origin 头。若同源被拒，
        // 前端在本机联调时会表现为"WebSocket 连不上"，而后端日志一片干净——极难定位。
        try (TerminalClient client = connect("http://localhost:" + port)) {
            client.send(new TerminalInput(TerminalInput.Action.OPEN, hostId, null, null, null, null, null));
            assertThat(client.nextFrame().sessionId()).as("同源握手应能建立终端").isNotNull();
        }
    }

    @Test
    @DisplayName("跨源握手被拒：Origin 不在允许列表时不建立连接（CSWSH 防护）")
    void foreignOriginHandshakeIsRejected() {
        // WHY 需要这条：/ws/terminal 能驱动用户配置好的 SSH 会话在远端执行任意命令。
        // 放开 Origin 之后，浏览器里任何恶意页面都能连上本机端点并敲命令；
        // WebSocket 握手不走 REST 那套凭据/CSRF 防护，Origin 校验是唯一的闸门。
        TerminalClient client = new TerminalClient(objectMapper);
        CompletableFuture<WebSocketSession> future = handshake(client, "http://evil.example");

        // WHY 用 ExecutionException 而不是某个具体的 WS 异常类型：容器把 403 翻译成什么异常
        // 属于实现细节（Tomcat 和 Jetty 各不相同）。这里要钉住的是"握手没有成功"这一事实，
        // 具体类型写死反而会让换容器时测试假红。
        assertThatThrownBy(() -> future.get(HANDSHAKE_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .as("Origin 不在允许列表时握手必须失败")
                .isInstanceOf(ExecutionException.class);
        assertThat(client.rawFrames()).as("被拒的握手不应收到任何帧").isEmpty();
    }

    @Test
    @DisplayName("跨源握手收到 403（而不是 500 或静默挂起）")
    void foreignOriginHandshakeRespondsForbidden() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://evil.example");
        headers.setUpgrade("websocket");
        headers.setConnection("Upgrade");
        headers.set("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
        headers.set("Sec-WebSocket-Version", "13");

        ResponseEntity<String> response = rest.exchange(WebSocketConfiguration.TERMINAL_ENDPOINT,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        // WHY 用裸 HTTP 再验一次：上面的用例只证明"连不上"，而连不上的原因可能是
        // 路径写错（404）或服务端异常（500）。钉住 403 才能说明是 Origin 校验在起作用。
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /** 经真实 REST 创建主机：凭据走 {@code CredentialStoreService} 加密落库，握手时再内存解密。 */
    private UUID createHost(String note, String password) {
        Host request = new Host("127.0.0.1", fake.port(), fake.username(), AuthType.PASSWORD);
        request.setNote(note);
        request.setPassword(password);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Host> response = rest.postForEntity("/api/hosts",
                new HttpEntity<>(toJson(request), headers), Host.class);

        assertThat(response.getStatusCode()).as("前置：创建主机应成功").isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        UUID id = response.getBody().getId();
        assertThat(id).as("前置：服务端应分配 host id").isNotNull();
        return id;
    }

    private URI endpointUri() {
        return URI.create("ws://localhost:" + port + WebSocketConfiguration.TERMINAL_ENDPOINT);
    }

    private CompletableFuture<WebSocketSession> handshake(TerminalClient client, @Nullable String origin) {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        // WHY 只在需要时设置：不设置 = 不发 Origin 头（原生客户端、服务端到服务端的形态）。
        // 设置 = 浏览器形态。两者在 OriginHandshakeInterceptor 里走不同分支，都要验证。
        if (origin != null) {
            headers.setOrigin(origin);
        }
        return new StandardWebSocketClient().execute(client, headers, endpointUri());
    }

    private TerminalClient connect(@Nullable String origin) throws Exception {
        TerminalClient client = new TerminalClient(objectMapper);
        // WHY 用 future 的返回值绑定会话，而不是等客户端自己的 afterConnectionEstablished：
        // 后者的调用时机由容器决定，绑定与首帧到达之间存在竞态。
        client.bind(handshake(client, origin).get(HANDSHAKE_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        return client;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("无法序列化请求体", e);
        }
    }

    /** 轮询直到该主机名下恰好有一条会话记录，并返回它。 */
    private Session awaitSingleSession(UUID hostId) {
        until("sessions 表应出现该主机的审计行 hostId=" + hostId, () -> sessionsOf(hostId).size() == 1);
        return sessionsOf(hostId).get(0);
    }

    private List<Session> sessionsOf(UUID hostId) {
        Session[] sessions = rest.getForObject("/api/sessions?host_id=" + hostId, Session[].class);
        return sessions == null ? List.of() : List.of(sessions);
    }

    /**
     * 极简 WebSocket 客户端：按到达顺序收集帧，并允许逐帧消费。
     *
     * <p>WHY 手写而不用 Spring 的测试客户端或 Mockito：本项目测试坚持"手写替身优先"。
     * 这里需要的能力只有"收帧 + 发帧 + 关连接"，二十行足够。
     * 而引入一个 DSL 会让"到底在等什么"这件事变得不可见。</p>
     */
    private static final class TerminalClient extends TextWebSocketHandler implements AutoCloseable {

        private final ObjectMapper mapper;
        private final List<String> raw = new CopyOnWriteArrayList<>();
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private final AtomicInteger consumed = new AtomicInteger();
        private final AtomicReference<WebSocketSession> bound = new AtomicReference<>();

        TerminalClient(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        void bind(WebSocketSession session) {
            bound.set(session);
        }

        WebSocketSession session() {
            WebSocketSession session = bound.get();
            if (session == null) {
                throw new IllegalStateException("握手尚未完成");
            }
            return session;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            raw.add(message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closedLatch.countDown();
        }

        void send(TerminalInput input) {
            sendRaw(toJson(input));
        }

        void sendRaw(String payload) {
            try {
                session().sendMessage(new TextMessage(payload));
            } catch (IOException e) {
                throw new IllegalStateException("发送 terminal_input 失败: " + payload, e);
            }
        }

        /** 只关传输层，不发 {@code action=close}——模拟用户直接关掉标签页。 */
        void closeWebSocket() throws IOException {
            WebSocketSession session = bound.get();
            if (session != null && session.isOpen()) {
                session.close(CloseStatus.NORMAL);
            }
        }

        boolean awaitClosedLatch() throws InterruptedException {
            return closedLatch.await(HANDSHAKE_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        /** 已收到的原始帧（按到达顺序）。 */
        List<String> rawFrames() {
            return List.copyOf(raw);
        }

        /** 已收到的全部帧，解析后的形态。 */
        List<TerminalOutput> frames() {
            return raw.stream().map(this::parse).toList();
        }

        /** 到目前为止所有 data 帧拼接出的文本。 */
        String dataSoFar() {
            StringBuilder text = new StringBuilder();
            for (TerminalOutput frame : frames()) {
                if (frame.type() == TerminalOutput.Type.DATA && frame.data() != null) {
                    text.append(frame.data());
                }
            }
            return text.toString();
        }

        /** 取下一帧；超时仍未到达则抛 {@link AssertionError}。 */
        TerminalOutput nextFrame() {
            int index = consumed.get();
            until("应收到第 " + (index + 1) + " 帧 terminal_output", () -> raw.size() > index);
            return parse(raw.get(consumed.getAndIncrement()));
        }

        /** 一直取帧直到出现 closed；期间任何一帧都不是 closed 也无妨（输出帧会先到达）。 */
        TerminalOutput awaitClosed() {
            while (true) {
                TerminalOutput frame = nextFrame();
                if (frame.type() == TerminalOutput.Type.CLOSED) {
                    return frame;
                }
            }
        }

        private TerminalOutput parse(String payload) {
            try {
                return mapper.readValue(payload, TerminalOutput.class);
            } catch (IOException e) {
                // WHY 抛异常而不是返回 null：帧无法解析意味着契约漂移。
                // 静默返回 null 会让断言以 NullPointerException 的形式红掉，掩盖真正的原因。
                throw new IllegalStateException("无法解析 terminal_output: " + payload, e);
            }
        }

        private String toJson(TerminalInput input) {
            try {
                return mapper.writeValueAsString(input);
            } catch (IOException e) {
                throw new IllegalStateException("无法序列化 terminal_input", e);
            }
        }

        @Override
        public void close() throws IOException {
            closeWebSocket();
        }
    }
}
