package com.ananoesis.shell.ssh;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * SessionRuntime unit tests (task 4.1 + 4.4).
 */
@DisplayName("SessionRuntime")
class SessionRuntimeTest {

    private static final UUID HOST_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private StubTerminalSession stubTerminal;
    private TerminalOutputListener stubListener;

    @BeforeEach
    void setUp() {
        stubTerminal = new StubTerminalSession();
        stubListener = new StubOutputListener();
    }

    // ==================================================================
    // 4.1 multi-instance isolation
    // ==================================================================

    @Nested
    @DisplayName("multi-instance isolation")
    class MultiInstanceIsolation {

        @Test
        @DisplayName("same host produces different session_ids")
        void sameHostProducesDifferentSessionIds() {
            SessionRuntime runtime1 = new SessionRuntime(HOST_ID,
                    new StubTerminalSession(), stubListener, null);
            SessionRuntime runtime2 = new SessionRuntime(HOST_ID,
                    new StubTerminalSession(), stubListener, null);

            assertThat(runtime1.sessionId()).isNotEqualTo(runtime2.sessionId());
        }

        @Test
        @DisplayName("closing one does not affect other")
        void closingOneDoesNotAffectOther() {
            SessionRuntime runtime1 = new SessionRuntime(HOST_ID,
                    new StubTerminalSession(), stubListener, null);
            SessionRuntime runtime2 = new SessionRuntime(HOST_ID,
                    new StubTerminalSession(), stubListener, null);

            runtime1.close(SshCloseReason.USER_DISCONNECT);

            assertThat(runtime1.isClosed()).isTrue();
            assertThat(runtime2.isClosed()).isFalse();
        }

        @Test
        @DisplayName("each instance holds independent terminal reference")
        void eachInstanceHoldsIndependentTerminal() {
            StubTerminalSession terminal1 = new StubTerminalSession();
            StubTerminalSession terminal2 = new StubTerminalSession();

            SessionRuntime runtime1 = new SessionRuntime(HOST_ID, terminal1, stubListener, null);
            SessionRuntime runtime2 = new SessionRuntime(HOST_ID, terminal2, stubListener, null);

            runtime1.close(SshCloseReason.USER_DISCONNECT);

            assertThat(terminal1.closed).isTrue();
            assertThat(terminal2.closed).isFalse();
        }
    }

    // ==================================================================
    // 4.4 disconnect grace & cleanup
    // ==================================================================

    @Nested
    @DisplayName("disconnect grace and cleanup")
    class DisconnectGraceAndCleanup {

        @Test
        @DisplayName("explicit close triggers immediate cleanup")
        void explicitCloseImmediateCleanup() {
            AtomicReference<String> cleanedUp = new AtomicReference<>();
            SessionRuntime runtime = new SessionRuntime(HOST_ID,
                    new StubTerminalSession(), stubListener, cleanedUp::set);

            runtime.close(SshCloseReason.USER_DISCONNECT);

            assertThat(runtime.isClosed()).isTrue();
            assertThat(cleanedUp.get()).isEqualTo(runtime.sessionId().toString());
        }

        @Test
        @DisplayName("explicit close is idempotent")
        void explicitCloseIsIdempotent() {
            java.util.concurrent.atomic.AtomicInteger cleanupCount =
                    new java.util.concurrent.atomic.AtomicInteger();
            SessionRuntime runtime = new SessionRuntime(HOST_ID,
                    new StubTerminalSession(), stubListener,
                    id -> cleanupCount.incrementAndGet());

            runtime.close(SshCloseReason.USER_DISCONNECT);
            runtime.close(SshCloseReason.USER_DISCONNECT);

            assertThat(cleanupCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("disconnect enters grace period, runtime stays open")
        void disconnectEntersGracePeriod() {
            SessionRuntime runtime = new SessionRuntime(HOST_ID,
                    new StubTerminalSession(), stubListener, null);

            runtime.disconnectForGracePeriod(Duration.ofSeconds(30));

            assertThat(runtime.isClosed()).isFalse();
        }

        @Test
        @DisplayName("grace period timeout triggers cleanup")
        void gracePeriodTimeoutTriggersCleanup() throws Exception {
            AtomicReference<String> cleanedUp = new AtomicReference<>();
            SessionRuntime runtime = new SessionRuntime(HOST_ID,
                    new StubTerminalSession(), stubListener, cleanedUp::set);

            runtime.disconnectForGracePeriod(Duration.ofMillis(100));

            assertThat(runtime.isClosed()).isFalse();

            Thread.sleep(300);

            assertThat(runtime.isClosed()).isTrue();
            assertThat(cleanedUp.get()).isEqualTo(runtime.sessionId().toString());
        }

        @Test
        @DisplayName("reconnect cancels cleanup")
        void reconnectCancelsCleanup() throws Exception {
            AtomicReference<String> cleanedUp = new AtomicReference<>();
            SessionRuntime runtime = new SessionRuntime(HOST_ID,
                    new StubTerminalSession(), stubListener, cleanedUp::set);

            runtime.disconnectForGracePeriod(Duration.ofMillis(100));
            runtime.rebind();

            Thread.sleep(250);

            assertThat(runtime.isClosed()).isFalse();
            assertThat(cleanedUp.get()).isNull();
        }

        @Test
        @DisplayName("grace period close does not affect other runtime")
        void gracePeriodCloseDoesNotAffectOther() throws Exception {
            SessionRuntime runtime1 = new SessionRuntime(HOST_ID,
                    new StubTerminalSession(), stubListener, null);
            SessionRuntime runtime2 = new SessionRuntime(HOST_ID,
                    new StubTerminalSession(), stubListener, null);

            runtime1.disconnectForGracePeriod(Duration.ofMillis(50));

            Thread.sleep(200);

            assertThat(runtime1.isClosed()).isTrue();
            assertThat(runtime2.isClosed()).isFalse();

            runtime2.close(SshCloseReason.USER_DISCONNECT);
        }
    }

    // ==================================================================
    // Stubs
    // ==================================================================

    /**
     * 测试桩：允许 null SSH 资源的 SshTerminalSession 子类。
     *
     * <p>WHY id 使用纯 UUID 格式：SessionRuntime 构造器从 terminalSession.id()
     * 解析 UUID，因此桩的 id 必须是合法的 UUID 字符串。</p>
     */
    static class StubTerminalSession extends SshTerminalSession {
        volatile boolean closed = false;

        StubTerminalSession() {
            super(UUID.randomUUID().toString(), null, null, null,
                    new StubOutputListener(), reason -> { });
        }

        @Override
        public void close(SshCloseReason reason) {
            closed = true;
        }

        @Override
        public boolean isOpen() {
            return !closed;
        }

        @Override
        public void send(String data) { }

        @Override
        public void resize(int columns, int rows) { }
    }

    static class StubOutputListener implements TerminalOutputListener {
        @Override public void onStdout(String data) { }
        @Override public void onStderr(String data) { }
        @Override public void onClosed(SshCloseReason reason) { }
    }
}
