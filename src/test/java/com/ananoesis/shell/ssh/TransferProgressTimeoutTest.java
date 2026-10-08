package com.ananoesis.shell.ssh;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ananoesis.shell.config.TransferProperties;
import com.ananoesis.shell.contract.model.Transfer;
import com.ananoesis.shell.entity.FileTransfer;
import com.ananoesis.shell.mapper.FileTransferMapper;
import com.ananoesis.shell.security.DownloadTicketService;
import com.ananoesis.shell.service.TransferProgressWatchdog;
import com.ananoesis.shell.service.TransferService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;

/**
 * 传输「无进度超时」watchdog 测试（sftp-transfer spec）。
 *
 * <p>对应 Scenario「大文件传输无进度超时」：连续 300 秒无字节进展 → 判定失败、
 * 释放资源、显示失败原因，不无限等待。判定逻辑用可注入假时钟精确驱动，
 * 传输循环接线（上传/下载打点与摘除）用嵌入式 SFTP 服务器走真实路径。</p>
 */
@ExtendWith(MockitoExtension.class)
class TransferProgressTimeoutTest {

    @Mock
    private FileTransferMapper mapper;

    @Mock
    private SshTerminalService terminalService;

    private TransferProperties properties;
    private DownloadTicketService ticketService;
    private AtomicLong clockNanos;
    private TransferProgressWatchdog watchdog;
    private TransferService service;

    private final String sessionId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        properties = new TransferProperties();
        properties.setMaxPerSession(10);
        properties.setMaxGlobal(10);
        properties.setMaxQueued(100);
        properties.setReadyTimeoutSeconds(30);
        properties.setProgressTimeoutSeconds(300);
        ticketService = new DownloadTicketService(properties);
        clockNanos = new AtomicLong(0L);
        watchdog = new TransferProgressWatchdog(clockNanos::get);
        service = new TransferService(mapper, properties, terminalService, ticketService, watchdog);
    }

    /** 推进假时钟（秒）。 */
    private void advanceSeconds(long seconds) {
        clockNanos.addAndGet(seconds * 1_000_000_000L);
    }

    private FileTransfer createTransfer(String id, String status, String direction) {
        FileTransfer entity = new FileTransfer();
        entity.setId(id);
        entity.setSessionId(sessionId);
        entity.setDirection(direction);
        entity.setRemotePath("/test/file.txt");
        entity.setFileName("file.txt");
        entity.setDeclaredSize(100L);
        entity.setTransferredBytes(0L);
        entity.setStatus(status);
        entity.setOverwrite(0);
        entity.setQueuedAt(java.time.LocalDateTime.now());
        entity.setCreatedAt(java.time.LocalDateTime.now());
        entity.setUpdatedAt(java.time.LocalDateTime.now());
        if ("ready".equals(status)) {
            entity.setReadyAt(java.time.LocalDateTime.now());
            entity.setReadyDeadline(java.time.LocalDateTime.now().plusSeconds(60));
        }
        return entity;
    }

    // ==================================================================
    // 无进度判定（纯 Mockito：expireStalledTransfers 的失败落库与配额释放）
    // ==================================================================

    @Nested
    @DisplayName("无进度判定（spec：连续 300 秒无字节进展则判定传输失败）")
    class StallDetection {

        @Test
        @DisplayName("无进度达 300 秒 → 判定 failed + failure_code=progress_timeout，配额释放并离开追踪")
        void stalledTransferringIsFailedAndQuotaReleased() {
            String transferId = UUID.randomUUID().toString();
            FileTransfer entity = createTransfer(transferId, "transferring", "upload");
            watchdog.register(transferId);
            when(mapper.selectById(transferId)).thenReturn(entity);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);
            when(mapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

            advanceSeconds(300);
            service.expireStalledTransfers();

            assertThat(entity.getStatus()).as("连续 300 秒无进展必须判定失败").isEqualTo("failed");
            assertThat(entity.getFailureCode()).isEqualTo(TransferService.FAILURE_PROGRESS_TIMEOUT);
            assertThat(watchdog.trackedCount()).as("终态传输必须离开扫描视野").isZero();
            // markFailed → tryPromoteToReady：槽位释放给排队任务（"释放资源"）
            verify(mapper, atLeastOnce()).selectCount(any(LambdaQueryWrapper.class));
        }

        @Test
        @DisplayName("阈值内（299 秒）绝不判定，传输保持 transferring")
        void justBelowTimeoutIsNotFailed() {
            String transferId = UUID.randomUUID().toString();
            FileTransfer entity = createTransfer(transferId, "transferring", "upload");
            watchdog.register(transferId);

            advanceSeconds(299);
            service.expireStalledTransfers();

            assertThat(entity.getStatus()).isEqualTo("transferring");
            assertThat(watchdog.trackedCount()).isEqualTo(1);
            verify(mapper, never()).updateById(any(FileTransfer.class));
        }

        @Test
        @DisplayName("持续有字节进展的传输不被误判（每轮续命后扫描均放行）")
        void progressingTransferIsNeverFailed() {
            String transferId = UUID.randomUUID().toString();
            FileTransfer entity = createTransfer(transferId, "transferring", "upload");
            watchdog.register(transferId);

            // 20 分钟内每 250 秒有一次字节进展：绝不触发判定
            for (int round = 0; round < 5; round++) {
                advanceSeconds(250);
                watchdog.markProgress(transferId);
                service.expireStalledTransfers();
            }
            verify(mapper, never()).updateById(any(FileTransfer.class));
            assertThat(entity.getStatus()).isEqualTo("transferring");

            // 此后真正停滞 300 秒才判定
            when(mapper.selectById(transferId)).thenReturn(entity);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);
            when(mapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            advanceSeconds(300);
            service.expireStalledTransfers();

            assertThat(entity.getStatus()).isEqualTo("failed");
            assertThat(entity.getFailureCode()).isEqualTo(TransferService.FAILURE_PROGRESS_TIMEOUT);
        }

        @Test
        @DisplayName("已终态（delivered）的残留追踪项不覆盖既有结果，仅摘除")
        void terminalTransferIsNotOverwritten() {
            String transferId = UUID.randomUUID().toString();
            FileTransfer entity = createTransfer(transferId, "delivered", "download");
            watchdog.register(transferId);
            when(mapper.selectById(transferId)).thenReturn(entity);

            advanceSeconds(300);
            service.expireStalledTransfers();

            assertThat(entity.getStatus()).as("保留最初结果").isEqualTo("delivered");
            verify(mapper, never()).updateById(any(FileTransfer.class));
            assertThat(watchdog.trackedCount()).isZero();
        }

        @Test
        @DisplayName("失败原因含明确超时秒数（failure_message 展示给用户）")
        void failureMessageContainsTimeoutSeconds() {
            String transferId = UUID.randomUUID().toString();
            FileTransfer entity = createTransfer(transferId, "transferring", "download");
            watchdog.register(transferId);
            when(mapper.selectById(transferId)).thenReturn(entity);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);
            when(mapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

            advanceSeconds(300);
            service.expireStalledTransfers();

            Transfer dto = service.getTransfer(transferId);
            assertThat(dto.getFailureCode()).isEqualTo(TransferService.FAILURE_PROGRESS_TIMEOUT);
            assertThat(dto.getFailureMessage())
                    .as("失败原因必须含配置的超时秒数，用户才知道等了多久")
                    .contains("300").contains("秒");
        }

        @Test
        @DisplayName("单条判定失败（库抖动）不阻断同批其他传输，留下轮重试")
        void singleLookupFailureDoesNotBlockOthers() {
            String badId = UUID.randomUUID().toString();
            String goodId = UUID.randomUUID().toString();
            FileTransfer good = createTransfer(goodId, "transferring", "upload");
            watchdog.register(badId);
            watchdog.register(goodId);
            when(mapper.selectById(badId)).thenThrow(new RuntimeException("模拟库抖动"));
            when(mapper.selectById(goodId)).thenReturn(good);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);
            when(mapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

            advanceSeconds(300);
            service.expireStalledTransfers();

            assertThat(good.getStatus()).as("同批的其他传输仍被正常判定").isEqualTo("failed");
            assertThat(watchdog.trackedCount()).as("异常条目保留追踪，下一轮扫描重试").isEqualTo(1);
        }
    }

    // ==================================================================
    // 传输循环接线（真实 SFTP：注册/打点/摘除必须挂在正确的位置上）
    // ==================================================================

    @Nested
    @DisplayName("传输循环接线（嵌入式 SFTP）")
    class LoopWiring {

        private static SftpTestServer sftpServer;
        private static Path tempDir;

        private SSHClient sshClient;
        private SessionRuntime runtime;

        @BeforeAll
        static void startServer() throws Exception {
            tempDir = Files.createTempDirectory("transfer-progress-test-");
            sftpServer = new SftpTestServer(tempDir);
            sftpServer.start();
        }

        @AfterAll
        static void stopServer() throws Exception {
            if (sftpServer != null) {
                sftpServer.stop();
            }
            if (tempDir != null) {
                Files.walk(tempDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                        });
            }
        }

        @BeforeEach
        void connect() throws Exception {
            sshClient = new SSHClient();
            sshClient.addHostKeyVerifier(new HostKeyVerifier() {
                @Override
                public boolean verify(String hostname, int port, PublicKey key) {
                    return true;
                }

                @Override
                public List<String> findExistingAlgorithms(String hostname, int port) {
                    return List.of();
                }
            });
            sshClient.connect("localhost", sftpServer.port());
            sshClient.authPassword("testuser", "testpass");

            SshTerminalSession terminalSession = new SshTerminalSession(
                    sessionId, sshClient, null, null,
                    new NoopOutputListener(), reason -> { });
            runtime = new SessionRuntime(UUID.randomUUID(), terminalSession,
                    new NoopOutputListener(), null);
        }

        @AfterEach
        void disconnect() throws Exception {
            if (sshClient != null) {
                sshClient.disconnect();
            }
        }

        @Test
        @DisplayName("上传完成（published）后 watchdog 不再追踪（register→unregister 闭环）")
        void uploadCompletionUnregistersFromWatchdog() throws Exception {
            String transferId = UUID.randomUUID().toString();
            FileTransfer entity = createTransfer(transferId, "ready", "upload");
            entity.setRemotePath("/done.txt");
            entity.setDeclaredSize(5L);
            when(mapper.selectById(transferId)).thenReturn(entity);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);
            when(terminalService.requireRuntime(sessionId)).thenReturn(runtime);

            service.uploadContent(transferId,
                    new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));

            assertThat(watchdog.trackedCount())
                    .as("终态传输必须离开扫描视野，否则条目泄漏且空耗扫描")
                    .isZero();
        }

        @Test
        @DisplayName("下载逐块读取时打点续命；流关闭后摘除追踪")
        void downloadStreamMarksProgressAndCloseUnregisters() throws Exception {
            String content = "hello-download-progress";
            String transferId = UUID.randomUUID().toString();
            FileTransfer entity = createTransfer(transferId, "ready", "download");
            entity.setRemotePath("/dl.txt");
            when(mapper.selectById(transferId)).thenReturn(entity);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);
            when(terminalService.requireRuntime(sessionId)).thenReturn(runtime);
            sftpServer.createFile("dl.txt", content);

            String ticket = service.claimDownloadTicket(transferId);
            TransferService.DownloadResult result = service.startDownload(transferId, ticket);
            assertThat(watchdog.trackedCount()).as("进入 transferring 即被追踪").isEqualTo(1);

            // 逐块读取：每块都推动假时钟，验证"有字节进展就续命"
            byte[] buffer = new byte[4];
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            InputStream in = result.getInputStream();
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                received.write(buffer, 0, bytesRead);
                advanceSeconds(50); // 每块间隔 50 秒，远小于 300 秒阈值
            }
            // 停顿计时从最后一块起算：此刻绝不判定停滞
            assertThat(watchdog.findStalled(java.time.Duration.ofSeconds(300)))
                    .as("持续读取的下载绝不误判").isEmpty();
            assertThat(new String(received.toByteArray(), StandardCharsets.UTF_8)).isEqualTo(content);

            // 关闭前：若再停滞 300 秒则应被判到（条目仍在）
            advanceSeconds(300);
            assertThat(watchdog.findStalled(java.time.Duration.ofSeconds(300))).containsExactly(transferId);

            // 流关闭（控制器 try-with-resources）→ 摘除
            result.close();
            assertThat(watchdog.trackedCount()).isZero();
        }

        @Test
        @DisplayName("上传中途假死：扫描判定 failed(progress_timeout) + 配额释放；后到的 io_error 不覆盖最初原因")
        void stalledMidUploadIsFailedAndFirstErrorWins() throws Exception {
            String transferId = UUID.randomUUID().toString();
            FileTransfer entity = createTransfer(transferId, "ready", "upload");
            entity.setRemotePath("/stalled.bin");
            entity.setDeclaredSize(2L);
            when(mapper.selectById(transferId)).thenReturn(entity);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);
            when(terminalService.requireRuntime(sessionId)).thenReturn(runtime);

            // 首块正常供给，之后挂起（模拟远端假死），解除后连接报错
            CountDownLatch firstChunk = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            InputStream stalling = new InputStream() {
                private boolean first = true;

                @Override
                public int read() {
                    return -1;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    if (first) {
                        first = false;
                        b[off] = 'x';
                        firstChunk.countDown();
                        return 1;
                    }
                    try {
                        release.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("等待解除时被中断", e);
                    }
                    throw new IOException("模拟假死后连接中断");
                }
            };

            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> uploading = worker.submit(() -> service.uploadContent(transferId, stalling));

                assertThat(firstChunk.await(10, TimeUnit.SECONDS))
                        .as("首块应在超时内写入 SFTP 并完成打点").isTrue();
                assertThat(watchdog.trackedCount()).as("在飞上传必须被追踪").isEqualTo(1);

                // 假时钟推过阈值，扫描线程（独立于卡死的上传线程）判定失败
                advanceSeconds(300);
                service.expireStalledTransfers();

                assertThat(entity.getStatus()).isEqualTo("failed");
                assertThat(entity.getFailureCode()).isEqualTo(TransferService.FAILURE_PROGRESS_TIMEOUT);
                assertThat(watchdog.trackedCount()).isZero();
                verify(mapper, atLeastOnce()).selectCount(any(LambdaQueryWrapper.class));

                // 解除挂起：请求线程随后抛 IOException 并试图 markFailed(io_error)，
                // 终态保护必须保住 watchdog 的最初判定（保留最初错误）
                release.countDown();
                assertThatThrownBy(() -> {
                    try {
                        uploading.get(10, TimeUnit.SECONDS);
                    } catch (java.util.concurrent.ExecutionException e) {
                        throw new RuntimeException(e.getCause());
                    }
                }).isInstanceOf(RuntimeException.class);
                assertThat(entity.getFailureCode())
                        .as("后到的失败原因不得覆盖先到的")
                        .isEqualTo(TransferService.FAILURE_PROGRESS_TIMEOUT);
            } finally {
                release.countDown();
                worker.shutdownNow();
                worker.awaitTermination(10, TimeUnit.SECONDS);
            }
        }
    }

    /** 不产生任何输出的 TerminalOutputListener 桩。 */
    private static class NoopOutputListener implements TerminalOutputListener {
        @Override public void onStdout(String data) { }
        @Override public void onStderr(String data) { }
        @Override public void onClosed(SshCloseReason reason) { }
    }
}
