package com.ananoesis.shell.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ananoesis.shell.config.TransferProperties;
import com.ananoesis.shell.contract.model.CreateTransferRequest;
import com.ananoesis.shell.contract.model.Transfer;
import com.ananoesis.shell.contract.model.TransferDirection;
import com.ananoesis.shell.contract.model.TransferStatus;
import com.ananoesis.shell.entity.FileTransfer;
import com.ananoesis.shell.mapper.FileTransferMapper;
import com.ananoesis.shell.security.DownloadTicketService;
import com.ananoesis.shell.ssh.SessionRuntime;
import com.ananoesis.shell.ssh.SshTerminalService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.FileAttributes;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.SFTPClient;

/**
 * 文件传输调度与状态机（design.md D9「SFTP 传输」）。
 *
 * <p>职责：管理传输任务的完整生命周期——创建、调度、上传/下载、发布、取消。</p>
 *
 * <h2>状态机</h2>
 * <ul>
 *   <li>上传：queued → ready → transferring → publishing → published</li>
 *   <li>下载：queued → ready → transferring → delivered</li>
 *   <li>异常终态：failed / cancelled / expired / unknown</li>
 * </ul>
 *
 * <h2>配额限制</h2>
 * <ul>
 *   <li>每 session：ready + transferring ≤ maxPerSession</li>
 *   <li>全局：ready + transferring ≤ maxGlobal</li>
 *   <li>queued 全应用 ≤ maxQueued</li>
 * </ul>
 *
 * <p>WHY 配额检查在 createTransfer 中同步执行：前端需要立即知道是被排队还是被拒绝，
 * 不能等后台调度器异步通知。调度器只负责把 queued 提升为 ready（当有槽位释放时）。</p>
 */
@Service
public class TransferService {

    private static final Logger LOG = LoggerFactory.getLogger(TransferService.class);

    private final FileTransferMapper mapper;
    private final TransferProperties properties;
    private final SshTerminalService terminalService;
    private final DownloadTicketService ticketService;

    public TransferService(FileTransferMapper mapper,
                           TransferProperties properties,
                           SshTerminalService terminalService,
                           DownloadTicketService ticketService) {
        this.mapper = Objects.requireNonNull(mapper, "mapper 不得为 null");
        this.properties = Objects.requireNonNull(properties, "properties 不得为 null");
        this.terminalService = Objects.requireNonNull(terminalService, "terminalService 不得为 null");
        this.ticketService = Objects.requireNonNull(ticketService, "ticketService 不得为 null");
    }

    // ==================================================================
    // 创建传输任务
    // ==================================================================

    /**
     * 创建文件传输任务。
     *
     * <p>检查配额后创建 queued 状态的记录，并立即尝试提升到 ready。</p>
     *
     * @param sessionId 所属会话 ID
     * @param request   创建请求
     * @return 创建后的传输任务 DTO
     * @throws TransferQuotaExceededException 配额已满
     */
    public Transfer createTransfer(String sessionId, CreateTransferRequest request) {
        Objects.requireNonNull(sessionId, "sessionId 不得为 null");
        Objects.requireNonNull(request, "request 不得为 null");

        // WHY 在创建时就检查配额而非等调度器：前端需要立即知道是被排队还是被拒绝（429）
        checkQuota(sessionId);

        // 构建实体
        FileTransfer entity = new FileTransfer();
        entity.setId(UUID.randomUUID().toString());
        entity.setSessionId(sessionId);
        entity.setDirection(request.getDirection().getValue());
        entity.setRemotePath(request.getRemotePath());
        entity.setFileName(request.getFileName());
        entity.setDeclaredSize(request.getSize());
        entity.setTransferredBytes(0L);
        entity.setStatus(TransferStatus.QUEUED.getValue());
        entity.setOverwrite(Boolean.TRUE.equals(request.getOverwrite()) ? 1 : 0);

        // 如果有 expected_target，序列化为 JSON 文本
        if (request.getExpectedTarget() != null) {
            entity.setExpectedTarget(serializeExpectedTarget(request.getExpectedTarget()));
        }

        LocalDateTime now = LocalDateTime.now();
        entity.setQueuedAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        mapper.insert(entity);
        LOG.info("已创建传输任务: id={} session={} direction={} path={}",
                entity.getId(), sessionId, entity.getDirection(), entity.getRemotePath());

        // 立即尝试调度到 ready
        tryPromoteToReady(sessionId);

        return toTransferDto(entity);
    }

    // ==================================================================
    // 查询
    // ==================================================================

    /**
     * 查询传输任务状态。
     *
     * @throws NotFoundException 任务不存在
     */
    public Transfer getTransfer(String transferId) {
        FileTransfer entity = loadTransfer(transferId);
        return toTransferDto(entity);
    }

    // ==================================================================
    // 调度
    // ==================================================================

    /**
     * 尝试将 queued 任务提升到 ready。
     *
     * <p>WHY 公开方法：除了创建时立即尝试，还需要在取消/完成释放槽位后再次调用。</p>
     */
    public void tryPromoteToReady(String sessionId) {
        // 检查全局配额
        int globalActive = countActiveGlobal();
        if (globalActive >= properties.getMaxGlobal()) {
            return;
        }

        // 检查会话配额
        int sessionActive = countActiveForSession(sessionId);
        if (sessionActive >= properties.getMaxPerSession()) {
            return;
        }

        // 找到该 session 最早的 queued 任务
        FileTransfer queued = findFirstQueued(sessionId);
        if (queued == null) {
            return;
        }

        // 提升到 ready
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.plusSeconds(properties.getReadyTimeoutSeconds());
        queued.setStatus(TransferStatus.READY.getValue());
        queued.setReadyAt(now);
        queued.setReadyDeadline(deadline);
        queued.setUpdatedAt(now);
        mapper.updateById(queued);

        LOG.info("传输任务已就绪: id={} deadline={}", queued.getId(), deadline);
    }

    // ==================================================================
    // 上传
    // ==================================================================

    /**
     * 开始上传内容。
     *
     * <p>将状态从 ready 转为 transferring，通过 SFTP 写入临时文件，
     * 完成后验证字节数，再执行发布（rename 到最终路径）。</p>
     *
     * @param transferId  传输任务 ID
     * @param inputStream 上传数据流（servlet 原始输入流）
     * @throws NotFoundException       任务不存在
     * @throws ConflictException       状态不是 ready
     * @throws TransferConflictException 目标已存在且未确认覆盖
     */
    public void uploadContent(String transferId, InputStream inputStream) {
        FileTransfer entity = loadTransfer(transferId);

        // 验证状态
        if (!TransferStatus.READY.getValue().equals(entity.getStatus())) {
            throw new ConflictException("传输任务状态不是 ready，当前状态: " + entity.getStatus());
        }

        // 检查 ready_deadline
        if (entity.getReadyDeadline() != null && LocalDateTime.now().isAfter(entity.getReadyDeadline())) {
            entity.setStatus(TransferStatus.EXPIRED.getValue());
            entity.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(entity);
            throw new ConflictException("传输任务已过期");
        }

        // 转为 transferring
        entity.setStatus(TransferStatus.TRANSFERRING.getValue());
        entity.setTransferStartedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);

        // 获取 SFTP 连接
        SessionRuntime runtime = terminalService.requireRuntime(entity.getSessionId());
        String tempPath = ".ananoesis-upload-" + transferId + ".part";

        try {
            SFTPClient sftp = openSftp(runtime);

            // 写入临时文件
            entity.setTempFilePath(tempPath);
            mapper.updateById(entity);

            long bytesWritten = writeToTempFile(sftp, tempPath, inputStream, entity);

            // 验证字节数
            if (entity.getDeclaredSize() != null && entity.getDeclaredSize() >= 0
                    && bytesWritten != entity.getDeclaredSize()) {
                throw new ConflictException("上传字节数不匹配: 声明=" + entity.getDeclaredSize()
                        + " 实际=" + bytesWritten);
            }

            // 关闭 SFTP 句柄后再发布
            sftp.close();

            // 发布到最终路径
            publishUpload(entity, runtime);

        } catch (IOException e) {
            LOG.error("上传失败: transfer={}", transferId, e);
            markFailed(entity, "io_error");
            throw new RuntimeException("上传失败: " + e.getMessage(), e);
        }
    }

    // ==================================================================
    // 下载
    // ==================================================================

    /**
     * 领取下载票据。
     *
     * @param transferId 传输任务 ID
     * @return 明文票据
     * @throws NotFoundException 任务不存在
     * @throws ConflictException 状态不允许领票
     */
    public String claimDownloadTicket(String transferId) {
        FileTransfer entity = loadTransfer(transferId);

        // 只有 ready 状态的下载任务可以领票
        if (!TransferStatus.READY.getValue().equals(entity.getStatus())) {
            throw new ConflictException("传输任务状态不允许领票，当前状态: " + entity.getStatus());
        }
        if (!"download".equals(entity.getDirection())) {
            throw new ConflictException("只有下载任务可以领取票据");
        }

        // 检查 ready_deadline
        if (entity.getReadyDeadline() != null && LocalDateTime.now().isAfter(entity.getReadyDeadline())) {
            entity.setStatus(TransferStatus.EXPIRED.getValue());
            entity.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(entity);
            throw new ConflictException("传输任务已过期");
        }

        // 签发票据（再次领票使旧票失效）
        String ticket = ticketService.issueTicket(transferId, entity.getReadyDeadline());

        // 更新票据信息到实体（仅记录过期时间，不存明文）
        entity.setDownloadTicketExpiresAt(entity.getReadyDeadline());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);

        return ticket;
    }

    /**
     * 执行下载：验证票据后从 SFTP 读取文件流。
     *
     * @param transferId 传输任务 ID
     * @param ticket     下载票据
     * @return 远端文件的输入流（调用方负责关闭）
     * @throws NotFoundException 任务不存在
     * @throws ConflictException 票据无效
     */
    public DownloadResult startDownload(String transferId, String ticket) {
        FileTransfer entity = loadTransfer(transferId);

        // 验证票据
        if (!ticketService.consumeTicket(transferId, ticket)) {
            throw new ConflictException("下载票据无效或已过期");
        }

        // 验证状态
        if (!TransferStatus.READY.getValue().equals(entity.getStatus())) {
            throw new ConflictException("传输任务状态不是 ready");
        }

        // 转为 transferring
        entity.setStatus(TransferStatus.TRANSFERRING.getValue());
        entity.setTransferStartedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);

        // 获取 SFTP 连接并打开文件
        SessionRuntime runtime = terminalService.requireRuntime(entity.getSessionId());
        try {
            SFTPClient sftp = openSftp(runtime);
            RemoteFile remoteFile = sftp.open(entity.getRemotePath());

            // 获取文件属性用于 Content-Disposition
            FileAttributes attrs = sftp.stat(entity.getRemotePath());

            return new DownloadResult(remoteFile, attrs, entity.getFileName(), sftp);
        } catch (IOException e) {
            markFailed(entity, "io_error");
            throw new RuntimeException("打开远端文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 下载完成后的清理。
     */
    public void completeDownload(String transferId) {
        FileTransfer entity = loadTransfer(transferId);
        entity.setStatus(TransferStatus.DELIVERED.getValue());
        entity.setTransferCompletedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        ticketService.revokeTicket(transferId);

        // 释放槽位，尝试调度下一个
        tryPromoteToReady(entity.getSessionId());
    }

    // ==================================================================
    // 取消
    // ==================================================================

    /**
     * 取消传输任务（幂等）。
     *
     * @return 取消后的传输任务 DTO
     * @throws NotFoundException 任务不存在
     */
    public Transfer cancelTransfer(String transferId) {
        FileTransfer entity = loadTransfer(transferId);

        // 幂等：已经是终态就直接返回
        if (isTerminalState(entity.getStatus())) {
            return toTransferDto(entity);
        }

        // 先改状态再释放资源（design.md：取消先改状态再关通道）
        entity.setStatus(TransferStatus.CANCELLED.getValue());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);

        // 撤销票据
        ticketService.revokeTicket(transferId);

        // 清理临时文件（如果是上传）
        if ("upload".equals(entity.getDirection()) && entity.getTempFilePath() != null) {
            cleanupTempFile(entity);
        }

        LOG.info("传输任务已取消: id={}", transferId);

        // 释放槽位，尝试调度下一个
        tryPromoteToReady(entity.getSessionId());

        return toTransferDto(entity);
    }

    // ==================================================================
    // 内部方法
    // ==================================================================

    private FileTransfer loadTransfer(String transferId) {
        FileTransfer entity = mapper.selectById(transferId);
        if (entity == null) {
            throw new NotFoundException("传输任务不存在: " + transferId) {
            };
        }
        return entity;
    }

    private void checkQuota(String sessionId) {
        // 检查 queued 配额
        int queuedCount = countQueuedGlobal();
        if (queuedCount >= properties.getMaxQueued()) {
            throw new TransferQuotaExceededException("传输队列已满（全局 queued 上限: " + properties.getMaxQueued() + "）");
        }

        // 检查会话 active 配额
        int sessionActive = countActiveForSession(sessionId);
        if (sessionActive >= properties.getMaxPerSession()) {
            throw new TransferQuotaExceededException("该会话的传输并发数已达上限: " + properties.getMaxPerSession());
        }

        // 检查全局 active 配额
        int globalActive = countActiveGlobal();
        if (globalActive >= properties.getMaxGlobal()) {
            throw new TransferQuotaExceededException("全局传输并发数已达上限: " + properties.getMaxGlobal());
        }
    }

    private int countActiveForSession(String sessionId) {
        LambdaQueryWrapper<FileTransfer> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileTransfer::getSessionId, sessionId)
                .in(FileTransfer::getStatus, TransferStatus.READY.getValue(), TransferStatus.TRANSFERRING.getValue());
        return Math.toIntExact(mapper.selectCount(wrapper));
    }

    private int countActiveGlobal() {
        LambdaQueryWrapper<FileTransfer> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(FileTransfer::getStatus, TransferStatus.READY.getValue(), TransferStatus.TRANSFERRING.getValue());
        return Math.toIntExact(mapper.selectCount(wrapper));
    }

    private int countQueuedGlobal() {
        LambdaQueryWrapper<FileTransfer> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileTransfer::getStatus, TransferStatus.QUEUED.getValue());
        return Math.toIntExact(mapper.selectCount(wrapper));
    }

    private FileTransfer findFirstQueued(String sessionId) {
        LambdaQueryWrapper<FileTransfer> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileTransfer::getSessionId, sessionId)
                .eq(FileTransfer::getStatus, TransferStatus.QUEUED.getValue())
                .orderByAsc(FileTransfer::getQueuedAt)
                .last("LIMIT 1");
        return mapper.selectOne(wrapper);
    }

    private SFTPClient openSftp(SessionRuntime runtime) throws IOException {
        // WHY 复用 SftpService 的模式：通过 runtime 的 SSHClient 创建 SFTP 会话
        SSHClient client = runtime.terminalSession().client();
        return new SFTPClient(client);
    }

    private long writeToTempFile(SFTPClient sftp, String tempPath, InputStream input, FileTransfer entity)
            throws IOException {
        byte[] buffer = new byte[properties.getUploadBufferSize()];
        long totalBytes = 0;

        // WHY 使用 EnumSet 而非 varargs：sshj 的 open 方法接受 Set<OpenMode>
        try (RemoteFile remoteFile = sftp.open(tempPath,
                EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC))) {

            RemoteFile.RemoteFileOutputStream out = remoteFile.new RemoteFileOutputStream();
            try {
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                    totalBytes += bytesRead;

                    // 更新进度
                    entity.setTransferredBytes(totalBytes);
                    entity.setUpdatedAt(LocalDateTime.now());
                    mapper.updateById(entity);
                }
            } finally {
                out.close();
            }
        }

        return totalBytes;
    }

    private void publishUpload(FileTransfer entity, SessionRuntime runtime) {
        // 转为 publishing
        entity.setStatus(TransferStatus.PUBLISHING.getValue());
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);

        try {
            SFTPClient sftp = openSftp(runtime);
            try {
                String remotePath = entity.getRemotePath();
                String tempPath = entity.getTempFilePath();

                // 检查目标是否已存在
                boolean targetExists = fileExists(sftp, remotePath);

                if (targetExists) {
                    if (entity.getOverwrite() == null || entity.getOverwrite() == 0) {
                        // 未确认覆盖 → 冲突
                        String snapshot = getTargetSnapshot(sftp, remotePath);
                        // 清理临时文件
                        safeDelete(sftp, tempPath);
                        entity.setStatus(TransferStatus.FAILED.getValue());
                        entity.setFailureCode("transfer_conflict");
                        entity.setUpdatedAt(LocalDateTime.now());
                        mapper.updateById(entity);
                        throw new TransferConflictException("目标文件已存在: " + remotePath, snapshot);
                    }
                    // 服务端不支持原子替换 → 拒绝覆盖，要求改名
                    // design.md: 不先删除原文件再 rename
                    safeDelete(sftp, tempPath);
                    entity.setStatus(TransferStatus.FAILED.getValue());
                    entity.setFailureCode("overwrite_not_supported");
                    entity.setUpdatedAt(LocalDateTime.now());
                    mapper.updateById(entity);
                    throw new ConflictException("服务端不支持原子替换，请更换文件名");
                }

                // no-replace rename 到最终名称
                sftp.rename(tempPath, remotePath);

                // 完成
                entity.setStatus(TransferStatus.PUBLISHED.getValue());
                entity.setTransferredBytes(entity.getDeclaredSize() != null ? entity.getDeclaredSize() : entity.getTransferredBytes());
                entity.setTransferCompletedAt(LocalDateTime.now());
                entity.setPublishedAt(LocalDateTime.now());
                entity.setUpdatedAt(LocalDateTime.now());
                mapper.updateById(entity);

                LOG.info("上传已发布: id={} path={}", entity.getId(), remotePath);

            } finally {
                sftp.close();
            }

            // 释放槽位，尝试调度下一个
            tryPromoteToReady(entity.getSessionId());

        } catch (TransferConflictException | ConflictException e) {
            throw e;
        } catch (IOException e) {
            LOG.error("发布上传失败: transfer={}", entity.getId(), e);
            markFailed(entity, "publish_failed");
            throw new RuntimeException("发布失败: " + e.getMessage(), e);
        }
    }

    private boolean fileExists(SFTPClient sftp, String path) throws IOException {
        try {
            sftp.stat(path);
            return true;
        } catch (IOException e) {
            if (isNotFound(e)) {
                return false;
            }
            throw e;
        }
    }

    private String getTargetSnapshot(SFTPClient sftp, String path) {
        try {
            FileAttributes attrs = sftp.stat(path);
            // 简化为 JSON 格式
            return String.format("{\"size\":%d,\"mtime\":%d,\"mode\":\"%s\"}",
                    attrs.getSize(), attrs.getMtime(),
                    attrs.getMode().getPermissions().toString());
        } catch (IOException e) {
            return "{}";
        }
    }

    private void safeDelete(SFTPClient sftp, String path) {
        try {
            if (path != null) {
                sftp.rm(path);
            }
        } catch (IOException e) {
            LOG.debug("清理临时文件失败: path={}", path, e);
        }
    }

    private void cleanupTempFile(FileTransfer entity) {
        if (entity.getTempFilePath() == null) {
            return;
        }
        try {
            SessionRuntime runtime = terminalService.findRuntime(entity.getSessionId());
            if (runtime != null) {
                SFTPClient sftp = openSftp(runtime);
                try {
                    safeDelete(sftp, entity.getTempFilePath());
                } finally {
                    sftp.close();
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("清理临时文件失败: transfer={}", entity.getId(), e);
        }
    }

    private void markFailed(FileTransfer entity, String failureCode) {
        entity.setStatus(TransferStatus.FAILED.getValue());
        entity.setFailureCode(failureCode);
        entity.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(entity);
        ticketService.revokeTicket(entity.getId());

        // 释放槽位
        tryPromoteToReady(entity.getSessionId());
    }

    private boolean isTerminalState(String status) {
        return TransferStatus.PUBLISHED.getValue().equals(status)
                || TransferStatus.DELIVERED.getValue().equals(status)
                || TransferStatus.FAILED.getValue().equals(status)
                || TransferStatus.CANCELLED.getValue().equals(status)
                || TransferStatus.EXPIRED.getValue().equals(status);
    }

    private boolean isNotFound(IOException e) {
        return e.getMessage() != null && (e.getMessage().contains("No such file")
                || e.getMessage().contains("no such file"));
    }

    private String serializeExpectedTarget(com.ananoesis.shell.contract.model.ExpectedTarget et) {
        if (et == null) {
            return null;
        }
        String mtimeStr = et.getMtime() != null ? String.valueOf(et.getMtime().toEpochSecond()) : "null";
        return String.format("{\"size\":%d,\"mtime\":%s,\"mode\":\"%s\"}",
                et.getSize() != null ? et.getSize() : 0,
                mtimeStr,
                et.getMode() != null ? et.getMode() : "");
    }

    // ==================================================================
    // DTO 转换
    // ==================================================================

    Transfer toTransferDto(FileTransfer entity) {
        Transfer dto = new Transfer();
        dto.setId(UUID.fromString(entity.getId()));
        dto.setSessionId(entity.getSessionId() != null ? UUID.fromString(entity.getSessionId()) : null);
        dto.setDirection(TransferDirection.fromValue(entity.getDirection()));
        dto.setFileName(entity.getFileName());
        dto.setRemotePath(entity.getRemotePath());
        dto.setStatus(TransferStatus.fromValue(entity.getStatus()));
        dto.setBytesTransferred(entity.getTransferredBytes() != null ? entity.getTransferredBytes() : 0L);
        dto.setTotalBytes(entity.getDeclaredSize() != null ? entity.getDeclaredSize() : 0L);

        if (entity.getReadyDeadline() != null) {
            dto.setReadyDeadline(entity.getReadyDeadline().atZone(ZoneId.systemDefault()).toOffsetDateTime());
        }
        if (entity.getFailureCode() != null) {
            dto.setFailureCode(entity.getFailureCode());
        }
        if (entity.getCreatedAt() != null) {
            dto.setCreatedAt(entity.getCreatedAt().atZone(ZoneId.systemDefault()).toOffsetDateTime());
        }
        if (entity.getUpdatedAt() != null) {
            dto.setUpdatedAt(entity.getUpdatedAt().atZone(ZoneId.systemDefault()).toOffsetDateTime());
        }
        return dto;
    }

    // ==================================================================
    // 下载结果
    // ==================================================================

    /**
     * 下载结果：持有远端文件流和元数据。
     *
     * <p>WHY 独立类：下载需要把 RemoteFile + SFTPClient + 文件名一起返回给 Controller，
     * Controller 负责流式传输并在完成后关闭资源。</p>
     */
    public static class DownloadResult implements AutoCloseable {
        private final RemoteFile remoteFile;
        private final FileAttributes attributes;
        private final String fileName;
        private final SFTPClient sftpClient;

        public DownloadResult(RemoteFile remoteFile, FileAttributes attributes,
                              String fileName, SFTPClient sftpClient) {
            this.remoteFile = remoteFile;
            this.attributes = attributes;
            this.fileName = fileName;
            this.sftpClient = sftpClient;
        }

        public InputStream getInputStream() {
            return remoteFile.new RemoteFileInputStream();
        }

        public FileAttributes getAttributes() {
            return attributes;
        }

        public String getFileName() {
            return fileName;
        }

        public long getFileSize() {
            return attributes.getSize();
        }

        @Override
        public void close() {
            try {
                remoteFile.close();
            } catch (IOException e) {
                // 忽略
            }
            try {
                sftpClient.close();
            } catch (IOException e) {
                // 忽略
            }
        }
    }
}
