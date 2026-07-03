package com.agentmemory.handoff;

import com.agentmemory.core.Observation;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.SessionId;
import com.agentmemory.llm.LlmException;
import com.agentmemory.llm.LlmRateLimitGate;
import com.agentmemory.recall.Scope;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
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
 * cheaply.
 *
 * <h2>Off the ingest worker (invariant #5, issue #78)</h2>
 * {@code addPostWriteListener} listeners run <em>on the single ingest worker thread</em>, after each
 * successful write. Handoff generation does a session-observations read + a blocking LLM call + a DB
 * write; running that inline on the worker would stall the bounded-queue drain for the whole LLM
 * round-trip and saturate ingest into 429s for unrelated events. So this listener does only the cheap
 * {@code SESSION_END} check on the worker and <strong>dispatches</strong> the actual
 * {@link HandoffService#begin} to a dedicated single-thread executor — mirroring
 * {@code ConsolidationObservationListener}, the sibling session-end reaction. One thread keeps
 * generation serialized; the backlog is bounded and a saturated backlog drops the task rather than
 * blocking ingest.
 *
 * <h2>Rate-limit aware, with deferred retries (issue #148)</h2>
 * {@code SESSION_END} is by definition this session's <em>last</em> observation — there is no "next
 * session-end" for it, so a dropped generation is a handoff lost forever (production lost seven to
 * one exhausted-quota window). The task therefore consults the shared {@link LlmRateLimitGate} before
 * firing (deferring instead of burning a doomed LLM call while the quota is exhausted), and a
 * <em>retryable</em> failure re-schedules the same task after the gate's pause (or a doubling
 * backoff), up to {@link #MAX_ATTEMPTS}. Retries are deduplicated per session and re-enter the same
 * bounded executor. Permanent failures are logged and dropped — capture is durable and a client can
 * still open a handoff explicitly via {@code POST /handoff} / {@code memory_handoff_begin}.
 *
 * <p>Best-effort throughout: a generation failure is caught and logged, and the dispatch itself never
 * throws back into the worker. {@link AutoCloseable} so the executors are shut down with the
 * application context.
 */
public class SessionEndHandoffTrigger implements Consumer<Observation>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionEndHandoffTrigger.class);

    /** Bounded generation backlog; keeps the dispatch non-blocking yet bounded (drops on overflow). */
    private static final int QUEUE_CAPACITY = 256;

    /** Max runs per session-end (first attempt + deferred retries). */
    static final int MAX_ATTEMPTS = 5;

    /** Cap for the doubling retry delay (the gate's own pause may be longer, e.g. a quota window). */
    private static final long MAX_RETRY_MILLIS = 30 * 60_000;

    private final HandoffService handoffs;
    private final LlmRateLimitGate rateLimitGate;
    // Retry timing, injectable for tests (production values via the public constructor).
    private final long retryBaseMillis;
    private final long retryFloorMillis;
    private final long retryJitterBoundMillis;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService retryScheduler;
    // Sessions with a deferred retry in flight, so repeated failures/pauses cannot pile up scheduled
    // tasks for the same session (bounds the retry queue by the number of distinct sessions).
    private final Set<UUID> pendingRetry = ConcurrentHashMap.newKeySet();

    public SessionEndHandoffTrigger(HandoffService handoffs, LlmRateLimitGate rateLimitGate) {
        this(handoffs, rateLimitGate, 60_000, 1_000, 15_000);
    }

    /**
     * Visible for tests: inject the retry timing — {@code retryBaseMillis} (first failure-retry
     * delay, doubling per attempt), {@code retryFloorMillis} (minimum deferral) and
     * {@code retryJitterBoundMillis} (random spread added to every deferral).
     */
    SessionEndHandoffTrigger(HandoffService handoffs, LlmRateLimitGate rateLimitGate,
            long retryBaseMillis, long retryFloorMillis, long retryJitterBoundMillis) {
        this.handoffs = handoffs;
        this.rateLimitGate = rateLimitGate;
        this.retryBaseMillis = retryBaseMillis;
        this.retryFloorMillis = retryFloorMillis;
        this.retryJitterBoundMillis = retryJitterBoundMillis;
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
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-memory-handoff-retry");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void accept(Observation observation) {
        if (observation == null || observation.kind() != ObservationKind.SESSION_END) {
            return;
        }
        // Capture only the fields the task needs (not the whole Observation, which carries the payload)
        // so a queued task pins nothing extra.
        Scope scope = new Scope(observation.identity().workspace(), observation.identity().project());
        dispatch(scope, observation.sessionId(), 1);
    }

    /** Submit one generation run to the bounded executor; a saturated backlog drops it (best-effort). */
    private void dispatch(Scope scope, SessionId sessionId, int attempt) {
        try {
            // Run generation OFF the ingest worker so the blocking LLM call cannot stall the queue
            // drain (invariant #5, issue #78). generate() is itself best-effort (it logs and swallows
            // or defers any failure), so the task body never escalates.
            executor.execute(() -> generate(scope, sessionId, attempt));
        } catch (RejectedExecutionException reject) {
            pendingRetry.remove(sessionId.value());
            if (executor.isShutdown()) {
                // Shutting down (context stopping): not a backlog problem — just note it and move on.
                log.debug("handoff executor is shutting down; skipped session-end handoff for {}/{} "
                        + "session {}", scope.workspaceSlug(), scope.projectSlug(), sessionId.value());
            } else {
                // Backlog saturated: drop rather than block ingest. begin supersedes any prior open
                // handoff and a client can still open one explicitly, so recovery stays possible.
                log.warn("handoff backlog saturated; dropped session-end handoff for {}/{} session {} "
                        + "(memory_handoff_begin can still open one explicitly)",
                        scope.workspaceSlug(), scope.projectSlug(), sessionId.value());
            }
        }
    }

    /** One generation run on the executor thread: defer while rate-limited, retry retryable failures. */
    private void generate(Scope scope, SessionId sessionId, int attempt) {
        if (rateLimitGate.isPaused()) {
            // The quota window is known-exhausted: firing now would just burn another doomed LLM call
            // (issue #148). Defer past the pause instead — SESSION_END never re-fires on its own.
            scheduleRetry(scope, sessionId, attempt, rateLimitGate.remainingPauseMillis());
            return;
        }
        try {
            handoffs.begin(scope, sessionId);
            pendingRetry.remove(sessionId.value());
        } catch (RuntimeException e) {
            if (isRetryableLlmFailure(e) && attempt < MAX_ATTEMPTS) {
                log.warn("session-end handoff generation failed for {}/{} session {} (attempt {}/{}; "
                        + "will retry): {}", scope.workspaceSlug(), scope.projectSlug(),
                        sessionId.value(), attempt, MAX_ATTEMPTS, e.toString());
                scheduleRetry(scope, sessionId, attempt, retryDelayMillis(attempt));
            } else {
                pendingRetry.remove(sessionId.value());
                // Non-fatal: capture is durable; the next session can still begin a handoff explicitly.
                log.warn("session-end handoff generation failed for {}/{} session {} (attempt {}/{}; "
                        + "giving up): {}", scope.workspaceSlug(), scope.projectSlug(),
                        sessionId.value(), attempt, MAX_ATTEMPTS, e.toString());
            }
        }
    }

    /**
     * Schedule a deferred re-dispatch of {@code (scope, sessionId)}. Deduplicated per session: when a
     * retry is already pending, the new request folds into it ({@code begin} supersedes any prior open
     * handoff, so one run covers both).
     */
    private void scheduleRetry(Scope scope, SessionId sessionId, int attempt, long delayMillis) {
        if (attempt >= MAX_ATTEMPTS) {
            pendingRetry.remove(sessionId.value());
            log.warn("session-end handoff for {}/{} session {} deferred {} times while rate-limited; "
                    + "giving up (memory_handoff_begin can still open one explicitly)",
                    scope.workspaceSlug(), scope.projectSlug(), sessionId.value(), attempt);
            return;
        }
        if (!pendingRetry.add(sessionId.value())) {
            return; // a retry for this session is already scheduled
        }
        // Small jitter so the sessions queued behind one exhausted quota window do not all re-fire in
        // the same instant when it reopens.
        long delay = Math.max(retryFloorMillis, delayMillis)
                + ThreadLocalRandom.current().nextLong(retryJitterBoundMillis + 1);
        try {
            retryScheduler.schedule(() -> {
                pendingRetry.remove(sessionId.value());
                dispatch(scope, sessionId, attempt + 1);
            }, delay, TimeUnit.MILLISECONDS);
            log.info("session-end handoff for {}/{} session {} deferred for ~{}s (attempt {}/{}) "
                    + "while the LLM quota recovers", scope.workspaceSlug(), scope.projectSlug(),
                    sessionId.value(), delay / 1000, attempt, MAX_ATTEMPTS);
        } catch (RejectedExecutionException reject) {
            pendingRetry.remove(sessionId.value()); // shutting down; nothing to defer to
        }
    }

    /** Doubling delay for a retryable failure the gate has no pause hint for. */
    private long retryDelayMillis(int attempt) {
        return Math.min(retryBaseMillis << Math.min(attempt - 1, 10), MAX_RETRY_MILLIS);
    }

    /** Whether {@code t} (or a cause) is a transient provider failure worth a deferred retry. */
    private static boolean isRetryableLlmFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof LlmException llm) {
                return llm.isRetryable();
            }
        }
        return false;
    }

    /** Shut both executors down with the context, letting an in-flight generation finish briefly. */
    @Override
    public void close() {
        retryScheduler.shutdownNow();
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
