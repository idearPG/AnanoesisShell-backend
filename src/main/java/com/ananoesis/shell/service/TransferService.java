package com.ananoesis.shell.service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>职责：管理传输任务的完整生命周期——创建、调度、上传/下载、发布、取消，
 * 以及无进展停滞传输的超时判定（sftp-transfer spec）。</p>
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
 * <h2>无进展超时（watchdog）</h2>
 * <p>传输循环（上传写块 / 下载读块）每次字节进展都向 {@link TransferProgressWatchdog}
 * 打点续命；{@link #expireStalledTransfers()} 由定时器周期调用，把停滞超过
 * progressTimeoutSeconds 的 transferring 判定为 failed 并释放配额。
 * 判定与传输线程解耦：扫描线程只做内存计算与单条落库，不会被卡死的传输阻塞。</p>
 *
 * <p>WHY 配额检查在 createTransfer 中同步执行：前端需要立即知道是被排队还是被拒绝，
 * 不能等后台调度器异步通知。调度器只负责把 queued 提升为 ready（当有槽位释放时）。</p>
 */
@Service
public class TransferService {

    private static final Logger LOG = LoggerFactory.getLogger(TransferService.class);

    /**
     * 无进展超时失败码（sftp-transfer spec：连续 progressTimeoutSeconds 无字节进展则判定失败）。
     *
     * <p>WHY 公开常量而非魔法字符串：测试、前端文案与监控按码分类展示，
     * 字面量散落各处极易漂移。</p>
     */
    public static final String FAILURE_PROGRESS_TIMEOUT = "progress_timeout";

    private final FileTransferMapper mapper;
    private final TransferProperties properties;
    private final SshTerminalService terminalService;
    private final DownloadTicketService ticketService;
    private final TransferProgressWatchdog watchdog;

    /**
     * 生产构造器（Spring 自动装配）。
     *
     * <p>WHY 必须显式标 {@code @Autowired}：本类有两个构造器（下面那个 4 参的供既有测试使用），
     * Spring 面对多于一个候选构造器且没有无参构造器时无法决定用哪个，必须显式指定。
     * （与 {@code OpenAiCompatibleChatModelProvider} 的多构造器处理方式一致。）</p>
     */
    @Autowired
    public TransferService(FileTransferMapper mapper,
                           TransferProperties properties,
                           SshTerminalService terminalService,
                           DownloadTicketService ticketService,
                           TransferProgressWatchdog watchdog) {
        this.mapper = Objects.requireNonNull(mapper, "mapper 不得为 null");
        this.properties = Objects.requireNonNull(properties, "properties 不得为 null");
        this.terminalService = Objects.requireNonNull(terminalService, "terminalService 不得为 null");
        this.ticketService = Objects.requireNonNull(ticketService, "ticketService 不得为 null");
        this.watchdog = Objects.requireNonNull(watchdog, "watchdog 不得为 null");
    }

    /**
     * 兼容构造器：既有测试（TransferFailureTest / TransferPublicationTest / TransferSchedulingTest）
     * 仍以 4 参构造，不关心 watchdog 判定。
     *
     * <p>WHY 新建独立实例而非注入共享单例：这些测试里上传/下载接线（register/unregister）
     * 仍正常工作，只是没有扫描线程去判定，实例随测试结束即被回收。</p>
     */
    public TransferService(FileTransferMapper mapper,
                           TransferProperties properties,
                           SshTerminalService terminalService,
                           DownloadTicketService ticketService) {
        this(mapper, properties, terminalService, ticketService, new TransferProgressWatchdog());
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
    // 无进展超时判定（watchdog 扫描）
    // ==================================================================

    /**
     * 扫描停滞传输并判定失败（sftp-transfer spec：连续 progressTimeoutSeconds
     * 无字节进展则判定传输失败、释放资源、显示失败原因，不无限等待）。
     *
     * <p>设计为被定时器周期调用；判定数据来自 {@link TransferProgressWatchdog}
     * 的内存打点，扫描路径上没有任何可能长时间阻塞的操作。</p>
     *
     * <p>WHY 每条独立 try/catch：单条查库异常（库抖动）不阻断同批其他传输的判定，
     * 异常条目保留追踪，下一轮扫描自动重试；判定落库成功后才摘除追踪，
     * 崩溃时最多多判一轮（幂等），不会漏判。</p>
     */
    public void expireStalledTransfers() {
        Duration timeout = Duration.ofSeconds(properties.getProgressTimeoutSeconds());
        List<String> stalledIds = watchdog.findStalled(timeout);
        for (String transferId : stalledIds) {
            try {
                FileTransfer entity = mapper.selectById(transferId);
                if (entity == null) {
                    // 记录已不存在（如会话级联删除）：摘除防止条目永久泄漏
                    watchdog.unregister(transferId);
                    continue;
                }
                if (!TransferStatus.TRANSFERRING.getValue().equals(entity.getStatus())) {
                    // 已是终态（如已 delivered）：不覆盖既有结果，仅摘除残留追踪项。
                    // WHY 不 updateById：终态结果是传输循环写下的最初事实，扫描只做自愈清理
                    watchdog.unregister(transferId);
                    continue;
                }
                // transferring 且停滞超时 → 判定失败（失败落库 + 撤票 + 释放槽位）
                markFailed(entity, FAILURE_PROGRESS_TIMEOUT);
                watchdog.unregister(transferId);
                LOG.info("传输无进展超时判定失败: id={} 连续{}秒无字节进展",
                        transferId, properties.getProgressTimeoutSeconds());
            } catch (RuntimeException e) {
                // 单条失败不阻断同批其他传输；条目保留追踪，下一轮扫描重试
                LOG.warn("停滞传输判定失败，留下轮扫描重试: id={}", transferId, e);
            }
        }
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

        // WHY 此刻注册而非 openSftp 成功后：连接建立后远端一直不吐字节的假死同样要能被发现，
        // 停滞计时从进入 transferring 起算
        watchdog.register(transferId);

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
        } finally {
            // WHY 仅终态摘除：终态（published/failed）必须离开扫描视野，否则条目泄漏且空耗扫描；
            // 字节数不匹配等异常不落终态，保持追踪由扫描线程兜底判定，DB 状态与追踪集永不失配
            if (isTerminalState(entity.getStatus())) {
                watchdog.unregister(transferId);
            }
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

        // WHY 此刻注册：下载全程由控制器流式转发、不更新 transferred_bytes（避免写放大），
        // 停滞计时从进入 transferring 起算，读取阶段的续命由返回流的读包装负责
        watchdog.register(transferId);

        // 获取 SFTP 连接并打开文件
        SessionRuntime runtime = terminalService.requireRuntime(entity.getSessionId());
        try {
            SFTPClient sftp = openSftp(runtime);
            RemoteFile remoteFile = sftp.open(entity.getRemotePath());

            // 获取文件属性用于 Content-Disposition
            FileAttributes attrs = sftp.stat(entity.getRemotePath());

            return new DownloadResult(remoteFile, attrs, entity.getFileName(), sftp, watchdog, transferId);
        } catch (IOException e) {
            // WHY 先摘除再落库：保证追踪清理不被 markFailed 内的库异常跳过
            watchdog.unregister(transferId);
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

        // 已终态 → 离开 watchdog 扫描视野（幂等；流关闭时还会再摘一次）
        watchdog.unregister(transferId);

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

        // 已终态 → 离开 watchdog 扫描视野（从未注册过时为幂等空操作）
        watchdog.unregister(transferId);

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
        return toInt(mapper.selectCount(wrapper));
    }

    private int countActiveGlobal() {
        LambdaQueryWrapper<FileTransfer> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(FileTransfer::getStatus, TransferStatus.READY.getValue(), TransferStatus.TRANSFERRING.getValue());
        return toInt(mapper.selectCount(wrapper));
    }

    private int countQueuedGlobal() {
        LambdaQueryWrapper<FileTransfer> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(FileTransfer::getStatus, TransferStatus.QUEUED.getValue());
        return toInt(mapper.selectCount(wrapper));
    }

    /**
     * WHY null 容忍：mapper 为 mock 时 selectCount 默认返回 null（生产 MyBatis-Plus 不会），
     * {@code Math.toIntExact(null)} 会 NPE。容忍视为 0，让配额判定在测试桩下保持可用；
     * 生产语义不变（真实计数永不为 null）。
     */
    private static int toInt(Long count) {
        return count == null ? 0 : Math.toIntExact(count);
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

                    // WHY 每块打点：字节进展即续命；远端假死时 read 长期阻塞、无人打点，
                    // 扫描线程据此判定停滞——这是无进展超时唯一的事实来源
                    watchdog.markProgress(entity.getId());

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
        // WHY 终态保护（先到先得）：watchdog 扫描线程可能先于卡死的传输线程判定 progress_timeout，
        // 解除阻塞后传输线程再抛 IOException 试图 markFailed(io_error)——首个错误才是根因，
        // 后到的失败原因不得覆盖先到的判定
        if (isTerminalState(entity.getStatus())) {
            LOG.debug("传输已终态，跳过失败标记: id={} status={} 请求的失败码={}",
                    entity.getId(), entity.getStatus(), failureCode);
            return;
        }
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
            dto.setFailureMessage(describeFailureForDisplay(entity.getFailureCode()));
        }
        if (entity.getCreatedAt() != null) {
            dto.setCreatedAt(entity.getCreatedAt().atZone(ZoneId.systemDefault()).toOffsetDateTime());
        }
        if (entity.getUpdatedAt() != null) {
            dto.setUpdatedAt(entity.getUpdatedAt().atZone(ZoneId.systemDefault()).toOffsetDateTime());
        }
        return dto;
    }

    /**
     * 由失败码派生用户可读的失败原因。
     *
     * <p>WHY 派生而非落库：file_transfers 表没有 failure_message 列，failure_code 是唯一事实来源；
     * 文案内含配置的超时秒数，配置调整后展示自动跟随，无需迁移历史数据。</p>
     *
     * @return 展示文案；暂无对应文案的失败码返回 null（前端按失败码展示通用文案）
     */
    private String describeFailureForDisplay(String failureCode) {
        if (FAILURE_PROGRESS_TIMEOUT.equals(failureCode)) {
            return String.format("连续 %d 秒无字节进展，传输已判定失败",
                    properties.getProgressTimeoutSeconds());
        }
        return null;
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
        private final TransferProgressWatchdog watchdog;
        private final String transferId;

        public DownloadResult(RemoteFile remoteFile, FileAttributes attributes,
                              String fileName, SFTPClient sftpClient,
                              TransferProgressWatchdog watchdog, String transferId) {
            this.remoteFile = remoteFile;
            this.attributes = attributes;
            this.fileName = fileName;
            this.sftpClient = sftpClient;
            this.watchdog = watchdog;
            this.transferId = transferId;
        }

        public InputStream getInputStream() {
            // WHY 包装而非裸流：下载全程不更新 transferred_bytes（避免写放大），
            // 只有读循环自己知道字节是否在动——每次读到数据即向 watchdog 续命
            return new ProgressTrackingInputStream(remoteFile.new RemoteFileInputStream(), watchdog, transferId);
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
            // WHY 无条件摘除且幂等：close 是下载流生命周期终点（控制器 try-with-resources 保证调用），
            // 即使关闭过程有 IOException 也必须摘除，否则残留条目只能靠扫描自愈分支兜底
            watchdog.unregister(transferId);
        }
    }

    /**
     * 下载读包装：每次读到字节即向 watchdog 打点续命。
     *
     * <p>WHY 仅在实际读到字节时打点：EOF（-1）不是字节进展，EOF 后控制器即将结束响应
     * 并关闭流，不应为无进展的读取续命；阻塞中的 read 不返回 → 不打点 → 扫描可判定停滞。
     * 覆盖 {@code read(byte[],int,int)} 与 {@code read()} 两个入口，
     * {@link FilterInputStream#read(byte[])} 的默认实现会经虚调用走到前者。</p>
     */
    private static final class ProgressTrackingInputStream extends FilterInputStream {
        private final TransferProgressWatchdog watchdog;
        private final String transferId;

        ProgressTrackingInputStream(InputStream in, TransferProgressWatchdog watchdog, String transferId) {
            super(in);
            this.watchdog = watchdog;
            this.transferId = transferId;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b != -1) {
                watchdog.markProgress(transferId);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                watchdog.markProgress(transferId);
            }
            return n;
        }
    }
}
