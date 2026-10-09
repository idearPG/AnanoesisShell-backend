package com.ananoesis.shell.ssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;

/**
 * {@link SshTerminalSession} 的分支覆盖补测。
 *
 * <p>WHY 独立测试文件：原测试覆盖关闭幂等，但构造器 null 校验、send null/empty、
 * resize 已关闭等分支未覆盖。</p>
 */
@DisplayName("SshTerminalSession 分支覆盖")
class SshTerminalSessionBranchTest {

    @Test
    @DisplayName("构造器: id 为 null 时抛异常")
    void nullIdThrows() {
        assertThatThrownBy(() -> new SshTerminalSession(
                null, null, null, null,
                mock(TerminalOutputListener.class),
                reason -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("id");
    }

    @Test
    @DisplayName("构造器: listener 为 null 时抛异常")
    void nullListenerThrows() {
        assertThatThrownBy(() -> new SshTerminalSession(
                "session-1", null, null, null,
                null,
                reason -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("listener");
    }

    @Test
    @DisplayName("构造器: closeHook 为 null 时抛异常")
    void nullCloseHookThrows() {
        assertThatThrownBy(() -> new SshTerminalSession(
                "session-1", null, null, null,
                mock(TerminalOutputListener.class),
                null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("closeHook");
    }

    @Test
    @DisplayName("send: null 数据时静默返回")
    void sendNullDataNoOp() {
        var listener = mock(TerminalOutputListener.class);
        var session = new SshTerminalSession("s1", null, null, null, listener, r -> {});
        session.send(null); // 不抛异常
    }

    @Test
    @DisplayName("send: 空串时静默返回")
    void sendEmptyDataNoOp() {
        var listener = mock(TerminalOutputListener.class);
        var session = new SshTerminalSession("s1", null, null, null, listener, r -> {});
        session.send(""); // 不抛异常
    }

    @Test
    @DisplayName("resize: 已关闭时静默返回")
    void resizeAfterClosedNoOp() {
        var listener = mock(TerminalOutputListener.class);
        var session = new SshTerminalSession("s1", null, null, null, listener, r -> {});
        session.close(SshCloseReason.USER_DISCONNECT);
        session.resize(80, 24); // 不抛异常
    }

    @Test
    @DisplayName("close: reason 为 null 时抛异常")
    void closeNullReasonThrows() {
        var listener = mock(TerminalOutputListener.class);
        var session = new SshTerminalSession("s1", null, null, null, listener, r -> {});
        assertThatThrownBy(() -> session.close(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("close: 幂等 — 第二次调用不抛异常")
    void closeIdempotent() {
        var listener = mock(TerminalOutputListener.class);
        var session = new SshTerminalSession("s1", null, null, null, listener, r -> {});
        session.close(SshCloseReason.USER_DISCONNECT);
        session.close(SshCloseReason.USER_DISCONNECT); // 幂等
        assertThat(session.isOpen()).isFalse();
    }

    @Test
    @DisplayName("isOpen: 初始为 true")
    void isOpenInitiallyTrue() {
        var listener = mock(TerminalOutputListener.class);
        var session = new SshTerminalSession("s1", null, null, null, listener, r -> {});
        assertThat(session.isOpen()).isTrue();
    }

    @Test
    @DisplayName("id: 返回构造时传入的 id")
    void idReturnsConstructorId() {
        var listener = mock(TerminalOutputListener.class);
        var session = new SshTerminalSession("my-session", null, null, null, listener, r -> {});
        assertThat(session.id()).isEqualTo("my-session");
    }

    @Test
    @DisplayName("startReaders: shell 为 null 时静默返回")
    void startReadersNullShellNoOp() {
        var listener = mock(TerminalOutputListener.class);
        var session = new SshTerminalSession("s1", null, null, null, listener, r -> {});
        session.startReaders(); // shell 为 null，不抛异常
    }
}
