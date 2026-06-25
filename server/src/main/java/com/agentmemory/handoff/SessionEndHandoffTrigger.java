package com.agentmemory.handoff;

import com.agentmemory.core.Observation;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.SessionId;
import com.agentmemory.recall.Scope;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens an LLM-written handoff when a {@code session-end} observation lands (ARCHITECTURE §3.4;
 * issue #22 acceptance: "session-end opens an LLM-written handoff"). Registered on
 * {@code IngestService} via {@code addPostWriteListener} (the listeners fan out, so it coexists with
 * session consolidation's trigger, #18/#32), so it runs after the event is durably captured — off the
 * HTTP hot path and outside the write transaction.
 *
 * <p>Only {@link ObservationKind#SESSION_END} events trigger generation; every other kind is ignored
 * cheaply. A generation failure is swallowed (logged): the capture already succeeded, and a client
 * can still open a handoff explicitly via {@code POST /handoff} / {@code memory_handoff_begin}, so a
 * transient LLM hiccup must not break ingest.
 *
 * <h2>Off the ingest worker (invariant #5, issue #78)</h2>
 * {@code addPostWriteListener} listeners run <em>on the single ingest worker thread</em>, after each
 * successful write. Handoff generation does a session-observations read + a blocking LLM call + a DB
 * write; running that inline on the worker would stall the bounded-queue drain for the whole LLM
 * round-trip and saturate ingest into 429s for unrelated events. So this listener does only the cheap
 * {@code SESSION_END} check on the worker and <strong>dispatches</strong> the actual
 * {@link HandoffService#begin} to a dedicated single-thread executor — mirroring
 * {@code ConsolidationObservationListener}, the sibling session-end reaction. One thread keeps
 * generation serialized; the backlog is bounded and a saturated backlog drops the task (best-effort —
 * {@code begin} supersedes any prior open handoff and re-fires on the next session-end, so no work is
 * permanently lost) rather than blocking ingest.
 *
 * <p>Best-effort throughout: a generation failure is caught and logged, and the dispatch itself never
 * throws back into the worker. {@link AutoCloseable} so the executor is shut down with the application
 * context.
 */
public class SessionEndHandoffTrigger implements Consumer<Observation>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionEndHandoffTrigger.class);

    /** Bounded generation backlog; keeps the dispatch non-blocking yet bounded (drops on overflow). */
    private static final int QUEUE_CAPACITY = 256;

    private final HandoffService handoffs;
    private final ThreadPoolExecutor executor;

    public SessionEndHandoffTrigger(HandoffService handoffs) {
        this.handoffs = handoffs;
        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "agent-memory-handoff");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public void accept(Observation observation) {
        if (observation == null || observation.kind() != ObservationKind.SESSION_END) {
            return;
        }
        // Capture only the fields the task needs (not the whole Observation, which carries the payload)
        // so a queued task pins nothing extra.
        Scope scope = new Scope(observation.identity().workspace(), observation.identity().project());
        SessionId sessionId = observation.sessionId();
        try {
            // Run generation OFF the ingest worker so the blocking LLM call cannot stall the queue
            // drain (invariant #5, issue #78). generate() is itself best-effort (it logs and swallows
            // any failure), so the task body never escalates.
            executor.execute(() -> generate(scope, sessionId));
        } catch (RejectedExecutionException reject) {
            if (executor.isShutdown()) {
                // Shutting down (context stopping): not a backlog problem, and there is no "next
                // session-end" to re-fire on — just note it and move on.
                log.debug("handoff executor is shutting down; skipped session-end handoff for {}/{} "
                        + "session {}", scope.workspaceSlug(), scope.projectSlug(), sessionId.value());
            } else {
                // Backlog saturated: drop rather than block ingest. The next session-end re-fires and
                // begin supersedes, and a client can still open one explicitly, so no work is lost.
                log.warn("handoff backlog saturated; dropped session-end handoff for {}/{} session {} "
                        + "(will re-fire on the next session-end)",
                        scope.workspaceSlug(), scope.projectSlug(), sessionId.value());
            }
        }
    }

    /** Generate and open the handoff; best-effort (a failure is logged, never propagated). */
    private void generate(Scope scope, SessionId sessionId) {
        try {
            handoffs.begin(scope, sessionId);
        } catch (RuntimeException e) {
            // Non-fatal: capture is durable; the next session can still begin a handoff explicitly.
            log.warn("session-end handoff generation failed for {}/{} session {}: {}",
                    scope.workspaceSlug(), scope.projectSlug(), sessionId.value(), e.toString());
        }
    }

    /** Shut the generation executor down with the context, letting an in-flight generation finish briefly. */
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
