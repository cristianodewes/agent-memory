package com.agentmemory.llm;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared runtime rate-limit state for the required chat provider (issue #148).
 *
 * <p>When a provider call fails with a {@code 429}, the exhausted quota applies to <em>every</em>
 * queued LLM task, not just the one that failed — yet before this gate each background queue
 * (consolidation, handoff) kept draining and burned one doomed call per task (five sessions in four
 * seconds in production). {@code RateLimitTrackingLlmProvider} records each outcome here, and the
 * background dispatchers consult {@link #isPaused()} before firing so they defer instead of failing.
 *
 * <p>The pause window comes from the provider's own hint when present
 * ({@link LlmException#retryAfterMillis()} — the {@code Retry-After} header or the ChatGPT
 * {@code error.resets_in_seconds} field), else from a doubling backoff. Any successful call clears
 * the pause: a success is proof the quota window reset.
 *
 * <p>Framework-free and lock-free (atomics only) so it can be unit-tested with a fake clock and
 * consulted from any thread. Purely advisory: nothing blocks on it, and callers that ignore it (the
 * interactive request paths) keep their current fail-fast behaviour.
 */
public final class LlmRateLimitGate {

    private static final Logger log = LoggerFactory.getLogger(LlmRateLimitGate.class);

    /** First pause when the 429 carried no reset hint; doubles per consecutive hintless 429. */
    static final long BASE_BACKOFF_MILLIS = 60_000;

    /** Cap for the hintless doubling backoff. */
    static final long MAX_BACKOFF_MILLIS = 30 * 60_000;

    /** Sanity cap on a provider-supplied reset hint (a Plus quota window is a few hours). */
    static final long MAX_HINT_MILLIS = 6 * 60 * 60_000;

    private final LongSupplier clock;
    private final AtomicLong pausedUntilMillis = new AtomicLong();
    private final AtomicInteger consecutiveHintless = new AtomicInteger();

    public LlmRateLimitGate() {
        this(System::currentTimeMillis);
    }

    /** Visible for tests: inject a fake clock (epoch millis). */
    LlmRateLimitGate(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Record a failed provider call. Only a {@code 429} moves the gate: the pause is extended to the
     * provider's reset hint when present, else to a doubling backoff. Other failures are not this
     * gate's concern (a 5xx is transient per-call, not a shared quota).
     */
    public void recordFailure(LlmException e) {
        if (e == null || e.httpStatus() != 429) {
            return;
        }
        long pauseMillis;
        if (e.retryAfterMillis() > 0) {
            consecutiveHintless.set(0);
            pauseMillis = Math.min(e.retryAfterMillis(), MAX_HINT_MILLIS);
        } else {
            int streak = Math.min(consecutiveHintless.incrementAndGet(), 30);
            pauseMillis = Math.min(BASE_BACKOFF_MILLIS << (streak - 1), MAX_BACKOFF_MILLIS);
        }
        long now = clock.getAsLong();
        long previous = pausedUntilMillis.getAndAccumulate(now + pauseMillis, Math::max);
        if (previous <= now) {
            // Opening a new pause window (was not already paused): say so once, loudly.
            log.warn("LLM provider is rate-limited (HTTP 429); pausing background LLM work for ~{}s {}",
                    pauseMillis / 1000,
                    e.retryAfterMillis() > 0 ? "(provider reset hint)" : "(no reset hint; backoff)");
        }
    }

    /** Record a successful provider call: proof the quota window reset, so the pause is lifted. */
    public void recordSuccess() {
        consecutiveHintless.set(0);
        pausedUntilMillis.set(0);
    }

    /** Whether background LLM work should currently hold instead of firing a doomed call. */
    public boolean isPaused() {
        return remainingPauseMillis() > 0;
    }

    /** Milliseconds until the pause lifts, or {@code 0} when not paused. */
    public long remainingPauseMillis() {
        return Math.max(0, pausedUntilMillis.get() - clock.getAsLong());
    }
}
