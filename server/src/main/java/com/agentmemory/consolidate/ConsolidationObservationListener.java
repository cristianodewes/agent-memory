package com.agentmemory.consolidate;

import com.agentmemory.core.Observation;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.SessionId;
import com.agentmemory.llm.LlmException;
import com.agentmemory.llm.LlmRateLimitGate;
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
 * <h2>Rate-limit aware, with deferred retries (issue #148)</h2>
 * A session's final {@code session-end} is its <em>last</em> trigger: if that synthesis fails during
 * an exhausted-quota window (HTTP 429), no "next trigger" ever comes and the session stays
 * unconsolidated. So the task consults the shared {@link LlmRateLimitGate} before firing (deferring
 * instead of burning a doomed LLM call while the quota is exhausted), and a <em>retryable</em>
 * failure re-schedules the same task after the gate's pause (or a doubling backoff), up to
 * {@link #MAX_ATTEMPTS}. Retries are deduplicated per session and re-enter the same bounded executor,
 * so the backlog bound and single-thread serialization are preserved. Permanent failures are logged
 * and dropped, exactly as before.
 *
 * <p>Best-effort throughout: the task catches and logs any synthesis failure, and the dispatch itself
 * never throws back into the worker. {@link AutoCloseable} so the executors are shut down with the
 * application context.
 */
public final class ConsolidationObservationListener implements Consumer<Observation>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConsolidationObservationListener.class);

    /** Bounded synthesis backlog; keeps the dispatch non-blocking yet bounded (drops on overflow). */
    private static final int QUEUE_CAPACITY = 256;

    /** Max runs per triggering observation (first attempt + deferred retries). */
    static final int MAX_ATTEMPTS = 5;

    /** Cap for the doubling retry delay (the gate's own pause may be longer, e.g. a quota window). */
    private static final long MAX_RETRY_MILLIS = 30 * 60_000;

    private final SessionConsolidationTrigger trigger;
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

    public ConsolidationObservationListener(SessionConsolidationTrigger trigger,
            LlmRateLimitGate rateLimitGate) {
        this(trigger, rateLimitGate, 60_000, 1_000, 15_000);
    }

    /**
     * Visible for tests: inject the retry timing — {@code retryBaseMillis} (first failure-retry
     * delay, doubling per attempt), {@code retryFloorMillis} (minimum deferral) and
     * {@code retryJitterBoundMillis} (random spread added to every deferral).
     */
    ConsolidationObservationListener(SessionConsolidationTrigger trigger,
            LlmRateLimitGate rateLimitGate, long retryBaseMillis, long retryFloorMillis,
            long retryJitterBoundMillis) {
        this.trigger = trigger;
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
                    Thread t = new Thread(r, "agent-memory-consolidation");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-memory-consolidation-retry");
            t.setDaemon(true);
            return t;
        });
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
        dispatch(observation.sessionId(), kind, 1);
    }

    /** Submit one synthesis run to the bounded executor; a saturated backlog drops it (best-effort). */
    private void dispatch(SessionId sessionId, ObservationKind kind, int attempt) {
        try {
            // Run synthesis OFF the ingest worker so the blocking LLM call cannot stall the queue drain
            // (invariant #5). run() is itself best-effort (it logs and swallows or defers any synthesis
            // failure), so the task body never escalates.
            executor.execute(() -> run(sessionId, kind, attempt));
        } catch (RejectedExecutionException reject) {
            pendingRetry.remove(sessionId.value());
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

    /** One synthesis run on the executor thread: defer while rate-limited, retry retryable failures. */
    private void run(SessionId sessionId, ObservationKind kind, int attempt) {
        if (rateLimitGate.isPaused()) {
            // The quota window is known-exhausted: firing now would just burn another doomed LLM call
            // (issue #148 — five sessions burned in four seconds). Defer past the pause instead.
            scheduleRetry(sessionId, kind, attempt, rateLimitGate.remainingPauseMillis());
            return;
        }
        try {
            trigger.onObservationOrThrow(sessionId, kind);
            pendingRetry.remove(sessionId.value());
        } catch (RuntimeException e) {
            if (isRetryableLlmFailure(e) && attempt < MAX_ATTEMPTS) {
                log.warn("session {} consolidation failed on {} (attempt {}/{}; will retry): {}",
                        sessionId, kind.wire(), attempt, MAX_ATTEMPTS, e.getMessage());
                scheduleRetry(sessionId, kind, attempt, retryDelayMillis(attempt));
            } else {
                pendingRetry.remove(sessionId.value());
                // DD-005: synthesis is LLM-driven with no fallback, but it must not break ingest. A
                // later trigger for this session (or a manual memory_consolidate) can still recover it.
                log.warn("session {} consolidation failed on {} (attempt {}/{}; giving up): {}",
                        sessionId, kind.wire(), attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
    }

    /**
     * Schedule a deferred re-dispatch of {@code (sessionId, kind)}. Deduplicated per session: when a
     * retry is already pending, the new request folds into it (synthesis reads the session's latest
     * state anyway, so one run covers both triggers).
     */
    private void scheduleRetry(SessionId sessionId, ObservationKind kind, int attempt, long delayMillis) {
        if (attempt >= MAX_ATTEMPTS) {
            pendingRetry.remove(sessionId.value());
            log.warn("session {} consolidation deferred {} times while rate-limited; giving up "
                    + "(a later trigger or memory_consolidate can still recover it)", sessionId, attempt);
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
                dispatch(sessionId, kind, attempt + 1);
            }, delay, TimeUnit.MILLISECONDS);
            log.info("session {} consolidation deferred for ~{}s (attempt {}/{}) while the LLM "
                    + "quota recovers", sessionId, delay / 1000, attempt, MAX_ATTEMPTS);
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

    /** Shut both executors down with the context, letting an in-flight synthesis finish briefly. */
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
