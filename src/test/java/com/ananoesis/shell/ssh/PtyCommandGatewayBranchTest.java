package com.ananoesis.shell.ssh;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ananoesis.shell.ssh.PtyCommandScheduler.CommandResult;

/**
 * {@link PtyCommandGateway} 的完整分支覆盖补测。
 *
 * <p>WHY 在原文件基础上扩展：原文件仅覆盖 isNestedShell 的三个分支，
 * submit / findScheduler 的 null 校验与会话不存在路径均未覆盖。</p>
 */
class PtyCommandGatewayBranchTest {

    // ==================================================================
    // submit
    // ==================================================================

    @Nested
    @DisplayName("submit")
    class SubmitTests {

        @Test
        @DisplayName("sessionId 为 null 时抛异常")
        void nullSessionIdThrows() {
            SshTerminalService terminalService = mock(SshTerminalService.class);
            PtyCommandGateway gateway = new PtyCommandGateway(terminalService);

            assertThatThrownBy(() -> gateway.submit(null, "ls"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("sessionId");
        }

        @Test
        @DisplayName("command 为 null 时抛异常")
        void nullCommandThrows() {
            SshTerminalService terminalService = mock(SshTerminalService.class);
            PtyCommandGateway gateway = new PtyCommandGateway(terminalService);

            assertThatThrownBy(() -> gateway.submit("session-1", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("command");
        }

        @Test
        @DisplayName("会话不存在时抛异常")
        void sessionNotFoundThrows() {
            SshTerminalService terminalService = mock(SshTerminalService.class);
            when(terminalService.findRuntime("nonexistent")).thenReturn(null);
            PtyCommandGateway gateway = new PtyCommandGateway(terminalService);

            assertThatThrownBy(() -> gateway.submit("nonexistent", "ls"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不存在");
        }

        @Test
        @DisplayName("调度器未安装时抛异常")
        void schedulerNullThrows() {
            SshTerminalService terminalService = mock(SshTerminalService.class);
            SessionRuntime runtime = mock(SessionRuntime.class);
            when(terminalService.findRuntime("session-1")).thenReturn(runtime);
            when(runtime.scheduler()).thenReturn(null);
            PtyCommandGateway gateway = new PtyCommandGateway(terminalService);

            assertThatThrownBy(() -> gateway.submit("session-1", "ls"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("未安装");
        }

        @Test
        @DisplayName("正常路径：提交到调度器并返回 future")
        void normalPathSubmitsToScheduler() {
            SshTerminalService terminalService = mock(SshTerminalService.class);
            SessionRuntime runtime = mock(SessionRuntime.class);
            PtyCommandScheduler scheduler = mock(PtyCommandScheduler.class);
            when(terminalService.findRuntime("session-1")).thenReturn(runtime);
            when(runtime.scheduler()).thenReturn(scheduler);
            CompletableFuture<CommandResult> expected = new CompletableFuture<>();
            when(scheduler.submitCommand("ls")).thenReturn(expected);
            PtyCommandGateway gateway = new PtyCommandGateway(terminalService);

            CompletableFuture<CommandResult> actual = gateway.submit("session-1", "ls");

            assertThat(actual).isSameAs(expected);
        }
    }

    // ==================================================================
    // findScheduler
    // ==================================================================

    @Nested
    @DisplayName("findScheduler")
    class FindSchedulerTests {

        @Test
        @DisplayName("sessionId 为 null 时返回 null")
        void nullSessionIdReturnsNull() {
            SshTerminalService terminalService = mock(SshTerminalService.class);
            PtyCommandGateway gateway = new PtyCommandGateway(terminalService);

            assertThat(gateway.findScheduler(null)).isNull();
        }

        @Test
        @DisplayName("会话不存在时返回 null")
        void sessionNotFoundReturnsNull() {
            SshTerminalService terminalService = mock(SshTerminalService.class);
            when(terminalService.findRuntime("nonexistent")).thenReturn(null);
            PtyCommandGateway gateway = new PtyCommandGateway(terminalService);

            assertThat(gateway.findScheduler("nonexistent")).isNull();
        }

        @Test
        @DisplayName("正常路径：返回调度器引用")
        void normalPathReturnsScheduler() {
            SshTerminalService terminalService = mock(SshTerminalService.class);
            SessionRuntime runtime = mock(SessionRuntime.class);
            PtyCommandScheduler scheduler = mock(PtyCommandScheduler.class);
            when(terminalService.findRuntime("session-1")).thenReturn(runtime);
            when(runtime.scheduler()).thenReturn(scheduler);
            PtyCommandGateway gateway = new PtyCommandGateway(terminalService);

            assertThat(gateway.findScheduler("session-1")).isSameAs(scheduler);
        }
    }

    // ==================================================================
    // 构造器
    // ==================================================================

    @Test
    @DisplayName("构造器: terminalService 为 null 时抛异常")
    void nullTerminalServiceThrows() {
        assertThatThrownBy(() -> new PtyCommandGateway(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("terminalService");
    }
}
