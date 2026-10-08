package com.ananoesis.shell.ssh;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.ananoesis.shell.contract.model.TransferStatus;
import com.ananoesis.shell.entity.FileTransfer;
import com.ananoesis.shell.mapper.FileTransferMapper;
import com.ananoesis.shell.security.DownloadTicketService;
import com.ananoesis.shell.service.NotFoundException;
import com.ananoesis.shell.service.TransferService;

/**
 * 故障隔离与取消测试（design.md D9）。
 *
 * <p>验证传输失败时的隔离行为：状态标记、临时文件清理、槽位释放。
 * 以及取消操作的幂等性和资源清理。</p>
 */
@ExtendWith(MockitoExtension.class)
class TransferFailureTest {

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
        properties.setMaxPerSession(10);
        properties.setMaxGlobal(10);
        properties.setMaxQueued(100);
        properties.setReadyTimeoutSeconds(30);
        ticketService = new DownloadTicketService(properties);
        service = new TransferService(mapper, properties, terminalService, ticketService);
    }

    @Nested
    @DisplayName("取消传输")
    class CancelTransfer {

        @Test
        @DisplayName("取消 queued 状态的任务 → cancelled")
        void cancelQueuedTransfer() {
            String transferId = UUID.randomUUID().toString();
            FileTransfer entity = createTransfer(transferId, sessionId, "queued");

            when(mapper.selectById(transferId)).thenReturn(entity);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);
            when(mapper.selectCount(any())).thenReturn(0L);
            when(mapper.selectOne(any())).thenReturn(null);

            Transfer result = service.cancelTransfer(transferId);

            assertThat(result.getStatus()).isEqualTo(TransferStatus.CANCELLED);
        }

        @Test
        @DisplayName("取消 ready 状态的任务 → cancelled")
        void cancelReadyTransfer() {
            String transferId = UUID.randomUUID().toString();
            FileTransfer entity = createTransfer(transferId, sessionId, "ready");

            when(mapper.selectById(transferId)).thenReturn(entity);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);
            when(mapper.selectCount(any())).thenReturn(0L);
            when(mapper.selectOne(any())).thenReturn(null);

            Transfer result = service.cancelTransfer(transferId);

            assertThat(result.getStatus()).isEqualTo(TransferStatus.CANCELLED);
        }

        @Test
        @DisplayName("取消已终态的任务是幂等的")
        void cancelTerminalTransferIsIdempotent() {
            String transferId = UUID.randomUUID().toString();
            FileTransfer entity = createTransfer(transferId, sessionId, "published");

            when(mapper.selectById(transferId)).thenReturn(entity);

            Transfer result = service.cancelTransfer(transferId);

            assertThat(result.getStatus()).isEqualTo(TransferStatus.PUBLISHED);
            verify(mapper, never()).updateById(any(FileTransfer.class));
        }

        @Test
        @DisplayName("取消后释放槽位并尝试调度下一个")
        void cancelReleasesSlotAndPromotesNext() {
            String transferId = UUID.randomUUID().toString();
            FileTransfer entity = createTransfer(transferId, sessionId, "queued");

            when(mapper.selectById(transferId)).thenReturn(entity);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);
            when(mapper.selectCount(any())).thenReturn(0L);
            when(mapper.selectOne(any())).thenReturn(null);

            service.cancelTransfer(transferId);

            verify(mapper, atLeastOnce()).selectCount(any());
        }

        @Test
        @DisplayName("取消不存在的任务 → NotFoundException")
        void cancelNonExistentThrows() {
            String transferId = UUID.randomUUID().toString();
            when(mapper.selectById(transferId)).thenReturn(null);

            assertThatThrownBy(() -> service.cancelTransfer(transferId))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("查询传输")
    class QueryTransfer {

        @Test
        @DisplayName("查询存在的任务返回正确状态")
        void getExistingTransfer() {
            String transferId = UUID.randomUUID().toString();
            FileTransfer entity = createTransfer(transferId, sessionId, "ready");
            entity.setDirection("upload");
            entity.setRemotePath("/test/file.txt");
            entity.setFileName("file.txt");
            entity.setDeclaredSize(100L);
            entity.setTransferredBytes(50L);

            when(mapper.selectById(transferId)).thenReturn(entity);

            Transfer result = service.getTransfer(transferId);

            assertThat(result.getId()).isEqualTo(UUID.fromString(transferId));
            assertThat(result.getStatus()).isEqualTo(TransferStatus.READY);
            assertThat(result.getBytesTransferred()).isEqualTo(50L);
            assertThat(result.getTotalBytes()).isEqualTo(100L);
        }

        @Test
        @DisplayName("查询不存在的任务 → NotFoundException")
        void getNonExistentThrows() {
            String transferId = UUID.randomUUID().toString();
            when(mapper.selectById(transferId)).thenReturn(null);

            assertThatThrownBy(() -> service.getTransfer(transferId))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("下载票据")
    class DownloadTicket {

        @Test
        @DisplayName("非 ready 状态不能领票")
        void nonReadyCannotClaimTicket() {
            String transferId = UUID.randomUUID().toString();
            FileTransfer entity = createTransfer(transferId, sessionId, "queued");
            entity.setDirection("download");

            when(mapper.selectById(transferId)).thenReturn(entity);

            assertThatThrownBy(() -> service.claimDownloadTicket(transferId))
                    .isInstanceOf(com.ananoesis.shell.service.ConflictException.class);
        }

        @Test
        @DisplayName("上传任务不能领票")
        void uploadCannotClaimTicket() {
            String transferId = UUID.randomUUID().toString();
            FileTransfer entity = createTransfer(transferId, sessionId, "ready");
            entity.setDirection("upload");

            when(mapper.selectById(transferId)).thenReturn(entity);

            assertThatThrownBy(() -> service.claimDownloadTicket(transferId))
                    .isInstanceOf(com.ananoesis.shell.service.ConflictException.class);
        }

        @Test
        @DisplayName("ready 状态的下载任务可以领票")
        void readyDownloadCanClaimTicket() {
            String transferId = UUID.randomUUID().toString();
            FileTransfer entity = createTransfer(transferId, sessionId, "ready");
            entity.setDirection("download");
            entity.setReadyDeadline(java.time.LocalDateTime.now().plusSeconds(60));

            when(mapper.selectById(transferId)).thenReturn(entity);
            when(mapper.updateById(any(FileTransfer.class))).thenReturn(1);

            String ticket = service.claimDownloadTicket(transferId);

            assertThat(ticket).isNotNull();
            assertThat(ticket).hasSize(64);
        }
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private FileTransfer createTransfer(String id, String sessionId, String status) {
        FileTransfer entity = new FileTransfer();
        entity.setId(id);
        entity.setSessionId(sessionId);
        entity.setDirection("upload");
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
            entity.setReadyDeadline(java.time.LocalDateTime.now().plusSeconds(30));
        }
        return entity;
    }
}
