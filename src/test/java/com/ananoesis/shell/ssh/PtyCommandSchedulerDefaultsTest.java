package com.ananoesis.shell.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PtyCommandScheduler 默认超时值的 spec 对齐钉子（v0.2.0）。
 *
 * <p>WHY 单独钉默认值：空闲超时从 60 秒提升为 120 秒（ssh-connection spec：
 * 「空闲超时（无 stdout/stderr 输出）SHALL 为 120 秒作为心跳检测」）。
 * 行为类测试全部使用可配置构造器（短超时加速），唯一钉住默认值的地方就在这里——
 * 若有人把常量改回去，此处立即红，而不是等到线上下载假死检测变敏感/失灵才被发现。</p>
 */
@DisplayName("PtyCommandScheduler 默认超时与 spec 对齐")
class PtyCommandSchedulerDefaultsTest {

    @Test
    @DisplayName("默认空闲超时为 120 秒（ssh-connection spec：心跳检测，v0.2.0 由 60 秒提升）")
    void defaultIdleTimeoutIs120SecondsPerSpec() {
        assertThat(PtyCommandScheduler.DEFAULT_TIMEOUT_MS)
                .as("空闲超时应与 ssh-connection spec 的 120 秒心跳检测一致")
                .isEqualTo(120_000L);
    }

    @Test
    @DisplayName("绝对执行上限保持 30 分钟（防跑飞兜底，与执行超时 1800s 对齐）")
    void absoluteCapStaysAt30Minutes() {
        assertThat(PtyCommandScheduler.DEFAULT_MAX_ABSOLUTE_MS)
                .as("绝对上限应与 run_command 执行超时 1800 秒一致")
                .isEqualTo(1_800_000L);
    }
}
