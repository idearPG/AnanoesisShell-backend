package com.ananoesis.shell.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ananoesis.shell.config.TransferProperties;

/**
 * 下载票据服务（design.md D9「SFTP 传输」）。
 *
 * <p>下载流程要求前端先领取一次性票据，再凭票据 GET 下载。
 * 票据的安全约束与控制令牌类似：服务端仅存散列，明文只返回一次。</p>
 *
 * <h2>安全约束</h2>
 * <ul>
 *   <li>票据为 256-bit 随机令牌，服务端仅存 SHA-256 散列</li>
 *   <li>单次有效：消费后即失效</li>
 *   <li>绑定传输任务：只能用于对应的 transfer</li>
 *   <li>有时限：不超过 ready_deadline，且最多 30 秒</li>
 *   <li>再次领票使旧票失效</li>
 *   <li>验证时使用常量时间比较，防止时序攻击</li>
 * </ul>
 *
 * <p>WHY 仅存散列：即使内存被转储，攻击者也无法伪造下载票据。
 * 票据与控制令牌共享同一安全模型（credential-store spec）。</p>
 */
@Service
public class DownloadTicketService {

    private static final Logger LOG = LoggerFactory.getLogger(DownloadTicketService.class);

    /** 256-bit = 32 字节。 */
    private static final int TICKET_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final TransferProperties properties;

    /**
     * transferId → 当前有效票据散列。
     * WHY 按 transferId 索引而非 ticketHash：一个传输任务同一时刻最多一张有效票据，
     * 再次领票时按 transferId 查找旧票并替换即可。
     */
    private final Map<String, TicketRecord> tickets = new ConcurrentHashMap<>();

    /**
     * transferId → 已消费的票据散列集合。
     * WHY 独立记录已消费散列：当旧票被消费时（remove 当前记录），需要确保不会误删新票的记录。
     * 消费逻辑：先比对散列，匹配才移除当前记录并加入已消费集合。
     * 这样再次领票（替换 tickets 中的记录）后，消费旧票不会删除新票的记录。
     */
    private final Map<String, Set<byte[]>> consumedHashes = new ConcurrentHashMap<>();

    public DownloadTicketService(TransferProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties 不得为 null");
    }

    /**
     * 为指定传输任务创建下载票据。
     *
     * <p>若该任务已有旧票据，旧票据自动失效（被替换）。</p>
     *
     * @param transferId    传输任务 ID
     * @param readyDeadline ready 状态的截止时间，票据不得超过此时间
     * @return 明文票据（hex 编码），仅此次返回
     */
    public String issueTicket(String transferId, LocalDateTime readyDeadline) {
        Objects.requireNonNull(transferId, "transferId 不得为 null");
        Objects.requireNonNull(readyDeadline, "readyDeadline 不得为 null");

        // 生成随机票据
        byte[] ticketBytes = new byte[TICKET_BYTES];
        secureRandom.nextBytes(ticketBytes);
        String hexTicket = bytesToHex(ticketBytes);
        byte[] hash = sha256(ticketBytes);
        // 擦除明文副本
        java.util.Arrays.fill(ticketBytes, (byte) 0);

        // 计算过期时间：取 readyDeadline 和 now + ticketTimeout 的较早者
        LocalDateTime maxExpiry = LocalDateTime.now().plusSeconds(properties.getDownloadTicketTimeoutSeconds());
        LocalDateTime expiry = readyDeadline.isBefore(maxExpiry) ? readyDeadline : maxExpiry;

        // WHY 替换旧票记录：再次领票自动使旧票失效
        tickets.put(transferId, new TicketRecord(hash, expiry));
        // 清除旧的已消费记录（新票签发后旧票的已消费记录不再需要）
        consumedHashes.remove(transferId);

        LOG.debug("已签发下载票据: transfer={} expires={}", transferId, expiry);
        return hexTicket;
    }

    /**
     * 验证并消费下载票据。
     *
     * <p>WHY 验证与消费原子化：消费即删除散列，保证票据单次有效。
     * 使用常量时间比较防止时序攻击。
     * 先比对散列再移除，确保消费旧票不会误删新票记录。</p>
     *
     * @param transferId 传输任务 ID
     * @param ticket     待验证的明文票据（hex 编码）
     * @return 验证通过返回 true；票据不存在、已过期、已消费或不匹配返回 false
     */
    public boolean consumeTicket(String transferId, String ticket) {
        if (transferId == null || ticket == null) {
            return false;
        }

        // 解析票据为字节
        byte[] ticketBytes;
        try {
            ticketBytes = hexToBytes(ticket);
        } catch (IllegalArgumentException e) {
            return false;
        }
        byte[] ticketHash = sha256(ticketBytes);
        java.util.Arrays.fill(ticketBytes, (byte) 0);

        // WHY 先检查已消费集合：防止重放攻击
        Set<byte[]> consumed = consumedHashes.get(transferId);
        if (consumed != null) {
            for (byte[] h : consumed) {
                if (MessageDigest.isEqual(h, ticketHash)) {
                    LOG.debug("下载票据已消费（重放）: transfer={}", transferId);
                    return false;
                }
            }
        }

        // 获取当前有效记录（不移除）
        TicketRecord record = tickets.get(transferId);
        if (record == null) {
            return false;
        }

        // 检查是否已过期
        if (LocalDateTime.now().isAfter(record.expiresAt)) {
            LOG.debug("下载票据已过期: transfer={}", transferId);
            return false;
        }

        // WHY 常量时间比较：防止时序攻击
        if (!MessageDigest.isEqual(record.hash, ticketHash)) {
            LOG.debug("下载票据不匹配: transfer={}", transferId);
            return false;
        }

        // 验证通过：原子地移除当前记录并标记为已消费
        // WHY 使用 remove(key, value) 双参数版本：防止并发修改
        boolean removed = tickets.remove(transferId, record);
        if (!removed) {
            // 并发场景下已被其他线程消费
            return false;
        }

        // 记录已消费的散列
        consumedHashes.computeIfAbsent(transferId, k -> ConcurrentHashMap.newKeySet())
                .add(ticketHash);

        return true;
    }

    /**
     * 撤销指定传输任务的票据（取消/完成时调用）。
     */
    public void revokeTicket(String transferId) {
        if (transferId != null) {
            tickets.remove(transferId);
            consumedHashes.remove(transferId);
        }
    }

    /**
     * 检查指定传输任务是否有有效票据（不消费）。
     */
    public boolean hasValidTicket(String transferId) {
        TicketRecord record = tickets.get(transferId);
        return record != null && !LocalDateTime.now().isAfter(record.expiresAt);
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 不可用", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) {
            throw new IllegalArgumentException("十六进制字符串长度必须为偶数");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi == -1 || lo == -1) {
                throw new IllegalArgumentException("非法十六进制字符: index=" + (i * 2));
            }
            bytes[i] = (byte) ((hi << 4) | lo);
        }
        return bytes;
    }

    /** 票据记录：散列 + 过期时间。 */
    private record TicketRecord(byte[] hash, LocalDateTime expiresAt) {
    }
}
