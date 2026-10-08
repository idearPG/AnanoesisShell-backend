package com.ananoesis.shell.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.springframework.stereotype.Component;

/**
 * 传输字节进展追踪器——SFTP「无进度超时」watchdog 的内存判定半边（sftp-transfer spec）。
 *
 * <p>职责单一：只回答「哪些 transferring 传输已经连续 {@code progressTimeoutSeconds}
 * 没有任何字节进展」。它不查库、不做网络 I/O、不改状态——判定后的失败落库与配额释放由
 * {@link TransferService#expireStalledTransfers()} 完成，两者通过 transferId 解耦，
 * 保证扫描路径上没有任何可能长时间阻塞的操作。</p>
 *
 * <p>WHY 内存态而非定时读库比对 transferred_bytes：上传循环每个缓冲块都会落库一次进度，
 * 下载却是控制器流式转发、全程不更新 transferred_bytes（避免写放大）——
 * 只有读写循环自己知道「此刻有没有字节在动」。循环内打点 + 定时扫描的组合
 * 与终端空闲回收（TerminalSessionRegistry + TerminalIdleReaper）同构：
 * 打点/续命/摘除全是内存操作，扫描线程只做纯计算。</p>
 *
 * <p>WHY nanoTime 而非墙钟：超时判定关心的是「流逝时长」，校时/回拨会制造
 * 虚假的进展或虚假的停滞；mono 时钟不受影响。时钟源可注入（LongSupplier），
 * 测试用假时钟精确验证「300 秒边界」而无需真实等待。</p>
 */
@Component
public class TransferProgressWatchdog {

    /** transferId → 最近一次字节进展时刻（nanoTime），有进展即覆盖写入。 */
    private final Map<String, Long> lastProgressNanos = new ConcurrentHashMap<>();

    /** 时钟源（生产为 System::nanoTime，测试注入假时钟）。 */
    private final LongSupplier clock;

    public TransferProgressWatchdog() {
        this(System::nanoTime);
    }

    public TransferProgressWatchdog(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock 不得为 null");
    }

    /**
     * 传输进入 transferring 时注册追踪。
     *
     * <p>WHY register 与 markProgress 分开：注册后到第一个字节之间也算「在传输」，
     * 停滞计时从注册时刻起算——连接建立后远端一直不吐字节的假死同样能被发现。</p>
     */
    public void register(String transferId) {
        Objects.requireNonNull(transferId, "transferId 不得为 null");
        lastProgressNanos.put(transferId, clock.getAsLong());
    }

    /**
     * 字节有进展时续命（每次 transferred_bytes 增加时调用）。
     *
     * <p>WHY {@code computeIfPresent} 而非无条件 put：传输终态后控制器才关闭下载流，
     * 关闭前后可能有已在途的读回调到达——无条件写入会让已摘除的条目被幽灵复活，
     * 且永远无人再摘除（内存泄漏，只能靠 DB 状态兜底）。只更新存活条目，
     * 让 {@link #unregister} 成为唯一的生命周期终点。</p>
     */
    public void markProgress(String transferId) {
        Objects.requireNonNull(transferId, "transferId 不得为 null");
        lastProgressNanos.computeIfPresent(transferId, (id, previous) -> clock.getAsLong());
    }

    /** 传输到终态时摘除（幂等：条目不存在时为空操作）。 */
    public void unregister(String transferId) {
        if (transferId != null) {
            lastProgressNanos.remove(transferId);
        }
    }

    /**
     * 找出已停滞超过 {@code timeout} 的传输。
     *
     * <p>边界语义与 spec「连续 300 秒无字节进展则判定传输失败」一致：
     * 停滞时长恰等于 timeout 即视为到期（与 TerminalSessionRegistry 的
     * {@code !isAfter(cutoff)} 同款包含式边界）。</p>
     *
     * @return 停滞的 transferId 快照（顺序不保证）
     */
    public List<String> findStalled(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout 不得为 null");
        long timeoutNanos = timeout.toNanos();
        long now = clock.getAsLong();
        List<String> stalled = new ArrayList<>();
        for (Map.Entry<String, Long> entry : lastProgressNanos.entrySet()) {
            if (now - entry.getValue() >= timeoutNanos) {
                stalled.add(entry.getKey());
            }
        }
        return stalled;
    }

    /** 当前追踪中的传输数（监控与测试用）。 */
    public int trackedCount() {
        return lastProgressNanos.size();
    }
}
