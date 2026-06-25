package com.agentmemory.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.config.AgentMemoryProperties.Ingest;
import com.agentmemory.config.AgentMemoryProperties.Sanitization;
import com.agentmemory.core.Observation;
import com.agentmemory.core.ObservationId;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.Uuid7;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.store.ObservationWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Proves the post-write {@link ObservationListener} seam fires <strong>through the ingest path</strong>
 * (issue #18 wiring): {@link IngestService#ingest} routes a captured event to the worker, and after the
 * write the listener is invoked with the persisted observation — so session consolidation actually
 * runs at session-end instead of being a seam nobody calls. Also proves the seam is best-effort: a
 * throwing listener never breaks ingest (invariant #5). No Spring, no database.
 */
class IngestListenerWiringTest {

    private final Sanitizer sanitizer = new Sanitizer(new Sanitization(65536, List.of()));

    /** A no-op writer that just echoes the observation back. */
    private static final ObservationWriter ECHO_WRITER = IngestListenerWiringTest::echo;

    private static HookPayload payload(String event) {
        return HookPayload.of(
                event,
                SessionId.newId(),
                WorkspaceId.of("acme"),
                ProjectId.of("agent-memory"),
                Instant.parse("2026-06-25T12:00:00Z"));
    }

    private static Observation echo(Sanitized<NewObservation> obs) {
        NewObservation v = obs.value();
        return new Observation(
                new ObservationId(Uuid7.randomUuid()),
                v.sessionId(), v.identity(), v.kind(),
                v.sourceEvent(), v.extension(), v.payload(), v.createdAt());
    }

    @Test
    void listenerIsInvokedThroughTheIngestPathAfterWrite() throws Exception {
        // Record every observation the listener sees. The event "SessionEnd" canonicalizes to
        // ObservationKind.SESSION_END — the kind that triggers consolidation.
        List<NewObservation> seen = new CopyOnWriteArrayList<>();
        ObservationListener listener = seen::add;

        try (IngestService svc = new IngestService(new Ingest(1024, 50), sanitizer, ECHO_WRITER, listener)) {
            assertThat(svc.ingest(payload("SessionEnd"))).isEqualTo(IngestStatus.ACCEPTED);
            assertThat(svc.awaitIdle(Duration.ofSeconds(5))).as("pipeline drains").isTrue();
        }

        // The listener fired exactly once, via the ingest path (not a direct call), with the SESSION_END
        // observation — this is the link that makes session-end synthesis actually happen.
        assertThat(seen).hasSize(1);
        assertThat(seen.get(0).kind()).isEqualTo(ObservationKind.SESSION_END);
        assertThat(seen.get(0).identity().project().value()).isEqualTo("agent-memory");
    }

    @Test
    void listenerSeesEveryWrittenObservationKind() throws Exception {
        List<ObservationKind> kinds = new CopyOnWriteArrayList<>();
        ObservationListener listener = obs -> kinds.add(obs.kind());

        try (IngestService svc = new IngestService(new Ingest(1024, 50), sanitizer, ECHO_WRITER, listener)) {
            svc.ingest(payload("UserPromptSubmit"));
            svc.ingest(payload("SessionEnd"));
            assertThat(svc.awaitIdle(Duration.ofSeconds(5))).isTrue();
        }

        // The listener is invoked for every write; the trigger (not this seam) decides which kinds act.
        assertThat(kinds).containsExactlyInAnyOrder(
                ObservationKind.USER_PROMPT, ObservationKind.SESSION_END);
    }

    @Test
    void aThrowingListenerNeverBreaksIngest() throws Exception {
        // Invariant #5: a post-write listener failure must not fail the write or kill the worker.
        AtomicInteger writes = new AtomicInteger();
        ObservationWriter counting = obs -> {
            writes.incrementAndGet();
            return echo(obs);
        };
        ObservationListener boom = obs -> {
            throw new RuntimeException("listener blew up");
        };

        try (IngestService svc = new IngestService(new Ingest(1024, 50), sanitizer, counting, boom)) {
            assertThat(svc.ingest(payload("SessionEnd"))).isEqualTo(IngestStatus.ACCEPTED);
            assertThat(svc.ingest(payload("SessionEnd"))).isEqualTo(IngestStatus.ACCEPTED);
            assertThat(svc.awaitIdle(Duration.ofSeconds(5))).isTrue();
            // Both writes happened despite the listener throwing each time (worker survived).
            assertThat(writes.get()).isEqualTo(2);
        }
    }

    @Test
    void aSlowListenerDoesNotBlockTheIngestWorker() throws Exception {
        // Invariant #5: a slow listener (a stand-in for an LLM-backed synthesis) must run OFF the
        // ingest worker so it never stalls the queue drain. With the listener blocked, subsequent
        // writes must still be accepted and drained promptly.
        java.util.concurrent.CountDownLatch listenerEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseListener = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        ObservationWriter counting = obs -> {
            writes.incrementAndGet();
            return echo(obs);
        };
        ObservationListener slow = obs -> {
            listenerEntered.countDown();
            try {
                releaseListener.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        try (IngestService svc = new IngestService(new Ingest(1024, 50), sanitizer, counting, slow)) {
            // First event: written, then its listener dispatched and now blocked on the listener thread.
            assertThat(svc.ingest(payload("SessionEnd"))).isEqualTo(IngestStatus.ACCEPTED);
            assertThat(listenerEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .as("listener started (off the worker)").isTrue();

            // The ingest worker must be free even though the listener is blocked: more events are
            // accepted AND their WRITES drain (writes reach 3) without waiting for the listener.
            assertThat(svc.ingest(payload("UserPromptSubmit"))).isEqualTo(IngestStatus.ACCEPTED);
            assertThat(svc.ingest(payload("UserPromptSubmit"))).isEqualTo(IngestStatus.ACCEPTED);

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (writes.get() < 3 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(writes.get())
                    .as("writes drained while the listener was still blocked (worker not stalled)")
                    .isEqualTo(3);

            releaseListener.countDown(); // let the blocked listener finish so close() is clean
            assertThat(svc.awaitIdle(Duration.ofSeconds(5))).isTrue();
        }
    }

    @Test
    void noListenerConfiguredRunsAsBefore() throws Exception {
        // The 3-arg constructor (no listener) must behave exactly like the prior pipeline.
        AtomicInteger writes = new AtomicInteger();
        ObservationWriter counting = obs -> {
            writes.incrementAndGet();
            return echo(obs);
        };
        try (IngestService svc = new IngestService(new Ingest(1024, 50), sanitizer, counting)) {
            assertThat(svc.ingest(payload("SessionEnd"))).isEqualTo(IngestStatus.ACCEPTED);
            assertThat(svc.awaitIdle(Duration.ofSeconds(5))).isTrue();
            assertThat(writes.get()).isEqualTo(1);
        }
    }
}
