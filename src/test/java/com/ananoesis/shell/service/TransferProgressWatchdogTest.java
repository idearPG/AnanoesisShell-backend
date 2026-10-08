package com.ananoesis.shell.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 传输无进度 watchdog 的内存判定单元测试（sftp-transfer spec：无进度超时）。
 *
 * <p>WHY 用可注入假时钟而非真实等待：spec 的「连续 300 秒无字节进展」边界语义
 * 必须被精确验证（恰等于阈值即到期、差一纳秒不到期），真实时钟做不到——
 * 与 TerminalSessionRegistry.expireIdleSessions 的可测性设计同款思路。</p>
 */
@DisplayName("TransferProgressWatchdog（无进度追踪判定）")
class TransferProgressWatchdogTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(300);

    /** 假时钟：测试直接推动"当前时刻"（nanoTime 语义）。 */
    private final AtomicLong now = new AtomicLong(0L);
    private final TransferProgressWatchdog watchdog = new TransferProgressWatchdog(now::get);

    @BeforeEach
    void resetClock() {
        now.set(0L);
    }

    /** 推进假时钟（秒）。 */
    private void advanceSeconds(long seconds) {
        now.addAndGet(seconds * 1_000_000_000L);
    }

    @Test
    @DisplayName("注册后阈值内未停滞：299 秒不判定")
    void freshTransferBelowTimeoutIsNotStalled() {
        watchdog.register("t1");

        advanceSeconds(TIMEOUT.toSeconds() - 1);

        assertThat(watchdog.findStalled(TIMEOUT)).as("阈值内的传输绝不误判").isEmpty();
        assertThat(watchdog.trackedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("连续无进展恰达阈值即判定停滞（>= 语义，与 spec「连续 300 秒」一致）")
    void exactlyAtTimeoutIsStalled() {
        watchdog.register("t1");

        advanceSeconds(TIMEOUT.toSeconds());

        assertThat(watchdog.findStalled(TIMEOUT)).containsExactly("t1");
    }

    @Test
    @DisplayName("字节进展续命：停滞计时从最后一次进展重新起算")
    void progressExtendsDeadlineFromLastByte() {
        watchdog.register("t1");

        advanceSeconds(250);
        watchdog.markProgress("t1"); // 传输仍在动：transferred_bytes 增加
        advanceSeconds(250);         // 距上次进展 250s < 300s

        assertThat(watchdog.findStalled(TIMEOUT)).as("有进展的传输绝不误判").isEmpty();

        advanceSeconds(50);          // 距上次进展恰 300s
        assertThat(watchdog.findStalled(TIMEOUT)).containsExactly("t1");
    }

    @Test
    @DisplayName("持续进展十分钟（10 轮 × 60 秒续命）永不误判")
    void continuousProgressNeverStalls() {
        watchdog.register("t1");

        for (int round = 0; round < 10; round++) {
            advanceSeconds(60);
            watchdog.markProgress("t1");
        }

        assertThat(watchdog.findStalled(TIMEOUT)).isEmpty();
        assertThat(watchdog.trackedCount()).as("持续追踪同一传输").isEqualTo(1);
    }

    @Test
    @DisplayName("unregister 后离开判定；迟到的读回调不得复活条目（防泄漏）")
    void unregisterRemovesAndLateProgressDoesNotRevive() {
        watchdog.register("t1");
        advanceSeconds(100);

        watchdog.unregister("t1");

        assertThat(watchdog.trackedCount()).isZero();
        assertThat(watchdog.findStalled(TIMEOUT)).isEmpty();

        // 传输已终态后，控制器关闭下载流前后可能有在途读回调到达 markProgress：
        // 若无条件写入，摘除的条目会被幽灵复活且永远无人再摘除
        watchdog.markProgress("t1");

        assertThat(watchdog.trackedCount()).as("unregister 必须是唯一生命周期终点").isZero();
    }

    @Test
    @DisplayName("多传输互相独立：只停滞的到期，有进展的存活")
    void transfersAreJudgedIndependently() {
        watchdog.register("t1");
        watchdog.register("t2");

        advanceSeconds(100);
        watchdog.markProgress("t1");
        advanceSeconds(250); // t2 距注册 350s 停滞到期；t1 距进展仅 250s

        assertThat(watchdog.findStalled(TIMEOUT)).containsExactly("t2");
        assertThat(watchdog.trackedCount()).isEqualTo(2);
    }
}
