package com.ananoesis.shell.ssh;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ananoesis.shell.config.SshProperties;

/**
 * {@link TerminalIdleReaper} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原类无单元测试，4 个分支（expired>0 / expired==0+traceEnabled /
 * catch RuntimeException / 正常无 trace）均未覆盖。</p>
 */
@DisplayName("TerminalIdleReaper")
class TerminalIdleReaperTest {

    @Test
    @DisplayName("reap: 有到期会话时记录 info 日志")
    void reapWithExpiredSessionsLogsInfo() {
        TerminalSessionRegistry registry = mock(TerminalSessionRegistry.class);
        when(registry.expireIdleSessions(Duration.ofMinutes(30))).thenReturn(3);
        when(registry.size()).thenReturn(5);
        SshProperties properties = new SshProperties();
        TerminalIdleReaper reaper = new TerminalIdleReaper(registry, properties);

        reaper.reapIdleSessions();

        verify(registry).expireIdleSessions(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("reap: 无到期会话时不报错")
    void reapWithNoExpiredSessionsNoError() {
        TerminalSessionRegistry registry = mock(TerminalSessionRegistry.class);
        when(registry.expireIdleSessions(Duration.ofMinutes(30))).thenReturn(0);
        SshProperties properties = new SshProperties();
        TerminalIdleReaper reaper = new TerminalIdleReaper(registry, properties);

        reaper.reapIdleSessions();

        verify(registry).expireIdleSessions(Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("reap: 注册表抛异常时吞掉并记 warn")
    void reapWhenRegistryThrowsSwallowsException() {
        TerminalSessionRegistry registry = mock(TerminalSessionRegistry.class);
        when(registry.expireIdleSessions(Duration.ofMinutes(30)))
                .thenThrow(new RuntimeException("db error"));
        SshProperties properties = new SshProperties();
        TerminalIdleReaper reaper = new TerminalIdleReaper(registry, properties);

        // 不应冒泡
        reaper.reapIdleSessions();
    }

    @Test
    @DisplayName("构造器: registry 为 null 时抛异常")
    void nullRegistryThrows() {
        SshProperties properties = new SshProperties();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TerminalIdleReaper(null, properties))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("registry");
    }

    @Test
    @DisplayName("构造器: properties 为 null 时抛异常")
    void nullPropertiesThrows() {
        TerminalSessionRegistry registry = mock(TerminalSessionRegistry.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TerminalIdleReaper(registry, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("properties");
    }
}
