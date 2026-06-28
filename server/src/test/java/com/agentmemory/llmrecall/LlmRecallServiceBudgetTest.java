package com.agentmemory.llmrecall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agentmemory.llm.CrossEncoderClient;
import com.agentmemory.llm.TestDoubleProvider;
import com.agentmemory.recall.HitSource;
import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.RecallQuery;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.RecallService;
import com.agentmemory.recall.Scope;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link LlmRecallService}'s orchestration: the per-query LLM-call budget and the
 * fast paths (feature off / budget 0 / too-few-candidates) bypass the model, the raw-observation
 * fallback is not re-ranked, and {@link AccessReinforcer} fires on exactly the returned hits. Uses a
 * scripted offline provider and hand-stubbed collaborators; no Spring, no DB.
 */
class LlmRecallServiceBudgetTest {

    private static final Scope SCOPE = Scope.of("ws", "proj");
    private static final RecallPrompts PROMPTS = new RecallPrompts();

    private static RecallHit page(String id, int rank) {
        return new RecallHit(
                HitSource.PAGE, id, "p/" + id + ".md", "Title " + id, null, 1.0 / rank, rank, "snip " + id);
    }

    /** A base recall stub that returns the given page hits and records every limit it was asked for. */
    private static final class StubBase implements RecallService {
        private final RecallResult result;
        final List<Integer> limits = new ArrayList<>();

        StubBase(RecallResult result) {
            this.result = result;
        }

        @Override
        public RecallResult search(RecallQuery query) {
            limits.add(query.limit());
            return result;
        }
    }

    /**
     * A reinforcer that records the hits it was asked to reinforce. Reinforcement now runs off the
     * response hot path (issue #130 follow-up), so the recording fields are {@code volatile} and tests
     * read them through Awaitility — the executor thread writes, the test thread polls.
     */
    private static final class RecordingReinforcer implements AccessReinforcer {
        volatile List<RecallHit> reinforced;
        volatile Scope scope;

        @Override
        public void reinforce(Scope scope, List<RecallHit> hits) {
            this.scope = scope;
            this.reinforced = hits;
        }
    }

    private static LlmRecallProperties props(boolean enabled, int maxCalls, int minRerank, boolean expand) {
        return props(enabled, maxCalls, minRerank, expand, 6000L);
    }

    private static LlmRecallProperties props(
            boolean enabled, int maxCalls, int minRerank, boolean expand, long budgetMs) {
        return new LlmRecallProperties(
                enabled, 20, maxCalls, minRerank, budgetMs, /*minimalReasoning*/ true,
                new LlmRecallProperties.CrossEncoder(true, "rerank-2-lite", 50),
                new LlmRecallProperties.Expansion(expand, 4),
                new LlmRecallProperties.Injection(5, 0.0, 0.35, 1200, null));
    }

    /**
     * The LLM-only reranker behind the Fase-2 {@link Reranker} seam (no cross-encoder client): exercises
     * exactly the LLM rerank path these budget tests assert, now via {@link CrossEncoderReranker}'s
     * fallback. The decorator is agnostic to which reranker ran.
     */
    private static Reranker reranker(TestDoubleProvider llm) {
        return new CrossEncoderReranker(null, new CandidateReranker(llm, PROMPTS, 20), 50);
    }

    private static QueryExpander expander(TestDoubleProvider llm) {
        return new QueryExpander(llm, PROMPTS, 4);
    }

    @Test
    void fastPathWhenDisabledDelegatesStraightToBaseWithNoLlmCall() {
        StubBase base = new StubBase(RecallResult.ofPages(List.of(page("a", 1), page("b", 2))));
        TestDoubleProvider llm = TestDoubleProvider.create();
        RecordingReinforcer reinforcer = new RecordingReinforcer();
        LlmRecallService svc = new LlmRecallService(
                base, expander(llm), reranker(llm), reinforcer, props(false, 2, 2, true));

        RecallResult out = svc.search(new RecallQuery("q", SCOPE, 10));

        assertThat(out.hits()).extracting(RecallHit::id).containsExactly("a", "b");
        assertThat(llm.chatCalls()).isEmpty(); // no expansion, no rerank
        // base was called once with the caller's own limit (no over-fetch on the fast path).
        assertThat(base.limits).containsExactly(10);
        // reinforcement still fires on the returned hits (now off the hot path → asserted via await).
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(reinforcer.reinforced).extracting(RecallHit::id).containsExactly("a", "b");
            assertThat(reinforcer.scope).isEqualTo(SCOPE);
        });
    }

    @Test
    void zeroCallBudgetTakesTheFastPath() {
        StubBase base = new StubBase(RecallResult.ofPages(List.of(page("a", 1), page("b", 2))));
        TestDoubleProvider llm = TestDoubleProvider.create();
        LlmRecallService svc = new LlmRecallService(
                base, expander(llm), reranker(llm), new RecordingReinforcer(), props(true, 0, 2, true));

        svc.search(new RecallQuery("q", SCOPE, 10));

        assertThat(llm.chatCalls()).isEmpty();
        assertThat(base.limits).containsExactly(10);
    }

    @Test
    void rerankRunsAndReordersWhenWithinBudget() {
        StubBase base = new StubBase(RecallResult.ofPages(List.of(page("a", 1), page("b", 2), page("c", 3))));
        // Expansion gets the default echo reply (no terms → no-op); rerank gets a real ranking.
        TestDoubleProvider llm = TestDoubleProvider.builder()
                .chatResponder(req -> {
                    String schema = req.schema() == null ? "" : req.schema().name();
                    if (RecallPrompts.RERANK_SCHEMA_NAME.equals(schema)) {
                        return "{\"rankings\":[{\"id\":\"a\",\"relevance\":0.1},"
                                + "{\"id\":\"b\",\"relevance\":0.2},"
                                + "{\"id\":\"c\",\"relevance\":0.9}]}";
                    }
                    return "{\"terms\":[]}"; // expansion: add nothing
                })
                .build();
        RecordingReinforcer reinforcer = new RecordingReinforcer();
        LlmRecallService svc = new LlmRecallService(
                base, expander(llm), reranker(llm), reinforcer, props(true, 2, 2, true));

        RecallResult out = svc.search(new RecallQuery("q", SCOPE, 10));

        // Scores a=0.1, b=0.2, c=0.9 → descending order is c, b, a.
        assertThat(out.hits()).extracting(RecallHit::id).containsExactly("c", "b", "a");
        // two LLM calls: one expansion, one rerank.
        assertThat(llm.chatCalls()).hasSize(2);
        // base was over-fetched to the candidate pool (max(limit, maxCandidates) = 20).
        assertThat(base.limits).containsExactly(20);
        // reinforcement saw the final, re-ranked, trimmed hits (off the hot path → asserted via await).
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(reinforcer.reinforced).extracting(RecallHit::id).containsExactly("c", "b", "a"));
    }

    @Test
    void singleCallBudgetFundsRerankNotExpansion() {
        // maxCallsPerQuery=1 with expansion enabled: the budget must go to RE-RANK (the higher-value
        // step per the contract), NOT be consumed by expansion. So exactly one LLM call is made and it
        // is the rerank; the result is re-ordered.
        StubBase base = new StubBase(RecallResult.ofPages(List.of(page("a", 1), page("b", 2), page("c", 3))));
        TestDoubleProvider llm = TestDoubleProvider.builder()
                .chatResponder(req -> {
                    String schema = req.schema() == null ? "" : req.schema().name();
                    if (RecallPrompts.RERANK_SCHEMA_NAME.equals(schema)) {
                        return "{\"rankings\":[{\"id\":\"a\",\"relevance\":0.1},"
                                + "{\"id\":\"b\",\"relevance\":0.2},"
                                + "{\"id\":\"c\",\"relevance\":0.9}]}";
                    }
                    return "{\"terms\":[\"should-not-be-called\"]}";
                })
                .build();
        LlmRecallService svc = new LlmRecallService(
                base, expander(llm), reranker(llm), new RecordingReinforcer(),
                props(true, /*maxCalls*/ 1, 2, /*expand*/ true));

        RecallResult out = svc.search(new RecallQuery("q", SCOPE, 10));

        assertThat(out.hits()).extracting(RecallHit::id).containsExactly("c", "b", "a");
        assertThat(llm.chatCalls()).hasSize(1); // exactly one call...
        assertThat(llm.chatCalls().get(0).schema().name())
                .as("the single budgeted call funds rerank, not expansion")
                .isEqualTo(RecallPrompts.RERANK_SCHEMA_NAME);
    }

    @Test
    void noOverFetchWhenRerankCannotRun() {
        // maxCallsPerQuery=0 disables all LLM work; the base must be queried with the caller's own
        // limit (no wasted over-fetch to the candidate pool).
        StubBase base = new StubBase(RecallResult.ofPages(List.of(page("a", 1))));
        TestDoubleProvider llm = TestDoubleProvider.create();
        LlmRecallService svc = new LlmRecallService(
                base, expander(llm), reranker(llm), new RecordingReinforcer(), props(true, 0, 2, true));

        svc.search(new RecallQuery("q", SCOPE, 7));

        assertThat(base.limits).containsExactly(7); // exact limit, not the 20-candidate pool
        assertThat(llm.chatCalls()).isEmpty();
    }

    @Test
    void rerankSkippedWhenTooFewCandidates() {
        // Only one hit; minRerankCandidates=2, so rerank must not run (but expansion still does).
        StubBase base = new StubBase(RecallResult.ofPages(List.of(page("a", 1))));
        TestDoubleProvider llm = TestDoubleProvider.builder()
                .chatResponder(req -> "{\"terms\":[]}")
                .build();
        LlmRecallService svc = new LlmRecallService(
                base, expander(llm), reranker(llm), new RecordingReinforcer(), props(true, 2, 2, true));

        RecallResult out = svc.search(new RecallQuery("q", SCOPE, 10));

        assertThat(out.hits()).extracting(RecallHit::id).containsExactly("a");
        assertThat(llm.chatCalls()).hasSize(1); // expansion only; no rerank call
    }

    @Test
    void expansionDisabledSpendsNoCallOnIt() {
        StubBase base = new StubBase(RecallResult.ofPages(List.of(page("a", 1), page("b", 2))));
        TestDoubleProvider llm = TestDoubleProvider.builder()
                .chatResponder(req -> "{\"rankings\":[{\"id\":\"a\",\"relevance\":0.9},"
                        + "{\"id\":\"b\",\"relevance\":0.1}]}")
                .build();
        LlmRecallService svc = new LlmRecallService(
                base, expander(llm), reranker(llm), new RecordingReinforcer(),
                props(true, 2, 2, /*expand*/ false));

        RecallResult out = svc.search(new RecallQuery("q", SCOPE, 10));

        assertThat(out.hits()).extracting(RecallHit::id).containsExactly("a", "b");
        assertThat(llm.chatCalls()).hasSize(1); // only the rerank call
    }

    @Test
    void rawFallbackIsNotRerankedButIsStillReinforcedAndTrimmed() {
        List<RecallHit> raw = List.of(
                new RecallHit(HitSource.RAW_OBSERVATION, "o1", null, "obs", "k", 1.0, 1, "s1"),
                new RecallHit(HitSource.RAW_OBSERVATION, "o2", null, "obs", "k", 0.5, 2, "s2"),
                new RecallHit(HitSource.RAW_OBSERVATION, "o3", null, "obs", "k", 0.33, 3, "s3"));
        StubBase base = new StubBase(RecallResult.ofRawFallback(raw));
        TestDoubleProvider llm = TestDoubleProvider.builder()
                .chatResponder(req -> "{\"terms\":[]}")
                .build();
        RecordingReinforcer reinforcer = new RecordingReinforcer();
        LlmRecallService svc = new LlmRecallService(
                base, expander(llm), reranker(llm), reinforcer, props(true, 2, 2, true));

        RecallResult out = svc.search(new RecallQuery("q", SCOPE, 2));

        assertThat(out.rawFallback()).isTrue();
        assertThat(out.hits()).hasSize(2); // trimmed to limit
        // expansion ran (1 call) but rerank did NOT (fallback path), so no rerank schema call.
        assertThat(llm.chatCalls()).hasSize(1);
        assertThat(llm.chatCalls().get(0).schema().name()).isEqualTo(RecallPrompts.EXPANSION_SCHEMA_NAME);
        // reinforcer fired on the (raw) hits — JdbcAccessReinforcer would ignore non-PAGE ids, but the
        // seam is still invoked uniformly (off the hot path → asserted via await).
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(reinforcer.reinforced).hasSize(2));
    }

    @Test
    void budgetExhaustionAbandonsRerankAndReturnsHybridNotEmpty() {
        // The wall-clock budget is spent before the re-rank step (issue #130): the LLM step must be
        // ABANDONED and the already-computed hybrid (RRF) hits returned — never empty, never reordered.
        StubBase base = new StubBase(RecallResult.ofPages(List.of(page("a", 1), page("b", 2), page("c", 3))));
        TestDoubleProvider llm = TestDoubleProvider.builder()
                // If the rerank ever fired it would reverse the order; proving it does NOT run.
                .chatResponder(req -> "{\"rankings\":[{\"id\":\"c\",\"relevance\":0.9},"
                        + "{\"id\":\"b\",\"relevance\":0.5},{\"id\":\"a\",\"relevance\":0.1}]}")
                .build();
        // Clock: first read is the deadline anchor (t=1000, budget 6000 -> deadline 7000); every later
        // read is past the deadline, so `remaining` is null when the re-rank step checks it.
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.LongSupplier clock = () -> reads.getAndIncrement() == 0 ? 1_000L : 1_000_000L;
        RecordingReinforcer reinforcer = new RecordingReinforcer();
        LlmRecallService svc = new LlmRecallService(
                base, expander(llm), reranker(llm), reinforcer,
                props(true, 2, 2, /*expand*/ false, /*budgetMs*/ 6000L), clock);

        RecallResult out = svc.search(new RecallQuery("q", SCOPE, 10));

        // Degraded to the hybrid result: original RRF order, NOT empty, NOT reordered by the LLM.
        assertThat(out.hits()).extracting(RecallHit::id).containsExactly("a", "b", "c");
        assertThat(llm.chatCalls()).as("budget exhausted -> no LLM call").isEmpty();
        // The base hybrid retrieve (Tier 0) still ran and is what we degraded to.
        assertThat(base.limits).containsExactly(20);
        // Reinforcement still fires on the returned (hybrid) hits (off the hot path → asserted via await).
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(reinforcer.reinforced).extracting(RecallHit::id).containsExactly("a", "b", "c"));
    }

    @Test
    void rerankCallCarriesAShortPerCallTimeoutDerivedFromTheBudget() {
        // Within budget the re-rank runs AND the call carries a real per-call HTTP timeout derived from
        // the remaining budget (issue #130) — short, never the provider-wide 300s default.
        StubBase base = new StubBase(RecallResult.ofPages(List.of(page("a", 1), page("b", 2))));
        TestDoubleProvider llm = TestDoubleProvider.builder()
                .chatResponder(req -> "{\"rankings\":[{\"id\":\"a\",\"relevance\":0.9},"
                        + "{\"id\":\"b\",\"relevance\":0.1}]}")
                .build();
        LlmRecallService svc = new LlmRecallService(
                base, null, reranker(llm), new RecordingReinforcer(),
                props(true, 2, 2, /*expand*/ false, /*budgetMs*/ 6000L));

        svc.search(new RecallQuery("q", SCOPE, 10));

        assertThat(llm.chatCalls()).hasSize(1);
        java.time.Duration timeout = llm.chatCalls().get(0).requestTimeout();
        assertThat(timeout).as("recall call is bounded by a per-call timeout").isNotNull();
        assertThat(timeout).isPositive();
        assertThat(timeout).isLessThanOrEqualTo(java.time.Duration.ofMillis(6000));
    }

    @Test
    void crossEncoderRerankReordersAndMarksResultCalibrated() {
        // With a cross-encoder client present behind the seam, the result is re-ordered by its calibrated
        // scores AND flagged calibrated() so the injection layer may apply the absolute gate (Fase 2).
        StubBase base = new StubBase(RecallResult.ofPages(List.of(page("a", 1), page("b", 2), page("c", 3))));
        // Head order is a,b,c; score a=0.5, b=0.1, c=0.9 -> descending order is c, a, b.
        CrossEncoderClient cross = (q, docs, t) -> new double[] {0.5, 0.1, 0.9};
        // The LLM fallback would reverse the order if it ever ran; proving the cross-encoder won.
        TestDoubleProvider llm = TestDoubleProvider.builder()
                .chatResponder(req -> "{\"rankings\":[{\"id\":\"a\",\"relevance\":0.9},"
                        + "{\"id\":\"b\",\"relevance\":0.5},{\"id\":\"c\",\"relevance\":0.1}]}")
                .build();
        Reranker reranker = new CrossEncoderReranker(cross, new CandidateReranker(llm, PROMPTS, 20), 50);
        LlmRecallService svc = new LlmRecallService(
                base, null, reranker, new RecordingReinforcer(), props(true, 2, 2, false));

        RecallResult out = svc.search(new RecallQuery("q", SCOPE, 10));

        assertThat(out.calibrated()).as("cross-encoder scores are calibrated").isTrue();
        assertThat(out.hits()).extracting(RecallHit::id).containsExactly("c", "a", "b");
        assertThat(out.hits().get(0).score()).isEqualTo(0.9);
        assertThat(llm.chatCalls()).as("cross-encoder ran; the LLM fallback did not").isEmpty();
    }

    @Test
    void crossEncoderFailureDegradesToLlmRerankUncalibrated() {
        // The cross-encoder throws; the seam degrades to the LLM reranker, whose result is uncalibrated.
        StubBase base = new StubBase(RecallResult.ofPages(List.of(page("a", 1), page("b", 2), page("c", 3))));
        CrossEncoderClient failing = (q, docs, t) -> {
            throw new com.agentmemory.llm.LlmException("voyage down");
        };
        TestDoubleProvider llm = TestDoubleProvider.builder()
                .chatResponder(req -> "{\"rankings\":[{\"id\":\"a\",\"relevance\":0.1},"
                        + "{\"id\":\"b\",\"relevance\":0.5},{\"id\":\"c\",\"relevance\":0.9}]}")
                .build();
        Reranker reranker = new CrossEncoderReranker(failing, new CandidateReranker(llm, PROMPTS, 20), 50);
        LlmRecallService svc = new LlmRecallService(
                base, null, reranker, new RecordingReinforcer(), props(true, 2, 2, false));

        RecallResult out = svc.search(new RecallQuery("q", SCOPE, 10));

        // LLM fallback ran: a=0.1, b=0.5, c=0.9 -> c, b, a; and the result is NOT calibrated.
        assertThat(out.hits()).extracting(RecallHit::id).containsExactly("c", "b", "a");
        assertThat(out.calibrated()).isFalse();
        assertThat(llm.chatCalls()).hasSize(1);
    }

    @Test
    void nullExpanderIsTolerated() {
        StubBase base = new StubBase(RecallResult.ofPages(List.of(page("a", 1), page("b", 2))));
        TestDoubleProvider llm = TestDoubleProvider.builder()
                .chatResponder(req -> "{\"rankings\":[{\"id\":\"a\",\"relevance\":0.9},"
                        + "{\"id\":\"b\",\"relevance\":0.1}]}")
                .build();
        // expander is null (as when expansion is disabled at wiring time): only rerank runs.
        LlmRecallService svc = new LlmRecallService(
                base, null, reranker(llm), new RecordingReinforcer(), props(true, 2, 2, true));

        RecallResult out = svc.search(new RecallQuery("q", SCOPE, 10));

        assertThat(out.hits()).extracting(RecallHit::id).containsExactly("a", "b");
        assertThat(llm.chatCalls()).hasSize(1);
    }

    @Test
    void recordsPerStageTimersWhenAMeterRegistryIsPresent() {
        // Per-stage latency instrumentation (issue #130 follow-up): with a Micrometer registry wired,
        // the search records a total timer plus a per-stage timer for the stages that actually ran —
        // retrieve and re-rank here; expansion is disabled, so its stage timer is never created.
        StubBase base = new StubBase(RecallResult.ofPages(List.of(page("a", 1), page("b", 2), page("c", 3))));
        TestDoubleProvider llm = TestDoubleProvider.builder()
                .chatResponder(req -> "{\"rankings\":[{\"id\":\"a\",\"relevance\":0.1},"
                        + "{\"id\":\"b\",\"relevance\":0.2},{\"id\":\"c\",\"relevance\":0.9}]}")
                .build();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmRecallService svc = new LlmRecallService(
                base, expander(llm), reranker(llm), new RecordingReinforcer(),
                props(true, 2, 2, /*expand*/ false), System::currentTimeMillis, new RecallMetrics(registry));

        svc.search(new RecallQuery("q", SCOPE, 10));

        assertThat(registry.get(RecallMetrics.SEARCH_TIMER).timer().count()).isEqualTo(1);
        assertThat(registry.get(RecallMetrics.STAGE_TIMER).tag("stage", "retrieve").timer().count())
                .isEqualTo(1);
        assertThat(registry.get(RecallMetrics.STAGE_TIMER).tag("stage", "rerank").timer().count())
                .isEqualTo(1);
        // Expansion was disabled → its stage timer was never recorded (skipped stages stay off the meters).
        assertThat(registry.find(RecallMetrics.STAGE_TIMER).tag("stage", "expand").timer()).isNull();
    }

    @Test
    void reinforcementRunsOffTheHotPathWithoutBlockingTheResponse() throws Exception {
        // Issue #130 follow-up: a slow reinforcer must not add to the response latency. The reinforcer
        // blocks until the test releases it; if reinforcement were synchronous, search() would never
        // return (the release only happens after it returns) and this test would deadlock. That search
        // returns at all proves the bump runs off the hot path — and it still applies best-effort.
        StubBase base = new StubBase(RecallResult.ofPages(List.of(page("a", 1), page("b", 2))));
        TestDoubleProvider llm = TestDoubleProvider.create();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<RecallHit> reinforced = new CopyOnWriteArrayList<>();
        AccessReinforcer slow = (scope, hits) -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reinforced.addAll(hits);
        };
        try (LlmRecallService svc = new LlmRecallService(
                base, null, reranker(llm), slow, props(/*enabled*/ false, 0, 2, false))) {

            RecallResult out = svc.search(new RecallQuery("q", SCOPE, 10));

            // Response is back while the reinforcer is still blocked mid-flight.
            assertThat(out.hits()).extracting(RecallHit::id).containsExactly("a", "b");
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(reinforced).as("reinforcement has not completed yet (still blocked)").isEmpty();

            // Releasing it lets the best-effort bump finish off the hot path.
            release.countDown();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(reinforced).extracting(RecallHit::id).containsExactly("a", "b"));
        }
    }
}
