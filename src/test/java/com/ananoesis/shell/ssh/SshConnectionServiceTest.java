package com.ananoesis.shell.ssh;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.contract.model.ErrorCode;
import com.ananoesis.shell.support.FakeSshServer;
import static com.ananoesis.shell.support.TestWait.until;

import net.schmizz.sshj.SSHClient;

/**
 * tasks 6.1 / 6.2：SSH 连接与认证。
 *
 * <p>覆盖 ssh-connection spec「建立 SSH 连接」的全部四个 Scenario：
 * 密码认证成功、私钥（含 passphrase）认证成功、认证失败、主机不可达。</p>
 *
 * <p>WHY 用 {@link FakeSshServer} 而不是 mock {@code SSHClient}：
 * 6.1 的风险不在"我们有没有调用 authPassword"，而在"sshj 与我们送出的凭据形态是否真的匹配"
 * （PKCS#8 PEM 解析、passphrase 解密、字符编码）。mock 掉 SSHClient 会让这些真实风险
 * 全部消失，测试变成对自己代码的复述。对真实 SSH 协议栈做端到端才是有意义的验证。</p>
 *
 * <p>WHY 6.2 要断言"不泄露内部细节"：spec 明确 MUST NOT 暴露服务器内部细节。
 * 认证失败的异常里若带着远端 banner、主机密钥指纹或栈信息并被直接回显给前端，
 * 就等于把内网拓扑送给了任何能看到界面的人。因此断言不只检查错误码，
 * 还检查面向用户的文案是**固定**的、且不含我们送出的错误凭据原文。</p>
 */
class SshConnectionServiceTest {

    /** 刻意选一个绝不会偶然出现在数据里的字符串，使"诊断信息里没有它"成为强断言。 */
    private static final String WRONG_PASSPHRASE = "Wrong-P@ssphrase-DO-NOT-LOG-2b7c";

    private static FakeSshServer fake;
    private static BlackHoleServer blackHole;

    private SshConnectionService service;

    @BeforeAll
    static void startFakeServer() throws IOException {
        fake = FakeSshServer.start();
        blackHole = BlackHoleServer.start();
    }

    @AfterAll
    static void stopFakeServer() throws IOException {
        blackHole.close();
        fake.close();
    }

    @BeforeEach
    void newService() {
        service = new SshConnectionService(SshPropertiesFixture.fast());
    }

    // ==================================================================
    // 6.1 认证成功
    // ==================================================================

    @Test
    @DisplayName("密码认证：建立会话后 exec 通道可用")
    void passwordAuthenticationSucceeds() throws IOException {
        try (SSHClient client = service.connect(passwordTarget(FakeSshServer.PASSWORD))) {
            assertThat(client.isConnected()).as("连接应处于已连接状态").isTrue();
            assertThat(client.isAuthenticated()).as("应已完成认证").isTrue();
            assertThat(runWhoami(client)).as("认证后应能执行命令").isEqualTo("ops");
        }
    }

    @Test
    @DisplayName("私钥认证（未加密 PKCS#8 PEM，无 passphrase）")
    void privateKeyAuthenticationSucceeds() throws IOException {
        SshTarget target = SshTarget.of("127.0.0.1", fake.port(), FakeSshServer.USERNAME,
                SshAuthMethod.privateKey(fake.privateKeyPem(), null));

        try (SSHClient client = service.connect(target)) {
            assertThat(client.isAuthenticated()).isTrue();
            assertThat(runWhoami(client)).isEqualTo("ops");
        }
    }

    @Test
    @DisplayName("私钥认证（加密 PKCS#8 PEM + passphrase）——内存解密，私钥不落盘")
    void privateKeyWithPassphraseAuthenticationSucceeds() throws IOException {
        SshTarget target = SshTarget.of("127.0.0.1", fake.port(), FakeSshServer.USERNAME,
                SshAuthMethod.privateKey(fake.encryptedPrivateKeyPem(FakeSshServer.PASSPHRASE),
                        FakeSshServer.PASSPHRASE));

        try (SSHClient client = service.connect(target)) {
            assertThat(client.isAuthenticated()).as("带口令的私钥应能解密并认证成功").isTrue();
            assertThat(runWhoami(client)).isEqualTo("ops");
        }
    }

    // ==================================================================
    // 6.2 认证失败
    // ==================================================================

    @Test
    @DisplayName("密码错误 → auth_failed，文案固定为「认证失败」")
    void wrongPasswordIsClassifiedAsAuthFailed() {
        String wrongPassword = "Definitely-Wrong-P@ssw0rd";

        SshConnectException exception = catchThrowableOfType(SshConnectException.class,
                () -> service.connect(passwordTarget(wrongPassword)));

        assertThat(exception).as("应抛出受控的 SshConnectException 而非裸 IOException").isNotNull();
        assertThat(exception.kind()).isEqualTo(SshFailureKind.AUTH_FAILED);
        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(exception.userMessage()).isEqualTo("认证失败");
    }

    @Test
    @DisplayName("用户名不存在 → auth_failed（与密码错误不可区分，避免枚举账号）")
    void unknownUsernameIsClassifiedAsAuthFailed() {
        SshTarget target = SshTarget.of("127.0.0.1", fake.port(), "no-such-user",
                SshAuthMethod.password(FakeSshServer.PASSWORD));

        SshConnectException exception = catchThrowableOfType(SshConnectException.class,
                () -> service.connect(target));

        assertThat(exception).isNotNull();
        assertThat(exception.errorCode()).as("账号不存在与密码错误必须给出同样的响应").isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(exception.userMessage()).isEqualTo("认证失败");
    }

    @Test
    @DisplayName("passphrase 错误 → auth_failed")
    void wrongPassphraseIsClassifiedAsAuthFailed() {
        SshTarget target = SshTarget.of("127.0.0.1", fake.port(), FakeSshServer.USERNAME,
                SshAuthMethod.privateKey(fake.encryptedPrivateKeyPem(FakeSshServer.PASSPHRASE),
                        WRONG_PASSPHRASE));

        SshConnectException exception = catchThrowableOfType(SshConnectException.class,
                () -> service.connect(target));

        assertThat(exception).isNotNull();
        assertThat(exception.errorCode()).isEqualTo(ErrorCode.AUTH_FAILED);
    }

    @Test
    @DisplayName("passphrase 错误：诊断信息指向「口令解不开」而非「格式不认识」（对外仍是 auth_failed）")
    void wrongPassphraseDetailPointsAtDecryptionNotFormat() {
        SshTarget target = SshTarget.of("127.0.0.1", fake.port(), FakeSshServer.USERNAME,
                SshAuthMethod.privateKey(fake.encryptedPrivateKeyPem(FakeSshServer.PASSPHRASE),
                        WRONG_PASSPHRASE));

        SshConnectException exception = catchThrowableOfType(SshConnectException.class,
                () -> service.connect(target));

        assertThat(exception).isNotNull();
        assertThat(exception.errorCode())
                .as("错误码不变：对用户而言仍是「这把私钥用不了」")
                .isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(exception.userMessage())
                .as("对外文案必须固定，不随内部原因变化")
                .isEqualTo("认证失败");

        // WHY 要断诊断信息：getMessage() 会进后端日志与 sessions.error_message，
        // 是排障时唯一的线索。报成"无法解析为任何受支持的格式"会把人引向
        // "重新导出私钥"这条错路，而真正要改的是 passphrase——
        // 两种原因的错误码相同、用户文案相同，但**修复动作完全相反**。
        assertThat(exception.getMessage())
                .as("诊断信息应指出是口令解密失败")
                .contains("口令");
        assertThat(exception.getMessage())
                .as("不得再报「格式不认识」，那与真实原因无关")
                .doesNotContain("无法解析为任何受支持的格式");
        assertThat(exception.getMessage())
                .as("诊断信息 MUST NOT 含明文口令（credential-store spec）")
                .doesNotContain(WRONG_PASSPHRASE);
    }

    @Test
    @DisplayName("认证失败的对外文案不泄露错误凭据与内部细节")
    void authFailureDoesNotLeakCredentialsOrInternals() {
        String wrongPassword = "Leaky-P@ssw0rd-Should-Not-Appear";

        SshConnectException exception = catchThrowableOfType(SshConnectException.class,
                () -> service.connect(passwordTarget(wrongPassword)));

        assertThat(exception).isNotNull();
        // WHY 同时查 userMessage 与 getMessage：前者是给前端的，后者会进日志与审计。
        // 面向用户的文案里绝不能出现凭据；服务端日志里出现异常类型可以，但也绝不该带上明文口令。
        assertThat(exception.userMessage()).doesNotContain(wrongPassword);
        assertThat(exception.getMessage()).doesNotContain(wrongPassword);
        assertThat(exception.userMessage())
                .as("对外文案必须是固定短语，不得夹带远端 banner / 主机密钥 / 端口等内部细节")
                .doesNotContain("127.0.0.1")
                .doesNotContain("SSH")
                .doesNotContain("MINA");
    }

    @Test
    @DisplayName("缺少凭据（私钥为空）→ 明确的可操作错误，而非 500")
    void missingCredentialIsClassifiedAsCredentialMissing() {
        SshTarget target = SshTarget.of("127.0.0.1", fake.port(), FakeSshServer.USERNAME,
                SshAuthMethod.privateKey("   ", null));

        SshConnectException exception = catchThrowableOfType(SshConnectException.class,
                () -> service.connect(target));

        assertThat(exception).isNotNull();
        assertThat(exception.kind()).isEqualTo(SshFailureKind.CREDENTIAL_MISSING);
        assertThat(exception.userMessage()).as("必须告诉用户去哪里补凭据").contains("凭据");
    }

    // ==================================================================
    // 6.2 主机不可达 / 超时
    // ==================================================================

    @Test
    @DisplayName("端口无监听 → host_unreachable，文案「连接失败：主机不可达」")
    void closedPortIsClassifiedAsHostUnreachable() {
        SshTarget target = passwordTargetOnPort(ClosedPortProbe.findClosedPort());

        SshConnectException exception = catchThrowableOfType(SshConnectException.class,
                () -> service.connect(target));

        assertThat(exception).isNotNull();
        assertThat(exception.errorCode()).isEqualTo(ErrorCode.HOST_UNREACHABLE);
        assertThat(exception.userMessage()).isEqualTo("连接失败：主机不可达");
    }

    @Test
    @DisplayName("对端接受 TCP 但不发协议版本 → 在连接超时后按主机不可达上报")
    void unresponsiveEndpointTimesOutAndIsClassifiedAsHostUnreachable() {
        SshConnectionService impatient = new SshConnectionService(
                SshPropertiesFixture.withConnectTimeout(Duration.ofMillis(600)));
        SshTarget target = passwordTargetOnPort(blackHole.port());

        long startedAt = System.nanoTime();
        SshConnectException exception = catchThrowableOfType(SshConnectException.class,
                () -> impatient.connect(target));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(exception).as("spec 要求超时后给出明确原因，而不是永久挂起").isNotNull();
        assertThat(exception.errorCode()).isEqualTo(ErrorCode.HOST_UNREACHABLE);
        assertThat(exception.userMessage()).isEqualTo("连接失败：主机不可达");
        assertThat(exception.kind())
                .as("内部要能区分「拒绝连接」与「超时」，对外则统一为主机不可达")
                .isEqualTo(SshFailureKind.CONNECT_TIMEOUT);
        assertThat(elapsedMillis)
                .as("必须在配置的超时附近返回，而不是等到 OS 默认的 ~21 秒")
                .isLessThan(8_000L);
    }

    @Test
    @DisplayName("域名无法解析 → host_unreachable")
    void unresolvableHostnameIsClassifiedAsHostUnreachable() {
        SshTarget target = SshTarget.of("host-that-does-not-exist.invalid", 22,
                FakeSshServer.USERNAME, SshAuthMethod.password(FakeSshServer.PASSWORD));

        SshConnectException exception = catchThrowableOfType(SshConnectException.class,
                () -> service.connect(target));

        assertThat(exception).isNotNull();
        assertThat(exception.errorCode()).isEqualTo(ErrorCode.HOST_UNREACHABLE);
    }

    @Test
    @DisplayName("连接失败时不留半成品：抛出异常即无可用连接")
    void failedConnectLeavesNoOpenClient() {
        SshTarget target = passwordTargetOnPort(ClosedPortProbe.findClosedPort());

        assertThatThrownBy(() -> service.connect(target)).isInstanceOf(SshConnectException.class);
        // WHY 值得断言：连接失败路径最容易漏掉 close()，
        // 结果是每次失败都泄漏一条 socket 与一个读线程——在桌面应用里表现为越用越卡。
        // WHY 用 until 而不是立即断言：MINA 把会话从 activeSessions 摘除是异步的
        // （要等客户端 socket 关闭事件回传到服务端）。立即断言会偶发红；
        // 轮询等待既抗抖动，又仍然能抓住真泄漏——真泄漏时它永远不归零，until 会超时失败。
        until("失败的连接不应在服务端留下会话", () -> fake.activeSessionCount() == 0);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static SshTarget passwordTarget(String password) {
        return passwordTargetOnPort(fake.port(), password);
    }

    private static SshTarget passwordTargetOnPort(int port) {
        return passwordTargetOnPort(port, FakeSshServer.PASSWORD);
    }

    private static SshTarget passwordTargetOnPort(int port, String password) {
        return SshTarget.of("127.0.0.1", port, FakeSshServer.USERNAME, SshAuthMethod.password(password));
    }

    private static String runWhoami(SSHClient client) throws IOException {
        try (var session = client.startSession(); var command = session.exec("whoami")) {
            String output = new String(command.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            command.join(5, java.util.concurrent.TimeUnit.SECONDS);
            return output.trim();
        }
    }

    /**
     * 「黑洞」TCP 服务器：接受连接但永不说一句话。
     * WHY 需要它：连不上的端口会立刻被 OS 拒绝（ConnectException），
     * 那是"不可达"而不是"超时"。要验证超时路径，必须有一个
     * TCP 层握手成功、SSH 层却沉默的对端。
     */
    private static final class BlackHoleServer implements AutoCloseable {

        private final ServerSocket socket;
        private final List<Socket> held = new ArrayList<>();
        private final Thread acceptor;
        private volatile boolean closed;

        private BlackHoleServer(ServerSocket socket) {
            this.socket = socket;
            this.acceptor = new Thread(this::acceptLoop, "black-hole-acceptor");
            this.acceptor.setDaemon(true);
            this.acceptor.start();
        }

        static BlackHoleServer start() throws IOException {
            return new BlackHoleServer(new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1")));
        }

        int port() {
            return socket.getLocalPort();
        }

        private void acceptLoop() {
            while (!closed) {
                try {
                    Socket client = socket.accept();
                    // 刻意不读不写：让客户端在等待 SSH 版本串时超时
                    held.add(client);
                } catch (IOException e) {
                    return;
                }
            }
        }

        @Override
        public void close() throws IOException {
            closed = true;
            for (Socket client : held) {
                client.close();
            }
            socket.close();
        }
    }

    /** 找一个当前无人监听的端口：绑定后立刻释放，随后连接它必然被拒绝。 */
    private static final class ClosedPortProbe {
        static int findClosedPort() {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new IllegalStateException("无法分配测试端口", e);
            }
        }
    }
}

/**
 * 测试用配置：把超时压到足够短，避免一次失败要等 OS 默认的二十多秒。
 * WHY 独立成一个类而不是散落在各测试里：三个 SSH 测试类都要用同样的"快失败"配置，
 * 集中一处才能保证它们的超时语义一致。
 */
final class SshPropertiesFixture {

    private SshPropertiesFixture() {
    }

    static com.ananoesis.shell.config.SshProperties fast() {
        return withConnectTimeout(Duration.ofSeconds(3));
    }

    static com.ananoesis.shell.config.SshProperties withConnectTimeout(Duration connectTimeout) {
        var properties = new com.ananoesis.shell.config.SshProperties();
        properties.setConnectTimeout(connectTimeout);
        properties.setExecTimeout(Duration.ofSeconds(2));
        properties.setExecMaxOutputBytes(65_536);
        properties.setTerminalIdleTimeout(Duration.ofMinutes(5));
        properties.setPtyTerm("xterm-256color");
        properties.setPtyColumns(80);
        properties.setPtyRows(24);
        return properties;
    }
}
