package com.ananoesis.shell.ssh;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ananoesis.shell.config.TransferProperties;
import com.ananoesis.shell.contract.model.CreateTransferRequest;
import com.ananoesis.shell.contract.model.Transfer;
import com.ananoesis.shell.contract.model.TransferDirection;
import com.ananoesis.shell.contract.model.TransferStatus;
import com.ananoesis.shell.entity.FileTransfer;
import com.ananoesis.shell.mapper.FileTransferMapper;
import com.ananoesis.shell.security.DownloadTicketService;
import com.ananoesis.shell.service.TransferQuotaExceededException;
import com.ananoesis.shell.service.TransferService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

/**
 * 传输调度与配额测试（design.md D9）。
 *
 * <p>验证 TransferService 的调度逻辑：配额检查、状态提升、租期管理。</p>
 */
@ExtendWith(MockitoExtension.class)
class TransferSchedulingTest {

    @Mock
    private FileTransferMapper mapper;

    @Mock
    private SshTerminalService terminalService;

    private TransferProperties properties;
    private DownloadTicketService ticketService;
    private TransferService service;

    private final String sessionId = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        properties = new TransferProperties();
        properties.setMaxPerSession(2);
        properties.setMaxGlobal(4);
        properties.setMaxQueued(100);
        properties.setReadyTimeoutSeconds(30);
        ticketService = new DownloadTicketService(properties);
        service = new TransferService(mapper, properties, terminalService, ticketService);
    }

    @Nested
    @DisplayName("配额检查")
    class QuotaEnforcement {

        @Test
        @DisplayName("会话 active 配额满时拒绝创建（429）")
        void sessionQuotaExceeded() {
            // WHY checkQuota 调用顺序：countQueuedGlobal → countActiveForSession → countActiveGlobal
            when(mapper.selectCount(any(LambdaQueryWrapper.class)))
                    .thenReturn(0L)  // queued global count（通过）
                    .thenReturn(2L)  // session active count → 触发配额超限
                    .thenReturn(0L); // global active count（不会到达）

            CreateTransferRequest request = new CreateTransferRequest(
                    TransferDirection.UPLOAD, "/test/file.txt", "file.txt", 100L);

            assertThatThrownBy(() -> service.createTransfer(sessionId, request))
                    .isInstanceOf(TransferQuotaExceededException.class);
        }

        @Test
        @DisplayName("全局 active 配额满时拒绝创建（429）")
        void globalQuotaExceeded() {
            // WHY checkQuota 调用顺序：countQueuedGlobal → countActiveForSession → countActiveGlobal
            when(mapper.selectCount(any(LambdaQueryWrapper.class)))
                    .thenReturn(0L)  // queued global count（通过）
                    .thenReturn(0L)  // session active count（通过）
                    .thenReturn(4L); // global active count → 触发配额超限

            CreateTransferRequest request = new CreateTransferRequest(
                    TransferDirection.UPLOAD, "/test/file.txt", "file.txt", 100L);

            assertThatThrownBy(() -> service.createTransfer(sessionId, request))
                    .isInstanceOf(TransferQuotaExceededException.class);
        }

        @Test
        @DisplayName("queued 配额满时拒绝创建（429）")
        void queuedQuotaExceeded() {
            // WHY checkQuota 首先检查 queued 全局配额
            when(mapper.selectCount(any(LambdaQueryWrapper.class)))
                    .thenReturn(100L) // queued global count → 触发配额超限
                    .thenReturn(0L)   // session active count（不会到达）
                    .thenReturn(0L);  // global active count（不会到达）

            CreateTransferRequest request = new CreateTransferRequest(
                    TransferDirection.UPLOAD, "/test/file.txt", "file.txt", 100L);

            assertThatThrownBy(() -> service.createTransfer(sessionId, request))
                    .isInstanceOf(TransferQuotaExceededException.class);
        }

        @Test
        @DisplayName("配额未满时成功创建")
        void createSucceedsWhenQuotaAvailable() {
            // WHY checkQuota 三次 selectCount + tryPromoteToReady 可能再调用
            when(mapper.selectCount(any(LambdaQueryWrapper.class)))
                    .thenReturn(0L)  // queued global（通过）
                    .thenReturn(0L)  // session active（通过）
                    .thenReturn(0L)  // global active（通过）
                    .thenReturn(0L)  // promotion: global active
                    .thenReturn(0L); // promotion: session active

            when(mapper.insert(any(FileTransfer.class))).thenReturn(1);
            when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            CreateTransferRequest request = new CreateTransferRequest(
                    TransferDirection.UPLOAD, "/test/file.txt", "file.txt", 100L);

            Transfer result = service.createTransfer(sessionId, request);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(TransferStatus.QUEUED);
            verify(mapper).insert(any(FileTransfer.class));
        }
    }

    @Nested
    @DisplayName("状态提升（queued → ready）")
    class StatusPromotion {

        @Test
        @DisplayName("创建后立即尝试提升到 ready")
        void promoteToReadyOnCreate() {
            // WHY checkQuota 三次 + tryPromoteToReady 两次
            when(mapper.selectCount(any(LambdaQueryWrapper.class)))
                    .thenReturn(0L).thenReturn(0L).thenReturn(0L)
                    .thenReturn(0L).thenReturn(0L);
            when(mapper.insert(any(FileTransfer.class))).thenReturn(1);

            FileTransfer queuedTransfer = createQueuedTransfer(UUID.randomUUID().toString(), sessionId);
            when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(queuedTransfer);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);

            CreateTransferRequest request = new CreateTransferRequest(
                    TransferDirection.UPLOAD, "/test/file.txt", "file.txt", 100L);

            Transfer result = service.createTransfer(sessionId, request);

            verify(mapper, atLeastOnce()).updateById(any(FileTransfer.class));
        }

        @Test
        @DisplayName("提升到 ready 时设置 ready_deadline = now + 30s")
        void readyDeadlineIsSet() {
            when(mapper.selectCount(any(LambdaQueryWrapper.class)))
                    .thenReturn(0L).thenReturn(0L).thenReturn(0L)
                    .thenReturn(0L).thenReturn(0L);
            when(mapper.insert(any(FileTransfer.class))).thenReturn(1);

            FileTransfer queuedTransfer = createQueuedTransfer(UUID.randomUUID().toString(), sessionId);
            when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(queuedTransfer);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);

            CreateTransferRequest request = new CreateTransferRequest(
                    TransferDirection.UPLOAD, "/test/file.txt", "file.txt", 100L);

            service.createTransfer(sessionId, request);

            ArgumentCaptor<FileTransfer> captor = ArgumentCaptor.forClass(FileTransfer.class);
            verify(mapper, atLeastOnce()).updateById(captor.capture());

            FileTransfer updated = captor.getAllValues().stream()
                    .filter(t -> "ready".equals(t.getStatus()))
                    .findFirst()
                    .orElse(null);

            assertThat(updated).isNotNull();
            assertThat(updated.getReadyDeadline()).isNotNull();
            assertThat(updated.getReadyAt()).isNotNull();
        }

        @Test
        @DisplayName("配额已满时不提升")
        void noPromotionWhenQuotaFull() {
            // WHY checkQuota 三次通过，tryPromoteToReady 中 globalActive 已满
            when(mapper.selectCount(any(LambdaQueryWrapper.class)))
                    .thenReturn(0L)  // queued global（create check，通过）
                    .thenReturn(0L)  // session active（create check，通过）
                    .thenReturn(0L)  // global active（create check，通过）
                    .thenReturn(4L); // promotion: global active → 已满，不提升

            when(mapper.insert(any(FileTransfer.class))).thenReturn(1);

            CreateTransferRequest request = new CreateTransferRequest(
                    TransferDirection.UPLOAD, "/test/file.txt", "file.txt", 100L);

            service.createTransfer(sessionId, request);

            verify(mapper, never()).selectOne(any(LambdaQueryWrapper.class));
        }
    }

    @Nested
    @DisplayName("传输创建")
    class TransferCreation {

        @Test
        @DisplayName("上传传输创建后状态为 queued")
        void uploadTransferStartsQueued() {
            when(mapper.selectCount(any(LambdaQueryWrapper.class)))
                    .thenReturn(0L).thenReturn(0L).thenReturn(0L)
                    .thenReturn(0L).thenReturn(0L);
            when(mapper.insert(any(FileTransfer.class))).thenReturn(1);
            when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            CreateTransferRequest request = new CreateTransferRequest(
                    TransferDirection.UPLOAD, "/home/user/file.txt", "file.txt", 1024L);

            Transfer result = service.createTransfer(sessionId, request);

            assertThat(result.getDirection()).isEqualTo(TransferDirection.UPLOAD);
            assertThat(result.getRemotePath()).isEqualTo("/home/user/file.txt");
            assertThat(result.getFileName()).isEqualTo("file.txt");
            assertThat(result.getTotalBytes()).isEqualTo(1024L);
            assertThat(result.getBytesTransferred()).isEqualTo(0L);
        }

        @Test
        @DisplayName("下载传输创建后状态为 queued")
        void downloadTransferStartsQueued() {
            when(mapper.selectCount(any(LambdaQueryWrapper.class)))
                    .thenReturn(0L).thenReturn(0L).thenReturn(0L)
                    .thenReturn(0L).thenReturn(0L);
            when(mapper.insert(any(FileTransfer.class))).thenReturn(1);
            when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            CreateTransferRequest request = new CreateTransferRequest(
                    TransferDirection.DOWNLOAD, "/home/user/file.txt", "file.txt", 2048L);

            Transfer result = service.createTransfer(sessionId, request);

            assertThat(result.getDirection()).isEqualTo(TransferDirection.DOWNLOAD);
            assertThat(result.getStatus()).isEqualTo(TransferStatus.QUEUED);
        }

        @Test
        @DisplayName("零字节文件合法")
        void zeroByteFileAccepted() {
            when(mapper.selectCount(any(LambdaQueryWrapper.class)))
                    .thenReturn(0L).thenReturn(0L).thenReturn(0L)
                    .thenReturn(0L).thenReturn(0L);
            when(mapper.insert(any(FileTransfer.class))).thenReturn(1);
            when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            CreateTransferRequest request = new CreateTransferRequest(
                    TransferDirection.UPLOAD, "/home/user/empty.txt", "empty.txt", 0L);

            Transfer result = service.createTransfer(sessionId, request);

            assertThat(result.getTotalBytes()).isEqualTo(0L);
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private FileTransfer createQueuedTransfer(String id, String sessionId) {
        FileTransfer entity = new FileTransfer();
        entity.setId(id);
        entity.setSessionId(sessionId);
        entity.setDirection("upload");
        entity.setRemotePath("/test/file.txt");
        entity.setFileName("file.txt");
        entity.setDeclaredSize(100L);
        entity.setTransferredBytes(0L);
        entity.setStatus("queued");
        entity.setOverwrite(0);
        entity.setQueuedAt(java.time.LocalDateTime.now());
        entity.setCreatedAt(java.time.LocalDateTime.now());
        entity.setUpdatedAt(java.time.LocalDateTime.now());
        return entity;
    }
}
