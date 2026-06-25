package com.agentmemory.llmrecall;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.TestDoubleProvider;
import com.agentmemory.recall.HitSource;
import com.agentmemory.recall.RecallHit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link CandidateReranker} with a scripted, offline {@link TestDoubleProvider}: the
 * LLM relevance scores re-order the RRF input (acceptance: "beats raw RRF"), the cost cap K bounds how
 * many candidates are sent while keeping the tail, and every failure mode degrades to the unchanged RRF
 * order. No Spring, no DB.
 */
class CandidateRerankerTest {

    private static final RecallPrompts PROMPTS = new RecallPrompts();

    /** A PAGE hit with the given id/rank; title/snippet derived so the prompt is well-formed. */
    private static RecallHit page(String id, int rank) {
        return new RecallHit(
                HitSource.PAGE, id, "p/" + id + ".md", "Title " + id, null,
                1.0 / rank, rank, "snippet for " + id);
    }

    private static List<RecallHit> pages(String... ids) {
        List<RecallHit> hits = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            hits.add(page(ids[i], i + 1));
        }
        return hits;
    }

    /** A provider that returns a fixed rerank JSON regardless of the request. */
    private static TestDoubleProvider scripted(String json) {
        return TestDoubleProvider.builder().chatResponder(req -> json).build();
    }

    /** A provider that runs an arbitrary responder (e.g. to count calls or branch on the request). */
    private static TestDoubleProvider scripted(Function<ChatRequest, String> responder) {
        return TestDoubleProvider.builder().chatResponder(responder).build();
    }

    @Test
    void llmScoresReorderTheRrfInput() {
        // RRF order is a, b, c; the LLM says c is most relevant, then a, then b.
        List<RecallHit> rrf = pages("a", "b", "c");
        TestDoubleProvider llm = scripted(
                "{\"rankings\":[{\"id\":\"a\",\"relevance\":0.5},"
                        + "{\"id\":\"b\",\"relevance\":0.1},"
                        + "{\"id\":\"c\",\"relevance\":0.9}]}");
        CandidateReranker reranker = new CandidateReranker(llm, PROMPTS, 20);

        List<RecallHit> out = reranker.rerank("query", rrf);

        assertThat(out).extracting(RecallHit::id).containsExactly("c", "a", "b");
        // ranks are re-stamped 1..n and the top score is the highest relevance.
        assertThat(out.get(0).rank()).isEqualTo(1);
        assertThat(out.get(0).score()).isEqualTo(0.9);
        assertThat(out.get(1).rank()).isEqualTo(2);
        assertThat(out.get(2).rank()).isEqualTo(3);
        // exactly one LLM call was made.
        assertThat(llm.chatCalls()).hasSize(1);
        assertThat(llm.chatCalls().get(0).wantsStructuredOutput()).isTrue();
    }

    @Test
    void capsCandidatesAtKAndKeepsTheTailInRrfOrder() {
        // 5 candidates, K=3: only a,b,c are re-ranked; d,e stay appended in RRF order.
        List<RecallHit> rrf = pages("a", "b", "c", "d", "e");
        // Reverse the head: c,b,a.
        TestDoubleProvider llm = scripted(
                "{\"rankings\":[{\"id\":\"a\",\"relevance\":0.2},"
                        + "{\"id\":\"b\",\"relevance\":0.5},"
                        + "{\"id\":\"c\",\"relevance\":0.9}]}");
        CandidateReranker reranker = new CandidateReranker(llm, PROMPTS, 3);

        List<RecallHit> out = reranker.rerank("query", rrf);

        assertThat(out).extracting(RecallHit::id).containsExactly("c", "b", "a", "d", "e");
        // The prompt only mentioned the top-K ids (d, e were never sent to the LLM).
        String sentToLlm = llm.chatCalls().get(0).messages().get(1).content();
        assertThat(sentToLlm).contains("id: a").contains("id: b").contains("id: c");
        assertThat(sentToLlm).doesNotContain("id: d").doesNotContain("id: e");
    }

    @Test
    void partialRankingFallsBackToRrfOrderNeverWorseThanBaseline() {
        // The LLM scores only a and c; b is omitted. A partial ranking is untrustworthy (an omitted
        // strong hit could be demoted), so the reranker must NOT reorder — it returns the RRF input
        // unchanged, guaranteeing it is never worse than the baseline.
        List<RecallHit> rrf = pages("a", "b", "c");
        TestDoubleProvider llm = scripted(
                "{\"rankings\":[{\"id\":\"a\",\"relevance\":0.8},{\"id\":\"c\",\"relevance\":0.4}]}");
        CandidateReranker reranker = new CandidateReranker(llm, PROMPTS, 20);

        List<RecallHit> out = reranker.rerank("query", rrf);

        assertThat(out).isSameAs(rrf); // partial ranking → RRF order unchanged
    }

    @Test
    void tailBeyondKKeepsItsRrfScoreNotZero() {
        // 4 candidates, K=2: a,b are re-ranked; c,d are the tail. The tail must keep its original RRF
        // score (here 1/rank), NOT be zeroed — zeroing would discard the tail's relevance signal.
        List<RecallHit> rrf = pages("a", "b", "c", "d"); // scores 1/1, 1/2, 1/3, 1/4
        TestDoubleProvider llm = scripted(
                "{\"rankings\":[{\"id\":\"a\",\"relevance\":0.3},{\"id\":\"b\",\"relevance\":0.9}]}");
        CandidateReranker reranker = new CandidateReranker(llm, PROMPTS, 2);

        List<RecallHit> out = reranker.rerank("query", rrf);

        assertThat(out).extracting(RecallHit::id).containsExactly("b", "a", "c", "d");
        // Tail c, d retain their original RRF scores (1/3, 1/4), not 0.0.
        RecallHit c = out.get(2);
        RecallHit d = out.get(3);
        assertThat(c.id()).isEqualTo("c");
        assertThat(c.score()).isEqualTo(1.0 / 3);
        assertThat(d.id()).isEqualTo("d");
        assertThat(d.score()).isEqualTo(1.0 / 4);
    }

    @Test
    void malformedJsonKeepsRrfOrder() {
        List<RecallHit> rrf = pages("a", "b", "c");
        CandidateReranker reranker = new CandidateReranker(scripted("not json at all"), PROMPTS, 20);

        List<RecallHit> out = reranker.rerank("query", rrf);

        assertThat(out).isSameAs(rrf); // returned unchanged on parse failure
    }

    @Test
    void emptyRankingsKeepRrfOrder() {
        List<RecallHit> rrf = pages("a", "b", "c");
        CandidateReranker reranker = new CandidateReranker(scripted("{\"rankings\":[]}"), PROMPTS, 20);

        assertThat(reranker.rerank("query", rrf)).isSameAs(rrf);
    }

    @Test
    void llmFailureKeepsRrfOrder() {
        List<RecallHit> rrf = pages("a", "b", "c");
        // failProbe also makes chat throw via the responder; instead, throw directly from the responder.
        TestDoubleProvider llm = scripted(req -> {
            throw new com.agentmemory.llm.LlmException("boom");
        });
        CandidateReranker reranker = new CandidateReranker(llm, PROMPTS, 20);

        assertThat(reranker.rerank("query", rrf)).isSameAs(rrf);
    }

    @Test
    void singletonOrEmptyInputIsReturnedWithoutAnLlmCall() {
        TestDoubleProvider llm = scripted("{\"rankings\":[]}");
        CandidateReranker reranker = new CandidateReranker(llm, PROMPTS, 20);

        List<RecallHit> one = pages("a");
        assertThat(reranker.rerank("q", one)).isSameAs(one);
        assertThat(reranker.rerank("q", List.of())).isEmpty();
        assertThat(llm.chatCalls()).isEmpty(); // never bothered the LLM
    }

    @Test
    void relevanceIsClampedIntoUnitRange() {
        List<RecallHit> rrf = pages("a", "b");
        // Out-of-range / NaN-ish values must be clamped, not propagated.
        TestDoubleProvider llm = scripted(
                "{\"rankings\":[{\"id\":\"a\",\"relevance\":4.0},{\"id\":\"b\",\"relevance\":-2.0}]}");
        CandidateReranker reranker = new CandidateReranker(llm, PROMPTS, 20);

        List<RecallHit> out = reranker.rerank("query", rrf);

        assertThat(out).extracting(RecallHit::id).containsExactly("a", "b");
        assertThat(out.get(0).score()).isEqualTo(1.0); // clamped from 4.0
        assertThat(out.get(1).score()).isEqualTo(0.0); // clamped from -2.0
    }
}
