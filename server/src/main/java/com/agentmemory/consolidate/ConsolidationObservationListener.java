package com.agentmemory.consolidate;

import com.agentmemory.core.Observation;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.SessionId;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ingest post-write listener that drives session consolidation (issue #18 / #32). It is registered
 * on {@code IngestService} via {@code addPostWriteListener} (the additive listener fan-out introduced
 * in #22, so it coexists with the handoff trigger rather than overwriting a single slot), and on every
 * persisted observation it forwards the session id and kind to the {@link SessionConsolidationTrigger},
 * which (re)synthesizes the session when the kind is a trigger ({@code session-end}/{@code pre-compact})
 * and ignores everything else.
 *
 * <p>This is the wiring that actually <em>connects</em> the synthesis trigger to capture — without it
 * the trigger is a seam nothing invokes and session-end synthesis never runs.
 *
 * <h2>Off the ingest worker (invariant #5)</h2>
 * {@code addPostWriteListener} listeners run <em>on the single ingest worker thread</em>, after each
 * successful write. Session synthesis does DB reads + a blocking LLM call + a git-committing page
 * write; running that inline on the worker would stall the bounded-queue drain for the whole LLM
 * round-trip and saturate ingest into 429s for unrelated events. So this listener only does the cheap
 * kind check on the worker and <strong>dispatches</strong> the actual synthesis to a dedicated
 * single-thread executor. One thread keeps synthesis serialized; the backlog is bounded and a
 * saturated backlog drops the task (best-effort — synthesis is idempotent and re-fires on the next
 * trigger) rather than blocking ingest.
 *
 * <p>Best-effort throughout: {@link SessionConsolidationTrigger#onObservation} catches and logs any
 * synthesis failure, and the dispatch itself never throws back into the worker. {@link AutoCloseable}
 * so the executor is shut down with the application context.
 */
public final class ConsolidationObservationListener implements Consumer<Observation>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConsolidationObservationListener.class);

    /** Bounded synthesis backlog; keeps the dispatch non-blocking yet bounded (drops on overflow). */
    private static final int QUEUE_CAPACITY = 256;

    private final SessionConsolidationTrigger trigger;
    private final ThreadPoolExecutor executor;

    public ConsolidationObservationListener(SessionConsolidationTrigger trigger) {
        this.trigger = trigger;
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "agent-memory-consolidation");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void accept(Observation observation) {
        if (observation == null) {
            return;
        }
        // Cheap policy check on the worker thread: only triggering kinds cost a dispatch; every other
        // kind returns immediately so non-triggering events never touch the executor.
        ObservationKind kind = observation.kind();
        if (!SessionConsolidationTrigger.triggers(kind)) {
            return;
        }
        // Capture only the two fields the task needs (not the whole Observation, which carries the
        // payload) so a queued task pins nothing extra.
        SessionId sessionId = observation.sessionId();
        try {
            // Run synthesis OFF the ingest worker so the blocking LLM call cannot stall the queue drain
            // (invariant #5). onObservation is itself best-effort (it logs and swallows any synthesis
            // failure), so the task body never escalates.
            executor.execute(() -> trigger.onObservation(sessionId, kind));
        } catch (RejectedExecutionException reject) {
            if (executor.isShutdown()) {
                // Shutting down (context stopping): not a backlog problem, and there is no "next
                // trigger" to re-fire on — just note it and move on.
                log.debug("consolidation executor is shutting down; skipped synthesis for session {} ({})",
                        sessionId, kind.wire());
            } else {
                // Backlog saturated: drop rather than block ingest. The next session-end/pre-compact
                // re-fires and synthesis is idempotent, so no work is permanently lost.
                log.warn("consolidation backlog saturated; dropped synthesis for session {} ({}) "
                        + "(will re-fire on the next trigger)", sessionId, kind.wire());
            }
        }
    }

    /** Shut the synthesis executor down with the context, letting an in-flight synthesis finish briefly. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
