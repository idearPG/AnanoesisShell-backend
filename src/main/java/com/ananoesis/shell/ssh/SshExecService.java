package com.ananoesis.shell.ssh;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ananoesis.shell.config.SshProperties;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Signal;

/**
 * exec 通道原语（task 6.5）——<b>Wave 3 全部 AI 工具的唯一执行入口</b>。
 *
 * <h2>为 Wave 3 预留的稳定接口</h2>
 * <pre>{@code
 * ExecOutcome execute(SshTarget target, String command);                       // 默认约束
 * ExecOutcome execute(SshTarget target, String command, ExecLimits limits);    // 分级约束
 * ExecOutcome executeForHost(UUID hostId, String command);                     // 按配置执行
 * ExecOutcome executeForHost(UUID hostId, String command, ExecLimits limits);
 * }</pre>
 *
 * <p>{@code run_command} 走 {@code executeForHost}（需要审批、时限长、输出可大）；
 * {@code list_dir}/{@code read_file}/{@code system_info} 也走它，但传入更紧的
 * {@link ExecLimits}（快进快出、输出小）。把约束做成参数而非全局常量，
 * 分级策略就能在 Wave 3 里落地而不必改动本层。</p>
 *
 * <h2>三条不可协商的性质</h2>
 * <ol>
 *   <li><b>非交互</b>：绝不分配 PTY。否则 {@code sudo}/{@code vim} 会把通道挂死等待输入，
 *       AI 的一次工具调用就永久卡住。</li>
 *   <li><b>stdout / stderr / exit code 三者分离</b>：混流会让 AI 把警告误读成结果。</li>
 *   <li><b>有界</b>：执行超时 + 输出字节上限。否则一条 {@code cat 大文件} 或 {@code tail -f}
 *       就能同时撑爆 AI 的上下文与 JVM 堆。</li>
 * </ol>
 *
 * <p>WHY 每次调用自建一条连接（design.md D4 的双通道隔离）：
 * 复用交互式终端的 {@code SSHClient} 看似省资源，实际会把 exec 的超时中断与输出限制
 * 传染到人正在使用的会话上——AI 跑一条命令导致用户终端断开，是不可接受的。</p>
 *
 * <p>WHY 输出超限后仍继续"读而不留"：SSH 是带窗口流控的。一旦停止读取，
 * 远端写满窗口就会阻塞，命令永远不结束，我们只能等到超时——
 * 于是一条 {@code big} 命令会被误报成"执行超时"而不是"输出被截断"。</p>
 */
@Service
public class SshExecService {

    private static final Logger LOG = LoggerFactory.getLogger(SshExecService.class);

    /** 空命令的本地退出码。WHY 是 2：沿用 shell 对"用法错误"的约定，且 != 0 足以让上层判定失败。 */
    private static final int EXIT_CODE_BAD_USAGE = 2;

    /** 读线程池等待收尾的宽限：命令已结束，读线程只差最后一段 EOF。 */
    private static final Duration READER_GRACE = Duration.ofSeconds(2);

    /** 超时后等待读线程退出的宽限：此时要靠中断唤醒它们，不需要太久。 */
    private static final Duration READER_GRACE_AFTER_INTERRUPT = Duration.ofMillis(500);

    private final SshConnectionService connection;
    private final SshProperties properties;
    private final SshTargetResolver resolver;

    public SshExecService(SshConnectionService connection, SshProperties properties, SshTargetResolver resolver) {
        this.connection = Objects.requireNonNull(connection, "connection 不得为 null");
        this.properties = Objects.requireNonNull(properties, "properties 不得为 null");
        this.resolver = Objects.requireNonNull(resolver, "resolver 不得为 null");
    }

    // ==================================================================
    // Wave 3 入口
    // ==================================================================

    /**
     * 按主机配置执行一条命令。
     *
     * <p>凭据由 {@link SshTargetResolver} 在回调窗口内解密并在返回前擦除——
     * 本方法与调用方都接触不到明文。</p>
     *
     * @throws com.ananoesis.shell.service.HostNotFoundException 主机配置不存在
     * @throws SshConnectException                               连接/认证失败或凭据缺失
     */
    public ExecOutcome executeForHost(UUID hostId, String command) {
        return executeForHost(hostId, command, ExecLimits.from(properties));
    }

    /** 按主机配置执行，并施加调用方指定的约束（Wave 3 按工具分级使用）。 */
    public ExecOutcome executeForHost(UUID hostId, String command, ExecLimits limits) {
        Objects.requireNonNull(limits, "limits 不得为 null");
        return resolver.withTarget(hostId, target -> execute(target, command, limits));
    }

    public ExecOutcome execute(SshTarget target, String command) {
        return execute(target, command, ExecLimits.from(properties));
    }

    /**
     * 在给定目标上执行一条命令。
     *
     * @return 永不返回 {@code null}；命令本身失败（非 0 退出码）也**不**抛异常——
     *         "命令返回 1"是正常事实，只有"连不上/通道坏了"才是异常
     */
    public ExecOutcome execute(SshTarget target, String command, ExecLimits limits) {
        Objects.requireNonNull(target, "target 不得为 null");
        Objects.requireNonNull(limits, "limits 不得为 null");

        if (command == null || command.isBlank()) {
            // WHY 本地拒绝而不发一个空串过去：远端 shell 对空命令的响应是"什么都不做并返回 0"，
            // AI 会把它当成"命令成功执行了"，这是最坏的一种误导
            LOG.debug("拒绝执行空命令: target={}", target);
            return new ExecOutcome(EXIT_CODE_BAD_USAGE, "", "命令为空，已在本地拒绝执行", false, false, 0L);
        }

        long startedAt = System.nanoTime();
        SSHClient client = connection.connect(target);
        BoundedCollector stdout = new BoundedCollector(limits.maxOutputBytes());
        BoundedCollector stderr = new BoundedCollector(limits.maxOutputBytes());
        ExecutorService readers = Executors.newFixedThreadPool(2, new ReaderThreadFactory());

        boolean timedOut = false;
        Integer exitStatus = null;
        try {
            Session session = client.startSession();
            try {
                // 刻意不调用 allocatePTY：见类注释的性质 1
                Session.Command remote = session.exec(command);
                try {
                    List<Future<?>> tasks = new ArrayList<>(2);
                    tasks.add(readers.submit(() -> { stdout.drain(remote.getInputStream()); return null; }));
                    tasks.add(readers.submit(() -> { stderr.drain(remote.getErrorStream()); return null; }));

                    timedOut = !awaitCompletion(remote, limits.timeout());
                    exitStatus = remote.getExitStatus();
                    if (timedOut) {
                        LOG.warn("命令执行超时，已中断: target={} timeout={}ms", target, limits.timeout().toMillis());
                        signalQuietly(remote);
                        readers.shutdownNow();
                        awaitReaders(tasks, READER_GRACE_AFTER_INTERRUPT);
                    } else {
                        awaitReaders(tasks, READER_GRACE);
                    }
                } finally {
                    SshConnectionService.closeQuietly(remote);
                }
            } finally {
                SshConnectionService.closeQuietly(session);
            }
        } catch (IOException | RuntimeException e) {
            throw channelFailure(target, command, e);
        } finally {
            readers.shutdownNow();
            SshConnectionService.disconnectQuietly(client);
        }

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        ExecOutcome outcome = new ExecOutcome(
                exitStatus == null ? ExecOutcome.EXIT_CODE_UNKNOWN : exitStatus,
                stdout.text(),
                stderr.text(),
                stdout.truncated() || stderr.truncated(),
                timedOut,
                elapsedMillis);
        LOG.debug("exec 完成: target={} exit={} truncated={} timedOut={} elapsedMs={} stdoutBytes={} stderrBytes={}",
                target, outcome.exitCode(), outcome.truncated(), outcome.timedOut(), elapsedMillis,
                stdout.collectedBytes(), stderr.collectedBytes());
        return outcome;
    }

    // ==================================================================
    // 内部
    // ==================================================================

    /**
     * 等待远端命令结束。
     *
     * <p>WHY 靠异常而不是返回值判定超时：sshj 的 {@code Channel.join(timeout)} 底层是
     * {@code Event.await} → {@code Promise.retrieve(timeout)}，超时会抛出被
     * {@code ExceptionChainer} 包装后的 {@link ConnectionException}，而不是安静地返回。</p>
     */
    private static boolean awaitCompletion(Session.Command remote, Duration timeout) {
        try {
            remote.join(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (ConnectionException e) {
            LOG.debug("等待命令结束未能在时限内完成", e);
            return false;
        } catch (RuntimeException e) {
            LOG.debug("等待命令结束时出现异常", e);
            return false;
        }
    }

    private static void signalQuietly(Session.Command remote) {
        try {
            remote.signal(Signal.TERM);
        } catch (Exception e) {
            // 发不出信号也要继续关闭通道：TERM 只是礼貌，真正的释放靠 close
            LOG.debug("发送 TERM 信号失败（将继续关闭通道）", e);
        }
    }

    private static void awaitReaders(List<Future<?>> tasks, Duration grace) {
        for (Future<?> task : tasks) {
            try {
                task.get(grace.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // 恢复中断标志并放弃等待：调用方正在收尾，继续等只会拖慢关闭
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException | TimeoutException | CancellationException e) {
                LOG.debug("读取远端输出时结束于异常（可能因超时被中断）: {}", e.toString());
            }
        }
    }

    private static SshConnectException channelFailure(SshTarget target, String command, Exception cause) {
        if (cause instanceof SshConnectException alreadyClassified) {
            return alreadyClassified;
        }
        // WHY 日志只记命令的前若干字符：命令本身可能很长（例如整段脚本），
        // 且可能内嵌敏感参数；截断既保住可读性又避免把整条命令写进日志
        String preview = command.length() <= 120 ? command : command.substring(0, 120) + "...(truncated)";
        String detail = "exec 通道失败: " + target + " command=" + preview;
        LOG.warn("{}: {}", detail, String.valueOf(cause));
        LOG.debug("exec 通道失败完整栈", cause);
        return new SshConnectException(SshFailureKind.CHANNEL_FAILURE, detail, cause);
    }

    /** 命名 + 守护线程工厂：exec 的读线程不该阻止 JVM 退出。 */
    private static final class ReaderThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "ssh-exec-reader-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

    /**
     * 带上限的字节采集器。
     *
     * <p>WHY 按字节而不是按字符计数：上限的语义是"占用多少内存/上下文"，
     * 那是字节量；按字符数会让多字节输出实际占用远超预算。</p>
     *
     * <p>非线程安全，但访问被 {@link Future#get} 建立的 happens-before 保护：
     * 只有读线程写、且只在 {@code get()} 返回后才读。</p>
     */
    private static final class BoundedCollector {

        private static final int CHUNK = 8192;

        private final int limit;
        private final ByteArrayOutputStream buffer;
        private boolean truncated;

        BoundedCollector(int limit) {
            this.limit = limit;
            this.buffer = new ByteArrayOutputStream(Math.min(limit, CHUNK));
        }

        void drain(InputStream source) throws IOException {
            byte[] chunk = new byte[CHUNK];
            int read;
            while ((read = source.read(chunk)) != -1) {
                int room = limit - buffer.size();
                if (room <= 0) {
                    // 继续读但丢弃：见类注释——停止读取会让远端因窗口耗尽而永久阻塞
                    truncated = true;
                    continue;
                }
                int take = Math.min(room, read);
                buffer.write(chunk, 0, take);
                if (take < read) {
                    truncated = true;
                }
            }
        }

        String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }

        boolean truncated() {
            return truncated;
        }

        int collectedBytes() {
            return buffer.size();
        }
    }
}
