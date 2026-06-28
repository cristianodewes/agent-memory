package com.agentmemory.llmrecall;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.MemoryLayer;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.ReasoningEffort;
import com.agentmemory.llm.TestDoubleProvider;
import com.agentmemory.recall.HitSource;
import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.RecallQuery;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.RecallService;
import com.agentmemory.recall.Scope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;
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
        // brief == null → the compact constructor defaults it to a DISABLED brief, so these cases
        // exercise the bullet path exactly as before Fase 3.
        return new LlmRecallProperties.Injection(maxHits, minScore, minScoreAbsolute, maxChars, null);
    }

    /** An injection config with the synthesized brief enabled (issue #135, Fase 3). */
    private static LlmRecallProperties.Injection cfgBriefEnabled(
            int maxHits, double minScore, double minScoreAbsolute, int maxChars, long timeoutMs) {
        return new LlmRecallProperties.Injection(
                maxHits, minScore, minScoreAbsolute, maxChars,
                new LlmRecallProperties.Injection.Brief(true, timeoutMs));
    }

    private static final RecallPrompts PROMPTS = new RecallPrompts();

    /** A provider returning a fixed curate JSON for every call. */
    private static TestDoubleProvider scriptedProvider(String curateJson) {
        return scriptedProvider(req -> curateJson);
    }

    /** A provider running an arbitrary responder (to throw, branch, or count calls). */
    private static TestDoubleProvider scriptedProvider(Function<ChatRequest, String> responder) {
        return TestDoubleProvider.builder().chatResponder(responder).build();
    }

    /** A synthesizer over {@code llm} with no reasoning hint — for output-only assertions. */
    private static BriefSynthesizer synthesizer(TestDoubleProvider llm) {
        return new BriefSynthesizer(llm, PROMPTS, null);
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
        assertThat(out.text()).contains("- p/a.md").contains("- p/b.md");
        // <mark> tags are stripped from the injected snippet.
        assertThat(out.text()).contains("first hit body").doesNotContain("<mark>");
    }

    @Test
    void rendersPathFirstWithLayerRecencyAndRelevanceMetadata() {
        // A hit carrying recency metadata (layer + updated_at) renders path-first with the
        // layer · "atualizado há Nd" · "rel X.XX" annotations and the snippet (issue #140).
        Instant now = Instant.parse("2026-06-28T00:00:00Z");
        Instant updated = now.minus(Duration.ofDays(3));
        RecallHit h = new RecallHit(
                HitSource.PAGE, "id-a", "concepts/recall.md", "Hybrid recall", null, 0.91, 1,
                "fusion <mark>blends</mark> signals", updated, MemoryLayer.EPISODIC);
        RecallInjection injection = new RecallInjection(
                new StubRecall(RecallResult.ofPages(List.of(h))), cfg(5, 0.0, 1200), null,
                Clock.fixed(now, ZoneOffset.UTC));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        assertThat(out.text()).startsWith("## Relevant project memory");
        // Path-first lead, then the three metadata segments, then the mark-stripped snippet.
        assertThat(out.text()).contains(
                "- concepts/recall.md · episodic · atualizado há 3d · rel 0.91 — fusion blends signals");
        assertThat(out.text()).doesNotContain("<mark>");
    }

    @Test
    void recencyLabelReadsTodayForASamedayHit() {
        Instant now = Instant.parse("2026-06-28T12:00:00Z");
        RecallHit h = new RecallHit(
                HitSource.PAGE, "id-a", "p/a.md", "A", null, 0.5, 1, "body",
                now.minus(Duration.ofHours(2)), MemoryLayer.WORKING);
        RecallInjection injection = new RecallInjection(
                new StubRecall(RecallResult.ofPages(List.of(h))), cfg(5, 0.0, 1200), null,
                Clock.fixed(now, ZoneOffset.UTC));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        assertThat(out.text()).contains("- p/a.md · working · atualizado hoje · rel 0.50 — body");
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
        assertThat(out.text()).contains("p/a.md").doesNotContain("p/b.md");
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
        assertThat(out.text()).contains("p/a.md").contains("p/b.md").doesNotContain("p/c.md");
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
        assertThat(out.text()).contains("p/a.md").contains("p/b.md").doesNotContain("p/c.md");
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

        // Cap is 2; the two kept hits are the top and the first strong backfill (Strong2=p/c.md), not
        // Weak1=p/b.md.
        assertThat(out.hits()).isEqualTo(2);
        assertThat(out.text()).contains("p/a.md").contains("p/c.md").doesNotContain("p/b.md");
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
        assertThat(out.text()).contains("p/a.md").contains("p/b.md");
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
        assertThat(out.text()).contains("p/a.md").contains("p/b.md");
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
        assertThat(out.text()).contains("p/a.md").doesNotContain("p/b.md");
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

    // --- Synthesized brief (issue #135, Fase 3) ------------------------------------------------

    @Test
    void briefReplacesBulletsWhenGateApprovesAndSynthesizerIsRelevant() {
        RecallResult r = RecallResult.ofPages(List.of(
                page("a", "Alpha", "alpha body", 0.90, 1),
                page("b", "Beta", "beta body", 0.80, 2)), /*calibrated*/ true);
        TestDoubleProvider llm = scriptedProvider(
                "{\"relevant\":true,\"brief\":\"The server runs behind Traefik.\","
                        + "\"cited_paths\":[\"p/a.md\",\"p/b.md\"]}");
        RecallInjection injection = new RecallInjection(
                new StubRecall(r), cfgBriefEnabled(5, 0.0, 0.35, 1200, 4000), synthesizer(llm));

        RecallInjection.Result out = injection.inject(SCOPE, "where does the server run");

        // The block is the synthesized paragraph + Sources, NOT the raw snippet bullets.
        assertThat(out.text()).startsWith("## Relevant project memory");
        assertThat(out.text()).contains("The server runs behind Traefik.");
        assertThat(out.text()).contains("Sources: p/a.md, p/b.md");
        // The brief paragraph replaced the bullets, so no per-hit "· rel" metadata line is present.
        assertThat(out.text()).doesNotContain("· rel");
        assertThat(out.hits()).isEqualTo(2); // the brief covers both gated hits
        assertThat(llm.chatCalls()).hasSize(1); // exactly one generative call
    }

    @Test
    void fallsBackToBulletsWhenSynthesizerSaysNotRelevant() {
        RecallResult r = RecallResult.ofPages(List.of(page("a", "Alpha", "alpha body", 0.90, 1)), true);
        TestDoubleProvider llm = scriptedProvider("{\"relevant\":false,\"brief\":\"\",\"cited_paths\":[]}");
        RecallInjection injection = new RecallInjection(
                new StubRecall(r), cfgBriefEnabled(5, 0.0, 0.35, 1200, 4000), synthesizer(llm));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        // relevant:false → the bullets, never worse than the pre-Fase-3 baseline.
        assertThat(out.text()).contains("- p/a.md");
        assertThat(out.text()).doesNotContain("Sources:");
        assertThat(llm.chatCalls()).hasSize(1); // the call ran, but its verdict was "not relevant"
    }

    @Test
    void fallsBackToBulletsOnSynthesizerFailureOrTimeout() {
        // A provider error stands in for both a hard failure and a per-call HTTP timeout (a timeout
        // surfaces as a provider exception); either way the injection degrades to the bullets, never
        // throwing out of inject().
        RecallResult r = RecallResult.ofPages(List.of(page("a", "Alpha", "alpha body", 0.90, 1)), true);
        TestDoubleProvider llm = scriptedProvider(req -> {
            throw new com.agentmemory.llm.LlmException("simulated timeout");
        });
        RecallInjection injection = new RecallInjection(
                new StubRecall(r), cfgBriefEnabled(5, 0.0, 0.35, 1200, 4000), synthesizer(llm));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        assertThat(out.text()).contains("- p/a.md");
        assertThat(out.text()).doesNotContain("Sources:");
    }

    @Test
    void fallsBackToBulletsWhenBriefIsBlank() {
        // relevant:true but an all-whitespace brief is unusable → bullets.
        RecallResult r = RecallResult.ofPages(List.of(page("a", "Alpha", "alpha body", 0.90, 1)), true);
        TestDoubleProvider llm = scriptedProvider(
                "{\"relevant\":true,\"brief\":\"   \",\"cited_paths\":[\"p/a.md\"]}");
        RecallInjection injection = new RecallInjection(
                new StubRecall(r), cfgBriefEnabled(5, 0.0, 0.35, 1200, 4000), synthesizer(llm));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        assertThat(out.text()).contains("- p/a.md");
        assertThat(out.text()).doesNotContain("Sources:");
    }

    @Test
    void calibratedGateRejectsLowSignalSoTheBriefIsNeverCalled() {
        // Calibrated top 0.20 < the 0.35 absolute floor → empty block, and the synthesizer is NEVER
        // called: the common low-signal prompt makes no generative call (the Fase 0-2 latency property).
        RecallResult r = RecallResult.ofPages(List.of(page("a", "Alpha", "weak", 0.20, 1)), true);
        TestDoubleProvider llm = scriptedProvider(
                "{\"relevant\":true,\"brief\":\"unused\",\"cited_paths\":[]}");
        RecallInjection injection = new RecallInjection(
                new StubRecall(r), cfgBriefEnabled(5, 0.0, 0.35, 1200, 4000), synthesizer(llm));

        RecallInjection.Result out = injection.inject(SCOPE, "ok thanks");

        assertThat(out.isEmpty()).isTrue();
        assertThat(llm.chatCalls()).isEmpty(); // gate rejected before any generative call
    }

    @Test
    void uncalibratedScoresSkipTheBriefAndRenderBullets() {
        // Brief enabled + a real match, but the scores are UNCALIBRATED (RRF / LLM rerank, not the
        // cross-encoder). The brief fires only on calibrated scores, so this renders bullets, no call.
        RecallResult r = RecallResult.ofPages(List.of(
                page("a", "Alpha", "alpha body", 0.90, 1))); // calibrated == false
        TestDoubleProvider llm = scriptedProvider(
                "{\"relevant\":true,\"brief\":\"unused\",\"cited_paths\":[\"p/a.md\"]}");
        RecallInjection injection = new RecallInjection(
                new StubRecall(r), cfgBriefEnabled(5, 0.0, 0.35, 1200, 4000), synthesizer(llm));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        assertThat(out.text()).contains("- p/a.md");
        assertThat(llm.chatCalls()).isEmpty();
    }

    @Test
    void briefDisabledRendersBulletsEvenWithASynthesizerPresent() {
        RecallResult r = RecallResult.ofPages(List.of(page("a", "Alpha", "alpha body", 0.90, 1)), true);
        TestDoubleProvider llm = scriptedProvider(
                "{\"relevant\":true,\"brief\":\"unused\",\"cited_paths\":[\"p/a.md\"]}");
        // cfg() defaults the brief to DISABLED; the synthesizer is wired but must not be consulted.
        RecallInjection injection = new RecallInjection(
                new StubRecall(r), cfg(5, 0.0, 0.35, 1200), synthesizer(llm));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        assertThat(out.text()).contains("- p/a.md");
        assertThat(llm.chatCalls()).isEmpty();
    }

    @Test
    void briefCallCarriesMinimalReasoningAndTheConfiguredPerCallTimeout() {
        RecallResult r = RecallResult.ofPages(List.of(page("a", "Alpha", "alpha body", 0.90, 1)), true);
        TestDoubleProvider llm = scriptedProvider(
                "{\"relevant\":true,\"brief\":\"A brief.\",\"cited_paths\":[\"p/a.md\"]}");
        BriefSynthesizer synth = new BriefSynthesizer(llm, PROMPTS, ReasoningEffort.MINIMAL);
        RecallInjection injection = new RecallInjection(
                new StubRecall(r), cfgBriefEnabled(5, 0.0, 0.35, 1200, 2500), synth);

        injection.inject(SCOPE, "prompt");

        ChatRequest sent = llm.chatCalls().get(0);
        assertThat(sent.reasoningEffort()).isEqualTo(ReasoningEffort.MINIMAL);
        // The per-call timeout is the configured injection.brief.timeout-ms (the latency bound).
        assertThat(sent.requestTimeout()).isEqualTo(Duration.ofMillis(2500));
        assertThat(sent.wantsStructuredOutput()).isTrue();
    }

    @Test
    void briefThatOverflowsTheCharBudgetFallsBackToBullets() {
        RecallResult r = RecallResult.ofPages(List.of(page("a", "Alpha", "alpha body", 0.90, 1)), true);
        String hugeBrief = "x".repeat(5000);
        TestDoubleProvider llm = scriptedProvider(
                "{\"relevant\":true,\"brief\":\"" + hugeBrief + "\",\"cited_paths\":[\"p/a.md\"]}");
        RecallInjection injection = new RecallInjection(
                new StubRecall(r), cfgBriefEnabled(5, 0.0, 0.35, 200, 4000), synthesizer(llm));

        RecallInjection.Result out = injection.inject(SCOPE, "prompt");

        // The brief alone exceeds the 200-char budget → fall back to the bounded bullets.
        assertThat(out.text()).contains("- p/a.md");
        assertThat(out.text().length()).isLessThanOrEqualTo(200);
    }
}
