package com.agentmemory.llmrecall;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.llm.TestDoubleProvider;
import com.agentmemory.recall.HitSource;
import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.RecallQuery;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.RecallService;
import com.agentmemory.recall.Scope;
import java.util.ArrayList;
import java.util.List;
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

    /** A reinforcer that records the hits it was asked to reinforce. */
    private static final class RecordingReinforcer implements AccessReinforcer {
        List<RecallHit> reinforced;
        Scope scope;

        @Override
        public void reinforce(Scope scope, List<RecallHit> hits) {
            this.scope = scope;
            this.reinforced = hits;
        }
    }

    private static LlmRecallProperties props(boolean enabled, int maxCalls, int minRerank, boolean expand) {
        return new LlmRecallProperties(
                enabled, 20, maxCalls, minRerank,
                new LlmRecallProperties.Expansion(expand, 4),
                new LlmRecallProperties.Injection(5, 0.0, 1200));
    }

    private static CandidateReranker reranker(TestDoubleProvider llm) {
        return new CandidateReranker(llm, PROMPTS, 20);
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
        // reinforcement still fires on the returned hits.
        assertThat(reinforcer.reinforced).extracting(RecallHit::id).containsExactly("a", "b");
        assertThat(reinforcer.scope).isEqualTo(SCOPE);
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
        // reinforcement saw the final, re-ranked, trimmed hits.
        assertThat(reinforcer.reinforced).extracting(RecallHit::id).containsExactly("c", "b", "a");
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
        // seam is still invoked uniformly.
        assertThat(reinforcer.reinforced).hasSize(2);
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
}
