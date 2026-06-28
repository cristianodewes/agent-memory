package com.agentmemory.recall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.MemoryLayer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link RecencyDecay} (no DB, fixed clock): the per-layer half-life curve, the
 * non-decaying layers, the undatable-hit and disabled-layer no-ops, and the clock-skew floor (issue
 * #140).
 */
class RecencyDecayTest {

    private static final Instant NOW = Instant.parse("2026-06-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** Decay with the documented defaults (working 1d, episodic 14d). */
    private static RecencyDecay decay() {
        return new RecencyDecay(RecencyParameters.defaults(), CLOCK);
    }

    private static Instant daysAgo(double days) {
        return NOW.minus(Duration.ofMillis((long) (days * 86_400_000L)));
    }

    @Test
    void atOneHalfLifeTheFactorIsAHalf() {
        RecencyDecay decay = decay();
        // Episodic half-life is 14d → a 14-day-old page keeps half its score.
        assertThat(decay.factor(MemoryLayer.EPISODIC, daysAgo(14)))
                .isCloseTo(0.5, org.assertj.core.api.Assertions.within(1e-9));
        // Working half-life is 1d.
        assertThat(decay.factor(MemoryLayer.WORKING, daysAgo(1)))
                .isCloseTo(0.5, org.assertj.core.api.Assertions.within(1e-9));
        // Two half-lives → a quarter.
        assertThat(decay.factor(MemoryLayer.EPISODIC, daysAgo(28)))
                .isCloseTo(0.25, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    void afreshPageIsUndecayed() {
        assertThat(decay().factor(MemoryLayer.EPISODIC, NOW)).isEqualTo(1.0);
    }

    @Test
    void olderPagesDecayMonotonically() {
        RecencyDecay decay = decay();
        double d7 = decay.factor(MemoryLayer.EPISODIC, daysAgo(7));
        double d30 = decay.factor(MemoryLayer.EPISODIC, daysAgo(30));
        double d90 = decay.factor(MemoryLayer.EPISODIC, daysAgo(90));
        assertThat(d7).isGreaterThan(d30);
        assertThat(d30).isGreaterThan(d90);
        assertThat(d90).isGreaterThan(0.0); // strictly positive — attenuates, never zeroes
    }

    @Test
    void semanticAndProceduralDoNotAgeDecay() {
        RecencyDecay decay = decay();
        // Even a year old, the timeless / procedural layers keep their full score.
        assertThat(decay.factor(MemoryLayer.SEMANTIC, daysAgo(365))).isEqualTo(1.0);
        assertThat(decay.factor(MemoryLayer.PROCEDURAL, daysAgo(365))).isEqualTo(1.0);
    }

    @Test
    void undatableHitIsNeverPenalized() {
        RecencyDecay decay = decay();
        // No timestamp (raw-observation fallback) and/or no layer (vector-only) → factor 1.0.
        assertThat(decay.factor(MemoryLayer.EPISODIC, null)).isEqualTo(1.0);
        assertThat(decay.factor(null, daysAgo(99))).isEqualTo(1.0);
        assertThat(decay.factor(null, null)).isEqualTo(1.0);
    }

    @Test
    void aZeroHalfLifeDisablesDecayForThatLayer() {
        // episodic half-life 0 → episodic stops decaying; working still does.
        RecencyDecay decay = new RecencyDecay(new RecencyParameters(1.0, 0.0), CLOCK);
        assertThat(decay.factor(MemoryLayer.EPISODIC, daysAgo(100))).isEqualTo(1.0);
        assertThat(decay.factor(MemoryLayer.WORKING, daysAgo(1)))
                .isCloseTo(0.5, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    void futureTimestampIsFlooredToNoDecay() {
        // A page stamped slightly in the future (clock skew) ages "negatively"; the floor keeps the
        // factor at 1.0 rather than letting it exceed the undecayed maximum.
        assertThat(decay().factor(MemoryLayer.EPISODIC, daysAgo(-5))).isEqualTo(1.0);
    }

    @Test
    void rejectsBadArguments() {
        assertThatThrownBy(() -> new RecencyDecay(null, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecencyDecay(RecencyParameters.defaults(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecencyParameters(-1.0, 14.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RecencyParameters(1.0, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
