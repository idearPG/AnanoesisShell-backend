package com.ananoesis.shell.ssh;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

/**
 * {@link SessionRuntime} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原测试覆盖事件缓冲，但构造器 null 校验、close 幂等、
 * disconnectForGracePeriod null 校验、rebind 无任务等分支未覆盖。</p>
 */
@DisplayName("SessionRuntime 分支覆盖")
class SessionRuntimeBranchTest {

    private SshTerminalSession mockTerminalSession(String id) {
        var session = mock(SshTerminalSession.class);
        org.mockito.Mockito.when(session.id()).thenReturn(id);
        return session;
    }

    @Test
    @DisplayName("构造器: hostId 为 null 时抛异常")
    void nullHostIdThrows() {
        var ts = mockTerminalSession(UUID.randomUUID().toString());
        var listener = mock(TerminalOutputListener.class);
        assertThatThrownBy(() -> new SessionRuntime(null, ts, listener, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("hostId");
    }

    @Test
    @DisplayName("构造器: terminalSession 为 null 时抛异常")
    void nullTerminalSessionThrows() {
        var listener = mock(TerminalOutputListener.class);
        assertThatThrownBy(() -> new SessionRuntime(UUID.randomUUID(), null, listener, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("terminalSession");
    }

    @Test
    @DisplayName("构造器: listener 为 null 时抛异常")
    void nullListenerThrows() {
        var ts = mockTerminalSession(UUID.randomUUID().toString());
        assertThatThrownBy(() -> new SessionRuntime(UUID.randomUUID(), ts, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("listener");
    }

    @Test
    @DisplayName("isClosed: 初始为 false")
    void isClosedInitiallyFalse() {
        var hostId = UUID.randomUUID();
        var ts = mockTerminalSession(UUID.randomUUID().toString());
        var listener = mock(TerminalOutputListener.class);
        var runtime = new SessionRuntime(hostId, ts, listener, null);
        assertThat(runtime.isClosed()).isFalse();
    }

    @Test
    @DisplayName("close: reason 为 null 时抛异常")
    void closeNullReasonThrows() {
        var hostId = UUID.randomUUID();
        var ts = mockTerminalSession(UUID.randomUUID().toString());
        var listener = mock(TerminalOutputListener.class);
        var runtime = new SessionRuntime(hostId, ts, listener, null);
        assertThatThrownBy(() -> runtime.close(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("close: 幂等 — 第二次调用不抛异常")
    void closeIdempotent() {
        var hostId = UUID.randomUUID();
        var ts = mockTerminalSession(UUID.randomUUID().toString());
        var listener = mock(TerminalOutputListener.class);
        var runtime = new SessionRuntime(hostId, ts, listener, null);
        runtime.close(SshCloseReason.USER_DISCONNECT);
        runtime.close(SshCloseReason.USER_DISCONNECT); // 幂等
        assertThat(runtime.isClosed()).isTrue();
    }

    @Test
    @DisplayName("disconnectForGracePeriod: gracePeriod 为 null 时抛异常")
    void disconnectNullGracePeriodThrows() {
        var hostId = UUID.randomUUID();
        var ts = mockTerminalSession(UUID.randomUUID().toString());
        var listener = mock(TerminalOutputListener.class);
        var runtime = new SessionRuntime(hostId, ts, listener, null);
        assertThatThrownBy(() -> runtime.disconnectForGracePeriod(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("disconnectForGracePeriod: 已关闭时静默返回")
    void disconnectWhenAlreadyClosedNoOp() {
        var hostId = UUID.randomUUID();
        var ts = mockTerminalSession(UUID.randomUUID().toString());
        var listener = mock(TerminalOutputListener.class);
        var runtime = new SessionRuntime(hostId, ts, listener, null);
        runtime.close(SshCloseReason.USER_DISCONNECT);
        runtime.disconnectForGracePeriod(java.time.Duration.ofSeconds(30));
        // 已关闭，不再调度宽限期任务
    }

    @Test
    @DisplayName("rebind: 无宽限期任务时静默返回")
    void rebindNoTaskReturnsSilently() {
        var hostId = UUID.randomUUID();
        var ts = mockTerminalSession(UUID.randomUUID().toString());
        var listener = mock(TerminalOutputListener.class);
        var runtime = new SessionRuntime(hostId, ts, listener, null);
        runtime.rebind(); // 没有待取消的任务
        assertThat(runtime.isClosed()).isFalse();
    }

    @Test
    @DisplayName("currentEventSeq: 无事件时为 0")
    void currentEventSeqInitiallyZero() {
        var hostId = UUID.randomUUID();
        var ts = mockTerminalSession(UUID.randomUUID().toString());
        var listener = mock(TerminalOutputListener.class);
        var runtime = new SessionRuntime(hostId, ts, listener, null);
        assertThat(runtime.currentEventSeq()).isZero();
    }

    @Test
    @DisplayName("scheduler: 未安装时返回 null")
    void schedulerInitiallyNull() {
        var hostId = UUID.randomUUID();
        var ts = mockTerminalSession(UUID.randomUUID().toString());
        var listener = mock(TerminalOutputListener.class);
        var runtime = new SessionRuntime(hostId, ts, listener, null);
        assertThat(runtime.scheduler()).isNull();
    }
}
