package com.ananoesis.shell.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PtyCommandGateway} 的单元测试。
 */
class PtyCommandGatewayTest {

    @Test
    @DisplayName("isNestedShell: 会话不存在时返回 false")
    void isNestedShellReturnsFalseWhenSessionNotFound() {
        SshTerminalService terminalService = mock(SshTerminalService.class);
        when(terminalService.findRuntime("nonexistent")).thenReturn(null);
        PtyCommandGateway gateway = new PtyCommandGateway(terminalService);

        assertThat(gateway.isNestedShell("nonexistent")).isFalse();
    }

    @Test
    @DisplayName("isNestedShell: sessionId 为 null 时返回 false")
    void isNestedShellReturnsFalseWhenSessionIdNull() {
        SshTerminalService terminalService = mock(SshTerminalService.class);
        PtyCommandGateway gateway = new PtyCommandGateway(terminalService);

        assertThat(gateway.isNestedShell(null)).isFalse();
    }

    @Test
    @DisplayName("isNestedShell: 调度器未安装时返回 false")
    void isNestedShellReturnsFalseWhenSchedulerNull() {
        SshTerminalService terminalService = mock(SshTerminalService.class);
        SessionRuntime runtime = mock(SessionRuntime.class);
        when(terminalService.findRuntime("session-1")).thenReturn(runtime);
        when(runtime.scheduler()).thenReturn(null);
        PtyCommandGateway gateway = new PtyCommandGateway(terminalService);

        assertThat(gateway.isNestedShell("session-1")).isFalse();
    }
}
