package com.ananoesis.shell.ssh;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ananoesis.shell.config.TransferProperties;
import com.ananoesis.shell.entity.FileTransfer;
import com.ananoesis.shell.mapper.FileTransferMapper;
import com.ananoesis.shell.security.DownloadTicketService;
import com.ananoesis.shell.service.TransferConflictException;
import com.ananoesis.shell.service.TransferService;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.HostKeyVerifier;

/**
 * 上传发布与覆盖语义测试（design.md D9）。
 *
 * <p>使用嵌入式 SFTP 服务器验证端到端的上传发布流程：
 * 临时文件写入、字节验证、rename、覆盖冲突检测。</p>
 */
@ExtendWith(MockitoExtension.class)
class TransferPublicationTest {

    private static SftpTestServer sftpServer;
    private static Path tempDir;

    @Mock
    private FileTransferMapper mapper;

    @Mock
    private SshTerminalService terminalService;

    private TransferProperties properties;
    private DownloadTicketService ticketService;
    private TransferService service;
    private SessionRuntime runtime;
    private SSHClient sshClient;

    @BeforeAll
    static void startServer() throws Exception {
        tempDir = Files.createTempDirectory("transfer-publish-test-");
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
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        properties = new TransferProperties();
        properties.setMaxPerSession(10);
        properties.setMaxGlobal(10);
        properties.setMaxQueued(100);
        properties.setReadyTimeoutSeconds(30);
        properties.setUploadBufferSize(64 * 1024);
        ticketService = new DownloadTicketService(properties);
        service = new TransferService(mapper, properties, terminalService, ticketService);

        // 创建 SSHClient 连接到测试 SFTP 服务器
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

        String sessionId = UUID.randomUUID().toString();
        SshTerminalSession terminalSession = new SshTerminalSession(
                sessionId, sshClient, null, null,
                new NoopTerminalOutputListener(),
                reason -> {});

        runtime = new SessionRuntime(
                UUID.randomUUID(),
                terminalSession,
                new NoopTerminalOutputListener(),
                null);
    }

    @Nested
    @DisplayName("上传发布")
    class UploadPublication {

        @Test
        @DisplayName("上传完成后文件出现在目标路径")
        void uploadCreatesFileAtTargetPath() throws Exception {
            Path targetDir = sftpServer.createDir("upload_test");

            FileTransfer entity = createReadyTransfer("upload-1", "session-1",
                    "/upload_test/hello.txt", "hello.txt", 5L);

            when(mapper.selectById("upload-1")).thenReturn(entity);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);
            when(terminalService.requireRuntime("session-1")).thenReturn(runtime);

            byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
            service.uploadContent("upload-1", new ByteArrayInputStream(content));

            Path targetFile = tempDir.resolve("upload_test/hello.txt");
            assertThat(targetFile).exists();
            assertThat(Files.readString(targetFile)).isEqualTo("hello");
        }

        @Test
        @DisplayName("上传完成后状态变为 published")
        void statusBecomesPublished() throws Exception {
            sftpServer.createDir("upload_test2");

            FileTransfer entity = createReadyTransfer("upload-2", "session-1",
                    "/upload_test2/data.bin", "data.bin", 3L);

            when(mapper.selectById("upload-2")).thenReturn(entity);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);
            when(terminalService.requireRuntime("session-1")).thenReturn(runtime);

            byte[] content = "abc".getBytes(StandardCharsets.UTF_8);
            service.uploadContent("upload-2", new ByteArrayInputStream(content));

            // 验证 updateById 被多次调用（状态流转：transferring → publishing → published）
            verify(mapper, atLeast(3)).updateById(any(FileTransfer.class));
        }

        @Test
        @DisplayName("零字节文件上传成功")
        void zeroByteUploadSucceeds() throws Exception {
            sftpServer.createDir("upload_empty");

            FileTransfer entity = createReadyTransfer("upload-3", "session-1",
                    "/upload_empty/empty.txt", "empty.txt", 0L);

            when(mapper.selectById("upload-3")).thenReturn(entity);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);
            when(terminalService.requireRuntime("session-1")).thenReturn(runtime);

            byte[] content = new byte[0];
            service.uploadContent("upload-3", new ByteArrayInputStream(content));

            Path targetFile = tempDir.resolve("upload_empty/empty.txt");
            assertThat(targetFile).exists();
            assertThat(Files.size(targetFile)).isEqualTo(0L);
        }

        @Test
        @DisplayName("临时文件在发布后被清理")
        void tempFileCleanedUpAfterPublish() throws Exception {
            sftpServer.createDir("upload_temp");

            FileTransfer entity = createReadyTransfer("upload-4", "session-1",
                    "/upload_temp/file.txt", "file.txt", 4L);

            when(mapper.selectById("upload-4")).thenReturn(entity);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);
            when(terminalService.requireRuntime("session-1")).thenReturn(runtime);

            service.uploadContent("upload-4", new ByteArrayInputStream("test".getBytes()));

            // 临时文件不应存在（已通过 SFTP rename 到目标路径）
            Path tempFile = tempDir.resolve(".ananoesis-upload-upload-4.part");
            assertThat(tempFile).doesNotExist();
        }
    }

    @Nested
    @DisplayName("覆盖语义")
    class OverwriteSemantics {

        @Test
        @DisplayName("目标已存在且 overwrite=false → 409 transfer_conflict")
        void targetExistsNoOverwrite() throws Exception {
            Path targetDir = sftpServer.createDir("overwrite_test");
            sftpServer.createFile(targetDir, "existing.txt", "existing content");

            FileTransfer entity = createReadyTransfer("overwrite-1", "session-1",
                    "/overwrite_test/existing.txt", "existing.txt", 3L);
            entity.setOverwrite(0);

            when(mapper.selectById("overwrite-1")).thenReturn(entity);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);
            when(terminalService.requireRuntime("session-1")).thenReturn(runtime);

            assertThatThrownBy(() ->
                    service.uploadContent("overwrite-1",
                            new ByteArrayInputStream("new".getBytes())))
                    .isInstanceOf(TransferConflictException.class);
        }

        @Test
        @DisplayName("目标不存在时正常上传（即使 overwrite=false）")
        void targetNotExistsUploadsNormally() throws Exception {
            Path targetDir = sftpServer.createDir("overwrite_test2");

            FileTransfer entity = createReadyTransfer("overwrite-2", "session-1",
                    "/overwrite_test2/new.txt", "new.txt", 5L);
            entity.setOverwrite(0);

            when(mapper.selectById("overwrite-2")).thenReturn(entity);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);
            when(terminalService.requireRuntime("session-1")).thenReturn(runtime);

            service.uploadContent("overwrite-2",
                    new ByteArrayInputStream("hello".getBytes()));

            Path targetFile = tempDir.resolve("overwrite_test2/new.txt");
            assertThat(targetFile).exists();
        }
    }

    @Nested
    @DisplayName("字节验证")
    class ByteVerification {

        @Test
        @DisplayName("上传字节数与声明不匹配时失败")
        void sizeMismatchFails() throws Exception {
            sftpServer.createDir("verify_test");

            FileTransfer entity = createReadyTransfer("verify-1", "session-1",
                    "/verify_test/file.txt", "file.txt", 100L);

            when(mapper.selectById("verify-1")).thenReturn(entity);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);
            when(terminalService.requireRuntime("session-1")).thenReturn(runtime);

            // 实际只上传 5 字节，但声明 100 字节
            assertThatThrownBy(() ->
                    service.uploadContent("verify-1",
                            new ByteArrayInputStream("hello".getBytes())))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private FileTransfer createReadyTransfer(String id, String sessionId,
                                             String remotePath, String fileName, long size) {
        FileTransfer entity = new FileTransfer();
        entity.setId(id);
        entity.setSessionId(sessionId);
        entity.setDirection("upload");
        entity.setRemotePath(remotePath);
        entity.setFileName(fileName);
        entity.setDeclaredSize(size);
        entity.setTransferredBytes(0L);
        entity.setStatus("ready");
        entity.setOverwrite(0);
        entity.setReadyAt(java.time.LocalDateTime.now());
        entity.setReadyDeadline(java.time.LocalDateTime.now().plusSeconds(60));
        entity.setQueuedAt(java.time.LocalDateTime.now());
        entity.setCreatedAt(java.time.LocalDateTime.now());
        entity.setUpdatedAt(java.time.LocalDateTime.now());
        return entity;
    }

    /** 不产生任何输出的 TerminalOutputListener 桩。 */
    private static class NoopTerminalOutputListener implements TerminalOutputListener {
        @Override public void onStdout(String data) {}
        @Override public void onStderr(String data) {}
        @Override public void onClosed(SshCloseReason reason) {}
    }
}
