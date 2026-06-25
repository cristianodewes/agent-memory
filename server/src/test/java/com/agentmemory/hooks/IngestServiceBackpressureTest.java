package com.agentmemory.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.config.AgentMemoryProperties.Sanitization;
import com.agentmemory.config.AgentMemoryProperties.Ingest;
import com.agentmemory.core.Observation;
import com.agentmemory.core.ObservationId;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.Uuid7;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.store.ObservationWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Backpressure + ordering unit tests for {@link IngestService} — no database. A stand-in
 * {@link ObservationWriter} is used so the queue behaviour can be driven deterministically: a writer
 * that blocks lets us fill the bounded queue and assert {@link IngestStatus#THROTTLED} (HTTP 429),
 * and a counting writer lets us assert every accepted event is written exactly once on the single
 * worker thread.
 */
class IngestServiceBackpressureTest {

    private final Sanitizer sanitizer = new Sanitizer(new Sanitization(65536, List.of()));

    private static HookPayload payload() {
        return HookPayload.of(
                "UserPromptSubmit",
                SessionId.newId(),
                WorkspaceId.of("acme"),
                ProjectId.of("agent-memory"),
                Instant.parse("2026-06-25T12:00:00Z"));
    }

    /** A writer that blocks in append() until released — used to saturate the queue. */
    private static final class BlockingWriter implements ObservationWriter {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);

        @Override
        public Observation append(com.agentmemory.hooks.Sanitized<NewObservation> obs) {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return echo(obs);
        }
    }

    private static Observation echo(com.agentmemory.hooks.Sanitized<NewObservation> obs) {
        NewObservation v = obs.value();
        return new Observation(
                new ObservationId(Uuid7.randomUuid()),
                v.sessionId(), v.identity(), v.kind(),
                v.sourceEvent(), v.extension(), v.payload(), v.createdAt());
    }

    @Test
    void saturatedQueueReturns429() throws Exception {
        BlockingWriter writer = new BlockingWriter();
        // Capacity 1, non-blocking offer: one task occupies the worker, one sits in the queue, the
        // next is rejected. (offerTimeout 0 ⇒ a full queue throttles immediately — the hard budget.)
        try (IngestService svc = new IngestService(new Ingest(1, 0), sanitizer, writer)) {
            // First accepted event is taken by the worker, which then blocks in append().
            assertThat(svc.ingest(payload())).isEqualTo(IngestStatus.ACCEPTED);
            assertThat(writer.entered.await(5, TimeUnit.SECONDS)).isTrue();

            // Worker is now blocked; fill the single queue slot, then the next must be throttled.
            assertThat(svc.ingest(payload())).isEqualTo(IngestStatus.ACCEPTED); // sits in the queue
            IngestStatus third = svc.ingest(payload());
            assertThat(third)
                    .as("a full bounded queue must throttle (429), not block or grow")
                    .isEqualTo(IngestStatus.THROTTLED);

            writer.release.countDown(); // let the worker drain so close() is clean
        }
    }

    @Test
    void everyAcceptedEventIsWrittenExactlyOnce() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        ObservationWriter counting = obs -> {
            writes.incrementAndGet();
            return echo(obs);
        };
        try (IngestService svc = new IngestService(new Ingest(1024, 50), sanitizer, counting)) {
            int n = 200;
            int accepted = 0;
            for (int i = 0; i < n; i++) {
                if (svc.ingest(payload()) == IngestStatus.ACCEPTED) {
                    accepted++;
                }
            }
            assertThat(svc.awaitIdle(Duration.ofSeconds(10))).as("pipeline drains").isTrue();
            assertThat(writes.get())
                    .as("each accepted event written exactly once on the single worker")
                    .isEqualTo(accepted);
        }
    }

    @Test
    void aWriteFailureDoesNotKillTheWorker() throws Exception {
        // The single worker must survive a write that throws (otherwise one bad event stalls ALL
        // ingest). First append throws; the pipeline must still accept and write the next event.
        AtomicInteger calls = new AtomicInteger();
        ObservationWriter flaky = obs -> {
            if (calls.getAndIncrement() == 0) {
                throw new RuntimeException("boom (first write)");
            }
            return echo(obs);
        };
        try (IngestService svc = new IngestService(new Ingest(1024, 50), sanitizer, flaky)) {
            assertThat(svc.ingest(payload())).isEqualTo(IngestStatus.ACCEPTED); // throws in worker
            assertThat(svc.ingest(payload())).isEqualTo(IngestStatus.ACCEPTED); // must still run
            assertThat(svc.awaitIdle(Duration.ofSeconds(5))).isTrue();
            assertThat(calls.get()).as("worker kept draining after a failure").isEqualTo(2);
        }
    }
}
