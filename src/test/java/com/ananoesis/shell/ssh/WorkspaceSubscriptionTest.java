package com.ananoesis.shell.ssh;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ananoesis.shell.ssh.SessionRuntimeTest.StubOutputListener;
import com.ananoesis.shell.ssh.SessionRuntimeTest.StubTerminalSession;

/**
 * Workspace event subscription tests (task 4.3).
 */
@DisplayName("Workspace subscription")
class WorkspaceSubscriptionTest {

    private static final UUID HOST_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private SessionRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = new SessionRuntime(HOST_ID,
                new StubTerminalSession(), new StubOutputListener(), null);
    }

    @Nested
    @DisplayName("event sequencing")
    class EventSequencing {

        @Test
        @DisplayName("event seq monotonically increases")
        void eventSeqMonotonicallyIncreases() {
            long seq1 = runtime.recordEvent("stdout", "hello");
            long seq2 = runtime.recordEvent("stdout", "world");
            long seq3 = runtime.recordEvent("stderr", "error");

            assertThat(seq1).isEqualTo(1L);
            assertThat(seq2).isEqualTo(2L);
            assertThat(seq3).isEqualTo(3L);
        }

        @Test
        @DisplayName("new subscription gets all buffered events")
        void newSubscriptionGetsAllBufferedEvents() {
            runtime.recordEvent("stdout", "line1");
            runtime.recordEvent("stdout", "line2");

            Map<String, Object> snapshot = runtime.subscribe(0L);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events =
                    (List<Map<String, Object>>) snapshot.get("events");
            assertThat(events).hasSize(2);
            assertThat(eventSeq(events.get(0))).isEqualTo(1L);
            assertThat(eventSeq(events.get(1))).isEqualTo(2L);
        }
    }

    @Nested
    @DisplayName("resubscription")
    class Resubscription {

        @Test
        @DisplayName("resubscription has no duplicates")
        void resubscriptionHasNoDuplicates() {
            runtime.recordEvent("stdout", "line1");
            runtime.recordEvent("stdout", "line2");

            Map<String, Object> snapshot = runtime.subscribe(1L);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events =
                    (List<Map<String, Object>>) snapshot.get("events");
            assertThat(events).hasSize(1);
            assertThat(eventSeq(events.get(0))).isEqualTo(2L);
        }

        @Test
        @DisplayName("subscribe from latest returns no events")
        void subscribeFromLatestReturnsNoEvents() {
            runtime.recordEvent("stdout", "line1");
            runtime.recordEvent("stdout", "line2");

            Map<String, Object> snapshot = runtime.subscribe(2L);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events =
                    (List<Map<String, Object>>) snapshot.get("events");
            assertThat(events).isEmpty();
        }
    }

    @Nested
    @DisplayName("tail buffer and gap detection")
    class TailBufferAndGap {

        @Test
        @DisplayName("gap reported when buffer exceeded")
        void gapReportedWhenBufferExceeded() {
            String largePayload = "x".repeat(600_000);
            runtime.recordEvent("stdout", largePayload);
            runtime.recordEvent("stdout", largePayload);

            Map<String, Object> snapshot = runtime.subscribe(0L);

            assertThat(snapshot.get("gap_detected")).isEqualTo(true);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> events =
                    (List<Map<String, Object>>) snapshot.get("events");
            assertThat(events).hasSize(1);
            assertThat(eventSeq(events.get(0))).isEqualTo(2L);
        }

        @Test
        @DisplayName("no gap when within buffer")
        void noGapWhenWithinBuffer() {
            runtime.recordEvent("stdout", "small1");
            runtime.recordEvent("stdout", "small2");

            Map<String, Object> snapshot = runtime.subscribe(0L);

            assertThat(snapshot.get("gap_detected")).isNull();
        }

        @Test
        @DisplayName("gap snapshot includes latest seq")
        void gapSnapshotIncludesLatestSeq() {
            String largePayload = "x".repeat(600_000);
            runtime.recordEvent("stdout", largePayload);
            runtime.recordEvent("stdout", largePayload);

            Map<String, Object> snapshot = runtime.subscribe(0L);

            assertThat(snapshot.get("latest_seq")).isEqualTo(2L);
        }
    }

    private static long eventSeq(Map<String, Object> event) {
        return ((Number) event.get("seq")).longValue();
    }
}
