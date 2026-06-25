package com.agentmemory.hooks;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.config.AgentMemoryProperties;
import com.agentmemory.core.Identity;
import com.agentmemory.store.ObservationWriter;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code /hook} ingest pipeline (issue #8): the fire-and-forget path from a parsed
 * {@link HookPayload} to a persisted observation, with bounded-queue backpressure.
 *
 * <h2>Backpressure (invariant #5)</h2>
 * A single worker thread drains a <strong>bounded</strong> {@link ArrayBlockingQueue} to the
 * {@link ObservationWriter}. {@link #ingest(HookPayload)} validates + sanitizes the event and then
 * {@code offer}s the write to the queue; if the queue is saturated the offer fails fast and the
 * caller gets {@link IngestStatus#THROTTLED} (the controller maps it to HTTP 429). The server never
 * grows unbounded work and never blocks the agent's hot path: the offer wait is capped by
 * {@code agent-memory.ingest.offer-timeout-millis} (0 = pure non-blocking), the "hard request
 * budget" the issue requires.
 *
 * <h2>Single-writer ordering (invariant #2)</h2>
 * Exactly one worker thread calls the writer, so writes are serialized end to end; the writer's own
 * lock + the database unique index are the additional cross-cutting guarantees. Sanitization happens
 * on the calling (HTTP) thread <em>before</em> enqueuing, so the privacy strip (DD-010) is on the
 * synchronous path and a malformed event is rejected before it ever touches the queue.
 *
 * <h2>Idempotency &amp; partial-accept</h2>
 * Dedupe is the writer's job (via {@code clientEventId}); this service just routes. For a batch the
 * caller invokes {@link #ingest} per item and records each {@link IngestStatus} independently, so one
 * malformed or throttled event never fails the rest of the drain (the prior-art "batch stalls on one
 * bad event" bug).
 *
 * <p>{@link #awaitIdle(Duration)} lets a test block until the queue has drained and all in-flight
 * writes have completed, making the async pipeline deterministically assertable.
 */
public final class IngestService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final Sanitizer sanitizer;
    private final ObservationWriter writer;
    private final ThreadPoolExecutor worker;
    private final long offerTimeoutMillis;

    /** Count of events accepted but not yet fully written, so tests can await a quiescent pipeline. */
    private final AtomicLong inFlight = new AtomicLong();

    /**
     * Spring entry point.
     *
     * @param config    resolved server config; its {@code ingest()} block tunes the queue.
     * @param sanitizer the privacy boundary (#9) — the only producer of a storable observation.
     * @param writer    the single writer (#4) the worker drains to.
     */
    public IngestService(AgentMemoryConfig config, Sanitizer sanitizer, ObservationWriter writer) {
        this(config.ingest(), sanitizer, writer);
    }

    /**
     * Direct constructor (test-friendly: no Spring context).
     *
     * @param ingest    queue capacity + offer timeout.
     * @param sanitizer the privacy boundary.
     * @param writer    the single writer.
     */
    public IngestService(
            AgentMemoryProperties.Ingest ingest, Sanitizer sanitizer, ObservationWriter writer) {
        this.sanitizer = sanitizer;
        this.writer = writer;
        this.offerTimeoutMillis = ingest.offerTimeoutMillis();
        // One worker thread guarantees serialized writes; the bounded queue gives backpressure. The
        // AbortPolicy is unused because we offer to the queue explicitly and translate a full queue
        // to THROTTLED ourselves (so the wait can be time-boxed), but it is the correct safety net.
        this.worker = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(ingest.queueCapacity()),
                r -> {
                    Thread t = new Thread(r, "agent-memory-ingest");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Validate, sanitize, and enqueue one hook event for persistence.
     *
     * @param payload the parsed hook payload; never null.
     * @return {@link IngestStatus#ACCEPTED} if enqueued, {@link IngestStatus#THROTTLED} if the queue
     *     is saturated, or {@link IngestStatus#INVALID} if the payload could not be assembled into a
     *     storable observation.
     */
    public IngestStatus ingest(HookPayload payload) {
        Sanitized<NewObservation> sanitized;
        try {
            NewObservation raw = toNewObservation(payload);
            sanitized = sanitizer.sanitize(raw); // privacy strip on the synchronous path (DD-010)
        } catch (RuntimeException e) {
            // Malformed/unassemblable event: reject this item only. In a batch the caller keeps going.
            log.debug("rejecting malformed hook event '{}': {}", safeEvent(payload), e.toString());
            return IngestStatus.INVALID;
        }

        inFlight.incrementAndGet();
        boolean enqueued;
        try {
            enqueued = worker.getQueue().offer(
                    () -> runWrite(sanitized), offerTimeoutMillis, TimeUnit.MILLISECONDS);
            if (enqueued) {
                // Nudge the pool to pick up the queued task (ThreadPoolExecutor only spins its single
                // core thread when a task is submitted; offering straight to the queue needs a prod).
                worker.prestartCoreThread();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            inFlight.decrementAndGet();
            return IngestStatus.THROTTLED;
        }
        if (!enqueued) {
            inFlight.decrementAndGet();
            return IngestStatus.THROTTLED; // bounded queue full → 429 (invariant #5)
        }
        return IngestStatus.ACCEPTED;
    }

    private void runWrite(Sanitized<NewObservation> sanitized) {
        try {
            writer.append(sanitized);
        } catch (RuntimeException e) {
            // A write failure must not kill the single worker thread (that would stall all ingest).
            // Log and drop; the client's spool still holds the event and a later drain retries it.
            log.warn("ingest write failed; event dropped (client will retry on next drain): {}",
                    e.toString());
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /**
     * Assemble the unsanitized {@link NewObservation} from a parsed payload. The free-text
     * {@code payload} is a deterministic flattening of the human/tool content; the raw tool
     * input/response JSON is preserved verbatim (an array {@code toolResponse} is kept intact — the
     * prior-art "Bug A") so nothing is lost before sanitization.
     */
    private NewObservation toNewObservation(HookPayload p) {
        Identity identity = Identity.ofProject(p.workspace(), p.project());
        String payloadText = HookPayloadText.flatten(p);
        return new NewObservation(
                p.sessionId(),
                identity,
                p.kind(),
                p.event(),
                p.extension(),
                p.clientEventId(),
                payloadText,
                p.timestamp());
    }

    /**
     * Block until the queue is empty and every accepted event has been written (or has failed). For
     * tests: makes the async pipeline deterministic.
     *
     * @param timeout how long to wait.
     * @return {@code true} if the pipeline went idle within {@code timeout}, {@code false} on timeout.
     */
    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (inFlight.get() > 0 || !worker.getQueue().isEmpty()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    private static String safeEvent(HookPayload p) {
        return p == null ? "<null>" : String.valueOf(p.event());
    }

    /** Drain and stop the worker on shutdown so in-flight writes are not lost abruptly. */
    @PreDestroy
    @Override
    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }
}
