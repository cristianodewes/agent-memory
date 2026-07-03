package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link LlmRateLimitGate} (issue #148), driven with a fake clock — no threads, no
 * network. The contract: only a {@code 429} raises the pause; a provider reset hint wins over the
 * doubling backoff; any success (proof the quota window reset) clears the gate.
 */
class LlmRateLimitGateTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final LlmRateLimitGate gate = new LlmRateLimitGate(now::get);

    private static LlmException http429(long retryAfterMillis) {
        return new LlmException("stub request failed with HTTP 429: usage_limit_reached",
                null, true, 429, retryAfterMillis);
    }

    @Test
    void startsOpen() {
        assertThat(gate.isPaused()).isFalse();
        assertThat(gate.remainingPauseMillis()).isZero();
    }

    @Test
    void a429WithResetHintPausesUntilTheHint() {
        gate.recordFailure(http429(14_000_000)); // ~3.9h, the production resets_in_seconds shape

        assertThat(gate.isPaused()).isTrue();
        assertThat(gate.remainingPauseMillis()).isEqualTo(14_000_000);

        now.addAndGet(14_000_000); // the window reset
        assertThat(gate.isPaused()).isFalse();
    }

    @Test
    void aHintlessSequenceBacksOffDoubling() {
        gate.recordFailure(http429(0));
        assertThat(gate.remainingPauseMillis()).isEqualTo(LlmRateLimitGate.BASE_BACKOFF_MILLIS);

        now.addAndGet(LlmRateLimitGate.BASE_BACKOFF_MILLIS);
        gate.recordFailure(http429(0));
        assertThat(gate.remainingPauseMillis()).isEqualTo(2 * LlmRateLimitGate.BASE_BACKOFF_MILLIS);
    }

    @Test
    void theHintlessBackoffIsCapped() {
        for (int i = 0; i < 12; i++) {
            gate.recordFailure(http429(0));
        }
        assertThat(gate.remainingPauseMillis())
                .isLessThanOrEqualTo(LlmRateLimitGate.MAX_BACKOFF_MILLIS);
    }

    @Test
    void anOversizedHintIsCapped() {
        gate.recordFailure(http429(48L * 60 * 60 * 1000)); // absurd 48h hint

        assertThat(gate.remainingPauseMillis()).isEqualTo(LlmRateLimitGate.MAX_HINT_MILLIS);
    }

    @Test
    void non429FailuresDoNotMoveTheGate() {
        gate.recordFailure(new LlmException("boom", null, true, 500));
        gate.recordFailure(new LlmException("transport", null, true, 0));
        gate.recordFailure(null);

        assertThat(gate.isPaused()).isFalse();
    }

    @Test
    void aSuccessClearsThePauseAndTheBackoffStreak() {
        gate.recordFailure(http429(0));
        gate.recordFailure(http429(0));
        assertThat(gate.isPaused()).isTrue();

        gate.recordSuccess();
        assertThat(gate.isPaused()).isFalse();

        // The streak was reset too: the next hintless 429 starts back at the base pause.
        gate.recordFailure(http429(0));
        assertThat(gate.remainingPauseMillis()).isEqualTo(LlmRateLimitGate.BASE_BACKOFF_MILLIS);
    }

    @Test
    void aShorterHintNeverShrinksAnExistingPause() {
        gate.recordFailure(http429(10_000_000));
        gate.recordFailure(http429(5_000)); // a later, shorter hint must not cut the pause short

        assertThat(gate.remainingPauseMillis()).isEqualTo(10_000_000);
    }
}
