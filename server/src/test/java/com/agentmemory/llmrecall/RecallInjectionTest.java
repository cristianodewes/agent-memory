package com.agentmemory.llmrecall;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.recall.HitSource;
import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.RecallQuery;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.RecallService;
import com.agentmemory.recall.Scope;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link RecallInjection}: the relevance gate drops below-threshold hits (and yields
 * an empty block when nothing clears it), the block is bounded by hit-count and char budget, the
 * low-confidence raw-observation fallback is never injected, and the rendered markdown is clean
 * (mark-tags stripped, single-line). Uses a hand-stubbed {@link RecallService}; no Spring, no DB.
 */
class RecallInjectionTest {

    private static final Scope SCOPE = Scope.of("ws", "proj");

    private static RecallHit page(String id, String title, String snippet, double score, int rank) {
        return new RecallHit(HitSource.PAGE, id, "p/" + id + ".md", title, null, score, rank, snippet);
    }

    /** A stub recall service that returns a fixed result and records the limit it was asked for. */
    private static final class StubRecall implements RecallService {
        private final RecallResult result;
        int lastLimit = -1;

        StubRecall(RecallResult result) {
            this.result = result;
        }

        @Override
        public RecallResult search(RecallQuery query) {
            this.lastLimit = query.limit();
            return result;
        }
    }

    private static LlmRecallProperties.Injection cfg(int maxHits, double minScore, int maxChars) {
        return cfg(maxHits, minScore, 0.35, maxChars);
    }

    private static LlmRecallProperties.Injection cfg(
            int maxHits, double minScore, double minScoreAbsolute, int maxChars) {
        return new LlmRecallProperties.Injection(maxHits, minScore, minScoreAbsolute, maxChars);
    }

    @Test
    void rendersABoundedBlockOfGatedHits() {
        RecallResult r = RecallResult.ofPages(List.of(
                page("a", "Alpha", "first <mark>hit</mark> body", 0.9, 1),
                page("b", "Beta", "second hit body", 0.6, 2)));
        RecallInjection injection = new RecallInjection(new StubRecall(r), cfg(5, 0.0, 1200));

        RecallInjection.Result out = injection.inject(SCOPE, "some prompt");

        assertThat(out.isEmpty()).isFalse();
        assertThat(out.hits()).isEqualTo(2);
        assertThat(out.text()).startsWith("## Relevant project memory");
        assertThat(out.text()).contains("**Alpha** (p/a.md)").contains("**Beta** (p/b.md)");
        // <mark> tags are stripped from the injected snippet.
        assertThat(out.text()).contains("first hit body").doesNotContain("<mark>");
    }

    @Test
    void relevanceGateDropsLowScoringHits() {
        RecallResult r = RecallResult.ofPages(List.of(
                page("a", "Alpha", "strong", 0.80, 1),
                page("b", "Beta", "weak", 0.20, 2)));
        // Gate at 0.5: only Alpha clears.
        RecallInjection injection = new RecallInjection(new StubRecall(r), cfg(5, 0.5, 1200));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        assertThat(out.hits()).isEqualTo(1);
        assertThat(out.text()).contains("Alpha").doesNotContain("Beta");
    }

    @Test
    void relativeGateDropsHitsFarBelowTheTop() {
        // Relative gate: threshold = topScore * minScore = 1.0 * 0.5 = 0.5. The top hit always passes;
        // hits below half the top score are dropped.
        RecallResult r = RecallResult.ofPages(List.of(
                page("a", "Alpha", "best", 1.00, 1),
                page("b", "Beta", "mid", 0.60, 2),
                page("c", "Gamma", "low", 0.20, 3)));
        RecallInjection injection = new RecallInjection(new StubRecall(r), cfg(5, 0.5, 1200));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        // Alpha (1.0) and Beta (0.6 >= 0.5) kept; Gamma (0.2 < 0.5) dropped.
        assertThat(out.hits()).isEqualTo(2);
        assertThat(out.text()).contains("Alpha").contains("Beta").doesNotContain("Gamma");
    }

    @Test
    void relativeGateAlwaysKeepsTheTopHitEvenWhenScoresAreSmall() {
        // Even with tiny raw-RRF-magnitude scores, the relative gate keeps the top hit (and hits within
        // the fraction of it) rather than silently emptying — the key fix over an absolute gate.
        RecallResult r = RecallResult.ofPages(List.of(
                page("a", "Alpha", "best", 0.030, 1),
                page("b", "Beta", "near", 0.020, 2)));
        RecallInjection injection = new RecallInjection(new StubRecall(r), cfg(5, 0.5, 1200));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        // topScore=0.03, threshold=0.015; both 0.03 and 0.02 clear it → NOT empty.
        assertThat(out.isEmpty()).isFalse();
        assertThat(out.hits()).isEqualTo(2);
    }

    @Test
    void capsTheNumberOfHits() {
        RecallResult r = RecallResult.ofPages(List.of(
                page("a", "A", "x", 0.9, 1),
                page("b", "B", "x", 0.8, 2),
                page("c", "C", "x", 0.7, 3)));
        RecallInjection injection = new RecallInjection(new StubRecall(r), cfg(2, 0.0, 1200));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        assertThat(out.hits()).isEqualTo(2);
        assertThat(out.text()).contains("**A**").contains("**B**").doesNotContain("**C**");
    }

    @Test
    void overFetchesACandidatePoolLargerThanTheCap() {
        // The gate needs a pool bigger than maxHits so it can backfill; the recall is asked for
        // maxHits * POOL_MULTIPLIER (4), not just maxHits.
        StubRecall stub = new StubRecall(RecallResult.empty());
        RecallInjection injection = new RecallInjection(stub, cfg(3, 0.0, 1200));

        injection.inject(SCOPE, "prompt");

        assertThat(stub.lastLimit).isEqualTo(3 * 4);
    }

    @Test
    void gateBackfillsFromBelowTheCapWhenTopHitsAreDropped() {
        // maxHits=2. The top 2 hits are weak (gated out at minScore=0.5 relative to the 1.0 top), but
        // hits ranked 3-4 are strong enough — a pool-fetch + backfill includes them. With pool==cap this
        // would wrongly yield fewer/zero hits.
        RecallResult r = RecallResult.ofPages(List.of(
                page("a", "Top", "strong", 1.00, 1),     // top, kept
                page("b", "Weak1", "weak", 0.20, 2),     // dropped (0.2 < 0.5)
                page("c", "Strong2", "strong", 0.80, 3), // kept (0.8 >= 0.5) — backfilled past the cap
                page("d", "Strong3", "strong", 0.70, 4)));
        RecallInjection injection = new RecallInjection(new StubRecall(r), cfg(2, 0.5, 1200));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        // Cap is 2; the two kept hits are the top and the first strong backfill (Strong2), not Weak1.
        assertThat(out.hits()).isEqualTo(2);
        assertThat(out.text()).contains("Top").contains("Strong2").doesNotContain("Weak1");
    }

    @Test
    void calibratedAbsoluteGateEmptiesALowSignalBlock() {
        // A calibrated cross-encoder scored everything low (top 0.20 < the 0.35 absolute floor): the
        // calibrated way to say "nothing relevant here" on a low-signal prompt -> inject NOTHING. The
        // relative gate (0.0 here) would otherwise have kept the top hit; the absolute floor overrides it.
        RecallResult r = RecallResult.ofPages(List.of(
                page("a", "Alpha", "weak", 0.20, 1),
                page("b", "Beta", "weaker", 0.10, 2)), /*calibrated*/ true);
        RecallInjection injection = new RecallInjection(new StubRecall(r), cfg(5, 0.0, 0.35, 1200));

        RecallInjection.Result out = injection.inject(SCOPE, "ok thanks");

        assertThat(out.isEmpty()).isTrue();
    }

    @Test
    void calibratedAbsoluteGateKeepsAStrongBlock() {
        // Calibrated top 0.80 >= 0.35 absolute floor: the block is injected as normal.
        RecallResult r = RecallResult.ofPages(List.of(
                page("a", "Alpha", "strong", 0.80, 1),
                page("b", "Beta", "mid", 0.50, 2)), true);
        RecallInjection injection = new RecallInjection(new StubRecall(r), cfg(5, 0.0, 0.35, 1200));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        assertThat(out.isEmpty()).isFalse();
        assertThat(out.hits()).isEqualTo(2);
        assertThat(out.text()).contains("Alpha").contains("Beta");
    }

    @Test
    void absoluteGateIsNotAppliedToUncalibratedRrfScores() {
        // The SUBTLETY: the same tiny top score (0.030), but UNCALIBRATED (RRF fallback / LLM rerank).
        // The absolute floor (0.35) must NOT fire here — raw RRF fused scores are tiny (~0.01-0.05), so an
        // absolute cut would wrongly empty almost every block. Only the scale-invariant relative gate
        // applies, which always keeps the top hit -> the block is NOT empty.
        RecallResult r = RecallResult.ofPages(List.of(
                page("a", "Alpha", "best", 0.030, 1),
                page("b", "Beta", "near", 0.020, 2))); // calibrated == false
        RecallInjection injection = new RecallInjection(new StubRecall(r), cfg(5, 0.5, 0.35, 1200));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        assertThat(out.isEmpty()).isFalse();
        assertThat(out.hits()).isEqualTo(2);
        assertThat(out.text()).contains("Alpha").contains("Beta");
    }

    @Test
    void rawObservationFallbackIsNeverInjected() {
        RecallResult fallback = RecallResult.ofRawFallback(List.of(
                new RecallHit(HitSource.RAW_OBSERVATION, "o1", null, "observation: user-prompt",
                        "user-prompt", 1.0, 1, "raw <mark>text</mark>")));
        RecallInjection injection = new RecallInjection(new StubRecall(fallback), cfg(5, 0.0, 1200));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        assertThat(out.isEmpty()).isTrue();
    }

    @Test
    void blockStaysUnderMaxCharsAndDropsHitsThatDoNotFit() {
        // First hit is short and fits; second hit is huge and would blow the budget → only the first is
        // rendered, the block stays under maxChars, and the reported count matches what was rendered.
        RecallResult r = RecallResult.ofPages(List.of(
                page("a", "Alpha", "short", 0.9, 1),
                page("b", "Beta", "x".repeat(5000), 0.8, 2)));
        RecallInjection injection = new RecallInjection(new StubRecall(r), cfg(5, 0.0, 120));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        assertThat(out.text().length()).isLessThanOrEqualTo(120);
        assertThat(out.text()).contains("Alpha").doesNotContain("Beta");
        assertThat(out.hits()).isEqualTo(1); // count matches the rendered content, not the gated set
    }

    @Test
    void neverInjectsAHeaderOnlyBlock() {
        // Even the FIRST hit's line exceeds the tiny budget, so nothing fits. The result must be empty
        // (no bare '## Relevant project memory' header), and the hit count must be 0.
        RecallResult r = RecallResult.ofPages(List.of(
                page("a", "A title that is quite long indeed", "x".repeat(500), 0.9, 1)));
        RecallInjection injection = new RecallInjection(new StubRecall(r), cfg(5, 0.0, 30));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        assertThat(out.isEmpty()).isTrue();
        assertThat(out.text()).isEmpty();
        assertThat(out.hits()).isZero();
    }

    @Test
    void emptyRecallYieldsAnEmptyBlock() {
        RecallInjection injection = new RecallInjection(new StubRecall(RecallResult.empty()), cfg(5, 0.0, 1200));

        assertThat(injection.inject(SCOPE, "prompt").isEmpty()).isTrue();
    }
}
