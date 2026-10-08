package com.ananoesis.shell.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;

/**
 * 一条已建立的交互式 PTY 会话（task 6.3）。
 *
 * <p>它是"远端 shell"在本进程里的句柄：向上暴露 {@link #send}/{@link #resize}/{@link #close}，
 * 向下持有 sshj 的 {@code SSHClient} + {@code Session} + {@code Shell} 三层资源，
 * 并保证<b>恰好一次</b>地释放它们、<b>恰好一次</b>地通知 {@link TerminalOutputListener#onClosed}。</p>
 *
 * <h2>为什么"恰好一次"值得专门设计</h2>
 * <p>一个会话有三个并发的结束来源：用户点断开、远端 EOF、空闲回收。
 * 三者可能同时发生（用户点断开的瞬间远端也 EOF 了）。若不用原子 CAS 收口，
 * 前端会收到两条 {@code terminal_output(closed)} 而闪烁，
 * {@code sessions} 表也会被写两条 end 记录，审计因此自相矛盾。</p>
 *
 * <h2>关闭的因果顺序</h2>
 * <ol>
 *   <li>CAS 置为已关闭——挡住其余并发来源；</li>
 *   <li>中断读线程并释放 sshj 资源；</li>
 *   <li>注销注册表 + 写审计（{@code closeHook}）；</li>
 *   <li>最后才回调 {@code listener.onClosed}。</li>
 * </ol>
 * <p>WHY 通知放在最后：前端收到 closed 后通常会立刻刷新会话列表。
 * 若那时服务端还没注销/落库，用户会看到一条"已断开却仍在列表里"的幽灵会话。</p>
 */
public class SshTerminalSession {

    private static final Logger LOG = LoggerFactory.getLogger(SshTerminalSession.class);

    private static final int READ_CHUNK = 4096;

    private final String id;
    private final SSHClient client;
    private final Session channel;
    private final Session.Shell shell;
    private final TerminalOutputListener listener;
    private final Consumer<SshCloseReason> closeHook;
    private final ExecutorService readers;
    private final OutputStream stdin;

    final AtomicBoolean closed = new AtomicBoolean(false);
    /** 最近一次有活动（收到输出、发出按键、改变窗口）的时刻，空闲回收据此判定。 */
    private final AtomicLong lastActivityEpochMillis;

    SshTerminalSession(String id,
                       SSHClient client,
                       Session channel,
                       Session.Shell shell,
                       TerminalOutputListener listener,
                       Consumer<SshCloseReason> closeHook) {
        this.id = Objects.requireNonNull(id, "id 不得为 null");
        this.client = client;
        this.channel = channel;
        this.shell = shell;
        this.listener = Objects.requireNonNull(listener, "listener 不得为 null");
        this.closeHook = Objects.requireNonNull(closeHook, "closeHook 不得为 null");
        // WHY 允许 null shell：测试桩不需要真实 SSH 资源。
        // 生产路径中 shell 始终非 null（由 SshTerminalService.start() 保证）。
        this.stdin = shell != null ? shell.getOutputStream() : null;
        this.readers = Executors.newFixedThreadPool(2, new PumpThreadFactory(id));
        this.lastActivityEpochMillis = new AtomicLong(System.currentTimeMillis());
    }

    /**
     * 启动 stdout/stderr 两个读泵。
     * WHY 由服务显式调用而不是放在构造器里：构造器里启动线程会让 {@code this} 在
     * 尚未完全初始化（也尚未注册进注册表）时就被别的线程看见——
     * 极端情况下读泵会先于注册表拿到关闭事件，注销就成了空操作，注册表里留下幽灵项。
     */
    void startReaders() {
        if (shell == null) {
            return; // 测试桩：无真实 SSH 资源
        }
        readers.submit(() -> pump(shell.getInputStream(), true));
        readers.submit(() -> pump(shell.getErrorStream(), false));
    }

    /** 会话标识，即下发给前端的 {@code session_id}，也是 {@code sessions} 表主键。 */
    public String id() {
        return id;
    }

    /**
     * 底层 SSH 客户端实例。
     *
     * <p>WHY 公开访问器：SFTP 子系统需要复用同一 SSH transport 创建独立的 SFTP 通道
     * （design.md D3：「SFTP 是同一 SSH 连接上的独立 subsystem，不占用 Shell 输入」）。</p>
     */
    public SSHClient client() {
        return client;
    }

    public boolean isOpen() {
        return !closed.get();
    }

    /** 最近一次活动时刻（epoch millis 的快照）。 */
    public Instant lastActivityAt() {
        return Instant.ofEpochMilli(lastActivityEpochMillis.get());
    }

    /**
     * 转发一段用户输入（通常是单个按键）。
     *
     * <p>WHY 不缓冲、不合并：终端的响应手感直接取决于按键到回显的延迟，
     * 任何批量合并都会让光标移动、退格这类操作显得"粘"。</p>
     *
     * <p>已关闭时静默丢弃而不是抛异常：WebSocket 上的一次按键与一次关闭天然存在竞态，
     * 把正常的竞态变成异常只会污染日志。</p>
     */
    public void send(String data) {
        if (data == null || data.isEmpty() || closed.get() || stdin == null) {
            return;
        }
        try {
            stdin.write(data.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            touch();
        } catch (IOException | RuntimeException e) {
            LOG.debug("写入终端失败，按远端关闭处理: session={}", id, e);
            close(SshCloseReason.REMOTE_CLOSED);
        }
    }

    /**
     * 调整远端 PTY 的窗口尺寸。
     *
     * <p>WHY 必须真的转发到远端而不只改本地状态：{@code vim}/{@code top}/{@code less}
     * 靠 SIGWINCH 与 {@code COLUMNS}/{@code LINES} 重排。只改本地会让用户看到
     * "窗口变大了但内容还是 80 列换行"这种错位。</p>
     */
    public void resize(int columns, int rows) {
        if (closed.get() || shell == null) {
            return;
        }
        try {
            shell.changeWindowDimensions(columns, rows, 0, 0);
            touch();
        } catch (IOException | RuntimeException e) {
            LOG.debug("调整窗口尺寸失败，按远端关闭处理: session={}", id, e);
            close(SshCloseReason.REMOTE_CLOSED);
        }
    }

    /**
     * 结束会话并释放全部资源。幂等：并发或重复调用只有一次生效。
     *
     * @param reason 终止原因，写入 {@code sessions.close_reason} 并回传前端
     */
    public void close(SshCloseReason reason) {
        Objects.requireNonNull(reason, "reason 不得为 null");
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        LOG.info("关闭交互式终端会话: session={} reason={}", id, reason);
        try {
            // 先中断读泵：它们可能正阻塞在无超时的 wait() 上，中断是唯一能唤醒它们的手段
            readers.shutdownNow();
            // WHY null 检查：测试桩可能没有真实 SSH 资源
            if (shell != null) {
                SshConnectionService.closeQuietly(shell);
            }
            if (channel != null) {
                SshConnectionService.closeQuietly(channel);
            }
            if (client != null) {
                SshConnectionService.disconnectQuietly(client);
            }
        } finally {
            try {
                closeHook.accept(reason);
            } catch (RuntimeException e) {
                // 注销/落库失败不该阻止前端收到 closed：会话在传输层确实已经结束了
                LOG.warn("会话关闭钩子执行失败: session={}", id, e);
            }
            listener.onClosed(reason);
        }
    }

    private void touch() {
        lastActivityEpochMillis.set(System.currentTimeMillis());
    }

    /**
     * 读泵：把远端输出实时推给监听器。
     *
     * <p>WHY 用 {@link InputStreamReader} 而不是自己 {@code new String(bytes, 0, n)}：
     * UTF-8 的多字节字符可能正好被一次 read 切成两半，按字节拼字符串会周期性地产出
     * 替换字符（U+FFFD），表现为中文输出偶发乱码。解码器自带跨读缓冲，能正确处理。</p>
     *
     * <p>WHY 读到 EOF 或 IO 异常都归为 {@code REMOTE_CLOSED}：
     * sshj 的 {@code ChannelInputStream} 在对端 EOF、通道关闭、传输断裂三种情况下
     * 都会让 read 结束（返回 -1 或抛异常），而这三者在用户视角是同一件事——
     * "远端关闭连接"（spec「远端关闭连接」）。</p>
     */
    private void pump(InputStream source, boolean standardOutput) {
        boolean remoteEnded = false;
        try (Reader reader = new InputStreamReader(source, StandardCharsets.UTF_8)) {
            char[] chunk = new char[READ_CHUNK];
            while (!closed.get()) {
                int read = reader.read(chunk);
                if (read == -1) {
                    remoteEnded = true;
                    break;
                }
                touch();
                String data = new String(chunk, 0, read);
                if (standardOutput) {
                    listener.onStdout(data);
                } else {
                    listener.onStderr(data);
                }
            }
        } catch (InterruptedIOException e) {
            // 本地关闭时 shutdownNow() 的中断，不是远端事件
            LOG.trace("读泵被中断（本地关闭）: session={} stdout={}", id, standardOutput);
        } catch (IOException | RuntimeException e) {
            if (!closed.get()) {
                LOG.debug("读泵异常结束，按远端关闭处理: session={} stdout={}", id, standardOutput, e);
                remoteEnded = true;
            }
        }
        if (remoteEnded) {
            close(SshCloseReason.REMOTE_CLOSED);
        }
    }

    /** 命名的守护线程：一个会话两条读泵，线程名里带会话前缀便于线程转储排障。 */
    private static final class PumpThreadFactory implements ThreadFactory {

        private final String prefix;
        private final AtomicLong sequence = new AtomicLong();

        PumpThreadFactory(String sessionId) {
            this.prefix = "ssh-pty-" + sessionId.substring(0, Math.min(8, sessionId.length())) + "-";
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
