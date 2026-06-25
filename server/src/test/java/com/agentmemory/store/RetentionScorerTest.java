package com.agentmemory.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.agentmemory.core.MemoryLayer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link RetentionScorer} decay/retention math (issue #24) — the curve, access
 * reinforcement, and per-layer regimes — driven by a fixed {@link Clock} so elapsed-time decay is
 * asserted deterministically without sleeping.
 */
class RetentionScorerTest {

    private static final Instant NOW = Instant.parse("2026-06-25T12:00:00Z");

    /** A scorer pinned at {@link #NOW} with the documented default parameters. */
    private static RetentionScorer scorerAt(Instant now) {
        return new RetentionScorer(RetentionParameters.defaults(), Clock.fixed(now, ZoneOffset.UTC));
    }

    private static Instant daysAgo(double days) {
        return NOW.minus(Duration.ofMillis((long) (days * 86_400_000.0)));
    }

    // --- decay curve: salience term falls with age (age-decaying layers) -----------------------

    @Test
    void freshPageScoresAtFullSalienceWithNoAccess() {
        RetentionScorer scorer = scorerAt(NOW);
        // Created "now", never accessed: age 0 ⇒ exp(0)=1, log1p(0)=0 ⇒ score == salience.
        double score = scorer.score(MemoryLayer.EPISODIC, 0, NOW, null);
        assertThat(score).isCloseTo(RetentionParameters.defaults().defaultSalience(), within(1e-9));
    }

    @Test
    void salienceTermDecaysMonotonicallyWithAgeForEpisodic() {
        RetentionScorer scorer = scorerAt(NOW);
        double fresh = scorer.score(MemoryLayer.EPISODIC, 0, daysAgo(0), null);
        double week = scorer.score(MemoryLayer.EPISODIC, 0, daysAgo(7), null);
        double month = scorer.score(MemoryLayer.EPISODIC, 0, daysAgo(30), null);
        double year = scorer.score(MemoryLayer.EPISODIC, 0, daysAgo(365), null);

        // Strictly decreasing as the page ages.
        assertThat(fresh).isGreaterThan(week);
        assertThat(week).isGreaterThan(month);
        assertThat(month).isGreaterThan(year);
        // Never negative, and an ancient page tends toward (but never below) zero salience.
        assertThat(year).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void agedScoreMatchesTheClosedFormExponential() {
        RetentionScorer scorer = scorerAt(NOW);
        // salience·exp(−λ·Δt): λ=0.02, Δt=50d, salience=1 ⇒ exp(−1.0).
        double expected = Math.exp(-0.02 * 50.0);
        double score = scorer.score(MemoryLayer.EPISODIC, 0, daysAgo(50), null);
        assertThat(score).isCloseTo(expected, within(1e-6));
    }

    // --- access reinforcement: a recall hit raises the score ------------------------------------

    @Test
    void reinforcementRaisesScoreVersusNoAccessAtSameAge() {
        RetentionScorer scorer = scorerAt(NOW);
        Instant created = daysAgo(30);
        double noAccess = scorer.score(MemoryLayer.EPISODIC, 0, created, null);
        // Accessed today, with a non-trivial hit count: adds σ·log(1+10)·exp(0) on top.
        double reinforced = scorer.score(MemoryLayer.EPISODIC, 10, created, NOW);

        assertThat(reinforced).isGreaterThan(noAccess);
        double expectedBump = 1.0 * Math.log1p(10) * Math.exp(0.0);
        assertThat(reinforced - noAccess).isCloseTo(expectedBump, within(1e-6));
    }

    @Test
    void reinforcementHasDiminishingReturnsInAccessCount() {
        RetentionScorer scorer = scorerAt(NOW);
        Instant created = daysAgo(30);
        // Equal-width (single-hit) steps at different starting counts: log1p is concave, so the
        // marginal value of one more hit shrinks as the count grows (1→2 worth more than 100→101).
        double a1 = scorer.score(MemoryLayer.EPISODIC, 1, created, NOW);
        double a2 = scorer.score(MemoryLayer.EPISODIC, 2, created, NOW);
        double a100 = scorer.score(MemoryLayer.EPISODIC, 100, created, NOW);
        double a101 = scorer.score(MemoryLayer.EPISODIC, 101, created, NOW);

        double earlyMarginal = a2 - a1;
        double lateMarginal = a101 - a100;
        assertThat(lateMarginal).isLessThan(earlyMarginal);
        assertThat(lateMarginal).isGreaterThan(0.0); // still positive — more use never hurts
    }

    @Test
    void reinforcementBumpRelaxesAsTimeSinceAccessGrows() {
        RetentionScorer scorer = scorerAt(NOW);
        Instant created = daysAgo(200);
        double justAccessed = scorer.score(MemoryLayer.EPISODIC, 5, created, daysAgo(0));
        double accessedLongAgo = scorer.score(MemoryLayer.EPISODIC, 5, created, daysAgo(120));
        // Same hit count, but the lift decays with days-since-access (exp(−μ·days)).
        assertThat(justAccessed).isGreaterThan(accessedLongAgo);
    }

    // --- per-layer regimes ----------------------------------------------------------------------

    @Test
    void semanticLayerDoesNotDecayWithAge() {
        RetentionScorer scorer = scorerAt(NOW);
        double fresh = scorer.score(MemoryLayer.SEMANTIC, 0, daysAgo(0), null);
        double ancient = scorer.score(MemoryLayer.SEMANTIC, 0, daysAgo(3650), null); // 10 years
        // Timeless knowledge: identical salience regardless of calendar age.
        assertThat(ancient).isEqualTo(fresh);
        assertThat(fresh).isCloseTo(RetentionParameters.defaults().defaultSalience(), within(1e-9));
    }

    @Test
    void semanticStillGainsFromReinforcement() {
        RetentionScorer scorer = scorerAt(NOW);
        double base = scorer.score(MemoryLayer.SEMANTIC, 0, daysAgo(100), null);
        double used = scorer.score(MemoryLayer.SEMANTIC, 7, daysAgo(100), NOW);
        // No age decay, but use still lifts it (reinforced semantic > untouched semantic).
        assertThat(used).isGreaterThan(base);
    }

    @Test
    void proceduralRetentionIsDrivenByUseNotAge() {
        RetentionScorer scorer = scorerAt(NOW);
        // Two equally-old procedures; the frequently-used one retains more (age term is flat for
        // procedural, so the access term is the whole differentiator).
        Instant created = daysAgo(300);
        double unused = scorer.score(MemoryLayer.PROCEDURAL, 0, created, null);
        double used = scorer.score(MemoryLayer.PROCEDURAL, 20, created, daysAgo(5));
        assertThat(used).isGreaterThan(unused);

        // Age alone does not erode a procedural page's base salience.
        double old = scorer.score(MemoryLayer.PROCEDURAL, 0, daysAgo(3650), null);
        assertThat(old).isCloseTo(unused, within(1e-9));
    }

    @Test
    void workingLayerDecaysWithAgeLikeEpisodic() {
        RetentionScorer scorer = scorerAt(NOW);
        double fresh = scorer.score(MemoryLayer.WORKING, 0, daysAgo(0), null);
        double old = scorer.score(MemoryLayer.WORKING, 0, daysAgo(10), null);
        assertThat(old).isLessThan(fresh);
    }

    // --- cold threshold (sweep candidacy, #25 owns the action) ----------------------------------

    @Test
    void agedUntouchedEpisodicEventuallyGoesCold() {
        RetentionScorer scorer = scorerAt(NOW);
        // Fresh page is not cold.
        assertThat(scorer.isCold(MemoryLayer.EPISODIC, 0, daysAgo(0), null)).isFalse();
        // Very old, never reinforced ⇒ salience decayed below the 0.05 default cold threshold.
        assertThat(scorer.isCold(MemoryLayer.EPISODIC, 0, daysAgo(365), null)).isTrue();
    }

    @Test
    void reinforcementCanKeepAnOldPageWarm() {
        RetentionScorer scorer = scorerAt(NOW);
        // The same old page that would be cold untouched is kept above the threshold by recent use.
        assertThat(scorer.isCold(MemoryLayer.EPISODIC, 0, daysAgo(365), null)).isTrue();
        assertThat(scorer.isCold(MemoryLayer.EPISODIC, 25, daysAgo(365), daysAgo(0))).isFalse();
    }

    @Test
    void semanticNeverGoesColdOnAgeAlone() {
        RetentionScorer scorer = scorerAt(NOW);
        // Salience (1.0) stays well above the 0.05 threshold no matter how old.
        assertThat(scorer.isCold(MemoryLayer.SEMANTIC, 0, daysAgo(36500), null)).isFalse();
    }

    // --- robustness -----------------------------------------------------------------------------

    @Test
    void futureTimestampsAreFlooredAtZeroElapsedNotAmplified() {
        RetentionScorer scorer = scorerAt(NOW);
        // A created-in-the-future row (clock skew) must not exceed the undecayed maximum.
        double skewed = scorer.score(MemoryLayer.EPISODIC, 0, NOW.plus(Duration.ofDays(5)), null);
        assertThat(skewed).isCloseTo(RetentionParameters.defaults().defaultSalience(), within(1e-9));
    }

    @Test
    void explicitSalienceScalesTheAgeTerm() {
        RetentionScorer scorer = scorerAt(NOW);
        double s1 = scorer.score(MemoryLayer.EPISODIC, 2.0, 0, daysAgo(10), null);
        double s2 = scorer.score(MemoryLayer.EPISODIC, 1.0, 0, daysAgo(10), null);
        // Salience multiplies the decay term, so double salience ⇒ double the age contribution
        // (no access term here, so the whole score scales).
        assertThat(s1).isCloseTo(2.0 * s2, within(1e-9));
    }

    @Test
    void rejectsNullLayerAndBadInputs() {
        RetentionScorer scorer = scorerAt(NOW);
        assertThatThrownBy(() -> scorer.score(null, 0, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scorer.score(MemoryLayer.EPISODIC, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scorer.score(MemoryLayer.EPISODIC, -1, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scorer.score(MemoryLayer.EPISODIC, 0.0, 0, NOW, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullConstructorArgs() {
        assertThatThrownBy(() -> new RetentionScorer(null, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionScorer(RetentionParameters.defaults(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
