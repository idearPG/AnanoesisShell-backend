package com.ananoesis.shell.ssh;

import java.io.CharArrayReader;
import java.io.IOException;
import java.io.Reader;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ananoesis.shell.config.SshProperties;
import com.ananoesis.shell.security.SecretText;
import com.hierynomus.sshj.common.KeyDecryptionFailedException;
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;
import net.schmizz.sshj.userauth.UserAuthException;
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile;
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile;
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile;
import net.schmizz.sshj.userauth.password.PasswordFinder;
import net.schmizz.sshj.userauth.password.Resource;

/**
 * SSH 连接与认证（tasks 6.1 / 6.2）。
 *
 * <p>本类是**唯一**创建 {@link SSHClient} 的地方。WHY 要收口：
 * 超时、主机密钥策略、keep-alive、失败分类这四件事任何一件散落到调用点，
 * 都会出现"某条路径忘了设超时"这种在界面上表现为永久转圈的故障。</p>
 *
 * <h2>超时如何生效（实测 sshj 0.40 字节码后的结论）</h2>
 * <ul>
 *   <li>{@code setTimeout(ms)} 只是记录字段，真正 {@code socket.setSoTimeout(ms)} 发生在
 *       {@code SocketClient.onConnect()} 里，因此 MUST 在 {@code connect()} **之前**调用。</li>
 *   <li>版本串交换（{@code TransportImpl.receiveServerIdent}）在 {@code connect()} 内部
 *       **逐字节直读 socket**，没有超时容忍逻辑——所以"对端接受 TCP 却不说话"的黑洞主机
 *       会在这里抛出 {@code SocketTimeoutException}，正好被本类归类为 CONNECT_TIMEOUT。</li>
 *   <li>连接建成后的读循环（{@code net.schmizz.sshj.transport.Reader}）对
 *       {@code SocketTimeoutException} **有异常表兜底**（捕获后继续循环），
 *       而通道数据流 {@code ChannelInputStream} 用的是无超时的 {@code Object.wait()}。
 *       结论：SO_TIMEOUT 不会误杀空闲的交互式终端，无需在建连后再放宽。</li>
 * </ul>
 *
 * <h2>安全约束（credential-store spec）</h2>
 * <ul>
 *   <li>明文凭据只以 {@code char[]} 形态流向 sshj，用毕立即 {@code Arrays.fill(..., '\0')}。</li>
 *   <li>私钥 PEM 经 {@link CharArrayReader} 直接喂给 sshj，<b>全程不落盘</b>。</li>
 *   <li>对外文案取自 {@link SshFailureKind}，是**固定短语**；主机名/端口/异常类型只进服务端日志。</li>
 * </ul>
 */
@Service
public class SshConnectionService {

    private static final Logger LOG = LoggerFactory.getLogger(SshConnectionService.class);

    /**
     * 私钥格式的尝试顺序。
     *
     * <p>WHY 是逐个试而不是先探测格式：sshj 的
     * {@code KeyProviderUtil.detectKeyFileFormat(Reader, boolean)} 的 boolean 语义未文档化，
     * 猜错的代价是"某些用户的私钥永远连不上"。逐个试的代价只是几次内存解析，
     * 且判定标准是**真的读出了密钥对**——比读文件头更可靠。</p>
     *
     * <p>WHY PKCS#8 排第一：{@code openssl pkcs8} 与 Java 自身导出的都是它；
     * OpenSSH v1（{@code -----BEGIN OPENSSH PRIVATE KEY-----}）是 {@code ssh-keygen} 的默认产物，排第二。</p>
     */
    private static final List<Supplier<FileKeyProvider>> KEY_FACTORIES = List.of(
            () -> new PKCS8KeyFile.Factory().create(),
            () -> new OpenSSHKeyV1KeyFile.Factory().create(),
            () -> new OpenSSHKeyFile.Factory().create(),
            () -> new PuTTYKeyFile.Factory().create());

    private final SshProperties properties;

    /** 已就主机密钥风险告警过的 host:port，避免 exec 每次调用都刷一条 WARN。 */
    private final Set<String> hostKeyWarned = ConcurrentHashMap.newKeySet();

    public SshConnectionService(SshProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties 不得为 null");
    }

    /**
     * 建立连接并完成认证。
     *
     * <p>返回的 {@link SSHClient} <b>由调用方负责关闭</b>（它实现了 {@code Closeable}，
     * 推荐 try-with-resources）。失败时本方法保证不留半成品：内部已 {@code disconnect()}。</p>
     *
     * @throws SshConnectException 任何连接/认证失败；{@link SshConnectException#userMessage()} 可直接回显给用户
     */
    public SSHClient connect(SshTarget target) {
        Objects.requireNonNull(target, "target 不得为 null");
        requireCredential(target);

        int timeoutMillis = millisOf(properties.getConnectTimeout());
        SSHClient client = new SSHClient();
        // 顺序有意义：setTimeout 必须在 connect 之前，onConnect() 才会把它落到 socket 上
        client.setConnectTimeout(timeoutMillis);
        client.setTimeout(timeoutMillis);
        client.addHostKeyVerifier(new RecordingHostKeyVerifier(target));
        enableKeepAlive(client);

        try {
            client.connect(target.host(), target.port());
            authenticate(client, target);
            LOG.debug("SSH 连接建立成功: {} 用时上限 {}ms", target, timeoutMillis);
            return client;
        } catch (IOException | RuntimeException e) {
            closeQuietly(client);
            throw classify(target, e);
        }
    }

    // ==================================================================
    // 认证
    // ==================================================================

    private void authenticate(SSHClient client, SshTarget target) throws IOException {
        SshAuthMethod auth = target.auth();
        // WHY 用 instanceof 模式而非 switch 类型模式：本项目以 release=17 编译（见 pom.xml
        // 的 java.version 注释），switch 的类型模式要到 Java 21 才可用
        if (auth instanceof SshAuthMethod.Password password) {
            authByPassword(client, target.username(), password);
        } else if (auth instanceof SshAuthMethod.PrivateKey key) {
            client.authPublickey(target.username(), keyProviderOf(key));
        } else {
            throw new SshConnectException(SshFailureKind.CREDENTIAL_MISSING,
                    "不支持的认证方式: " + auth.getClass().getSimpleName());
        }
    }

    private static void authByPassword(SSHClient client, String username, SshAuthMethod.Password password)
            throws IOException {
        char[] secret = password.password().revealChars();
        try {
            client.authPassword(username, secret);
        } finally {
            // WHY 自己擦而不管 sshj 是否已经擦过：把"明文不留在堆上"变成自己的不变量，
            // 不依赖第三方库的实现细节（它是否擦、何时擦都可能随版本变化）
            Arrays.fill(secret, '\0');
        }
    }

    /**
     * 把内存中的 PEM 解析成 sshj 的 {@link KeyProvider}。
     *
     * @throws SshConnectException 私钥用不了时一律归为 AUTH_FAILED——"格式不认识"与
     *                             "口令解不开"对用户是同一件事（这把私钥用不了），
     *                             因此对外的错误码与文案都相同，不泄露实现细节。
     *                             但**诊断信息必须区分二者**，理由见 {@code decryptionFailure}。
     */
    private KeyProvider keyProviderOf(SshAuthMethod.PrivateKey key) {
        PasswordFinder finder = key.passphrase() == null ? null : new SingleShotPasswordFinder(key.passphrase());
        Exception lastFailure = null;
        // WHY 单独记住"解密失败"，而不是只看 lastFailure：本方法依次尝试四种格式，
        // lastFailure 留下的是**最后一种**格式的失败（通常是"这不是 PuTTY 私钥"之类），
        // 而真正的原因发生在前面某个已经认出格式的解析器上。按 lastFailure 报告，
        // 用户会被告知"格式不支持"从而去重新导出私钥——可真正要改的是 passphrase。
        // 两种原因的错误码与用户文案完全相同，但**修复动作相反**，误导的代价很高。
        Exception decryptionFailure = null;

        for (Supplier<FileKeyProvider> factory : KEY_FACTORIES) {
            FileKeyProvider provider = factory.get();
            char[] pem = key.privateKey().revealChars();
            try (Reader reader = new CharArrayReader(pem)) {
                if (finder == null) {
                    provider.init(reader);
                } else {
                    provider.init(reader, finder);
                }
                // 强制解析：只有真的读出密钥对才算这种格式匹配。
                // 若只 init 不解析，"格式认对但口令错"会被误判为"格式不对"而继续试下一种，
                // 最终报出的原因就与真实原因无关了。
                PrivateKey privateKey = provider.getPrivate();
                PublicKey publicKey = provider.getPublic();
                if (privateKey == null || publicKey == null) {
                    throw new IOException("私钥解析结果为空（privateKey=" + privateKey + ", publicKey=" + publicKey + "）");
                }
                LOG.debug("私钥解析成功: format={}", provider.getClass().getSimpleName());
                return provider;
            } catch (Exception e) {
                lastFailure = e;
                if (decryptionFailure == null && isDecryptionFailure(e)) {
                    decryptionFailure = e;
                }
            } finally {
                Arrays.fill(pem, '\0');
            }
        }

        if (decryptionFailure != null) {
            // WHY 不把 sshj 的原文拼进 detail：它可能带密钥来源路径等环境细节，
            // 而 detail 会进 sessions.error_message，属对外可见范围。原文只作为 cause 进日志。
            throw new SshConnectException(SshFailureKind.AUTH_FAILED,
                    "私钥格式已识别，但无法用给定口令解密（passphrase 可能不正确）", decryptionFailure);
        }
        throw new SshConnectException(SshFailureKind.AUTH_FAILED,
                "私钥无法解析为任何受支持的格式（已尝试 PKCS#8 / OpenSSH v1 / OpenSSH / PuTTY）", lastFailure);
    }

    /** cause 链的遍历深度上界。 */
    private static final int MAX_CAUSE_DEPTH = 16;

    /**
     * 这次解析失败是否属于"格式认出来了、但口令解不开"。
     *
     * <p>WHY 沿 cause 链查找而不只看顶层类型：sshj 在不同格式下的抛出位置不同
     * （字节码取证：{@code PKCS8KeyFile} 直接构造 {@code KeyDecryptionFailedException}，
     * {@code OpenSSHKeyV1KeyFile} 则由底层 {@code IOException} 包装后再抛），
     * 未来版本也可能再包一层。按链查找对这几种形态同时成立。</p>
     *
     * <p>WHY 要有深度上界：{@code Throwable} 允许构造出 cause 环
     * （{@code new IOException(a)} 之后再 {@code a.initCause(b)}），
     * 而本方法跑在建连的关键路径上，一个环就是一次挂死。正常的链深度不超过 3。</p>
     */
    private static boolean isDecryptionFailure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof KeyDecryptionFailedException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 只回答一次口令、且明确拒绝重试的 {@link PasswordFinder}。
     *
     * <p>WHY {@code shouldRetry} 恒为 false：sshj 在口令错误时会回调它询问"要不要再试一次"，
     * 语义是给交互式终端提示用户重新输入用的。我们跑在无终端的服务端，
     * 返回 true 只会让一次认证失败变成 N 次无意义的往返，拖慢失败反馈。</p>
     */
    private static final class SingleShotPasswordFinder implements PasswordFinder {

        private final SecretText passphrase;

        SingleShotPasswordFinder(SecretText passphrase) {
            this.passphrase = passphrase;
        }

        @Override
        public char[] reqPassword(Resource<?> resource) {
            return passphrase.revealChars();
        }

        @Override
        public boolean shouldRetry(Resource<?> resource) {
            return false;
        }
    }

    // ==================================================================
    // 主机密钥
    // ==================================================================

    /**
     * 接受任意主机密钥，但把风险**说出来**。
     *
     * <p><b>已知安全缺口（须在后续 Wave 由架构裁定后补齐）</b>：
     * 契约与 {@code hosts} 表都没有存放主机密钥指纹的字段，因此本项目当前无法做
     * TOFU（首次信任）或 known_hosts 校验，等价于 sshj 的 {@code PromiscuousVerifier}——
     * 存在中间人风险。之所以不静默使用 {@code PromiscuousVerifier}：
     * 那样这个缺口在代码里完全不可见，评审与运维都无从得知。
     * 本类把它显式化，并在每个 host:port 首次连接时打一条 WARN 留痕。</p>
     */
    private final class RecordingHostKeyVerifier implements HostKeyVerifier {

        private final SshTarget target;

        RecordingHostKeyVerifier(SshTarget target) {
            this.target = target;
        }

        @Override
        public boolean verify(String hostname, int port, PublicKey key) {
            String endpoint = hostname + ":" + port;
            if (hostKeyWarned.add(endpoint)) {
                LOG.warn("未校验主机密钥指纹（本项目尚未实现 TOFU/known_hosts，存在中间人风险）: endpoint={} algorithm={}",
                        endpoint, key == null ? "unknown" : key.getAlgorithm());
            } else {
                LOG.debug("跳过主机密钥校验（已告警过）: endpoint={}", endpoint);
            }
            return true;
        }

        @Override
        public List<String> findExistingAlgorithms(String hostname, int port) {
            // 返回空表示"没有已知记录"，与 verify 恒真一致
            LOG.trace("主机密钥算法查询: target={}", target);
            return List.of();
        }
    }

    private void enableKeepAlive(SSHClient client) {
        int seconds = (int) properties.getKeepAliveInterval().toSeconds();
        if (seconds <= 0) {
            return;
        }
        // MUST 在 connect() 之前：SSHClient.onConnect() 只在 isEnabled() 为真时才启动心跳线程，
        // 连上再设间隔等于白设
        client.getConnection().getKeepAlive().setKeepAliveInterval(seconds);
    }

    // ==================================================================
    // 失败分类
    // ==================================================================

    private static void requireCredential(SshTarget target) {
        SshAuthMethod auth = target.auth();
        if (auth instanceof SshAuthMethod.Password password) {
            requireNonBlank(password.password(), "密码");
        } else if (auth instanceof SshAuthMethod.PrivateKey key) {
            requireNonBlank(key.privateKey(), "私钥");
        } else {
            throw new SshConnectException(SshFailureKind.CREDENTIAL_MISSING,
                    "不支持的认证方式: " + auth.getClass().getSimpleName());
        }
    }

    /**
     * 判定凭据是否为空白。
     * WHY 用 {@code char[]} 而不是 {@code revealAsString().isBlank()}：
     * 后者会造出一份**不可擦除**的 String 明文，只为了判空而永久留在堆上，代价太高。
     */
    private static void requireNonBlank(SecretText secret, String what) {
        char[] value = secret.revealChars();
        try {
            boolean blank = true;
            for (char c : value) {
                if (!Character.isWhitespace(c)) {
                    blank = false;
                    break;
                }
            }
            if (blank) {
                throw new SshConnectException(SshFailureKind.CREDENTIAL_MISSING, "服务器" + what + "为空或未配置");
            }
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    /**
     * 把底层异常归类成受控异常。
     *
     * <p>WHY 遍历整条 cause 链：sshj 会把 {@code SocketTimeoutException} 包进
     * {@code TransportException}，只看最外层类型会一律落到 INTERNAL，
     * 于是"主机不可达"这个 spec 明确要求的提示永远出不来。</p>
     *
     * <p>WHY 日志只打 {@code cause.toString()} 而 WARN 级别不打栈：
     * 认证失败的栈对排障没有价值却极其吵；完整栈留在 DEBUG，需要时才开。</p>
     */
    private SshConnectException classify(SshTarget target, Exception cause) {
        if (cause instanceof SshConnectException alreadyClassified) {
            return alreadyClassified;
        }
        SshFailureKind kind = kindOf(cause);
        String detail = "SSH 连接失败: " + target + " kind=" + kind;
        LOG.warn("{}: {}", detail, String.valueOf(cause));
        LOG.debug("SSH 连接失败完整栈: {}", target, cause);
        return new SshConnectException(kind, detail, cause);
    }

    private static SshFailureKind kindOf(Throwable cause) {
        for (Throwable t = cause; t != null; t = (t.getCause() == t ? null : t.getCause())) {
            if (t instanceof UserAuthException) {
                return SshFailureKind.AUTH_FAILED;
            }
            if (t instanceof SocketTimeoutException) {
                return SshFailureKind.CONNECT_TIMEOUT;
            }
            if (t instanceof UnknownHostException || t instanceof NoRouteToHostException
                    || t instanceof ConnectException || t instanceof SocketException) {
                return SshFailureKind.HOST_UNREACHABLE;
            }
        }
        // 文本兜底：sshj 的部分包装异常不保留 cause，只在消息里留下线索
        String text = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
        if (text.contains("timeout") || text.contains("timed out")) {
            return SshFailureKind.CONNECT_TIMEOUT;
        }
        if (text.contains("connect") || text.contains("unreachable") || text.contains("resolve")) {
            return SshFailureKind.HOST_UNREACHABLE;
        }
        return SshFailureKind.INTERNAL;
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static int millisOf(java.time.Duration duration) {
        long millis = duration.toMillis();
        if (millis <= 0) {
            return 1;
        }
        return millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) millis;
    }

    /**
     * 静默关闭。
     * WHY 必须吞掉异常：它总是出现在 catch/finally 里，此时再抛异常会**覆盖**真正的失败原因，
     * 让用户看到一个与故障无关的"关闭失败"。
     */
    static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            LOG.debug("关闭 SSH 资源时出错（已忽略）: {}", closeable.getClass().getSimpleName(), e);
        }
    }

    /** 供 SSH 服务内部使用的统一关闭入口：{@code SSHClient.disconnect()} 抛 IOException。 */
    static void disconnectQuietly(SSHClient client) {
        if (client == null) {
            return;
        }
        try {
            client.disconnect();
        } catch (Exception e) {
            LOG.debug("断开 SSH 连接时出错（已忽略）", e);
        }
    }
}
