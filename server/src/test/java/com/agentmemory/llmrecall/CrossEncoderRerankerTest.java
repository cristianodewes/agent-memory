package com.agentmemory.llmrecall;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.llm.CrossEncoderClient;
import com.agentmemory.llm.LlmException;
import com.agentmemory.llm.TestDoubleProvider;
import com.agentmemory.recall.HitSource;
import com.agentmemory.recall.RecallHit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link CrossEncoderReranker} with a stubbed {@link CrossEncoderClient} and a scripted
 * offline LLM reranker (no Spring, no DB, no network): the cross-encoder re-orders by calibrated score
 * and flags the result calibrated, it scores the whole over-fetched pool up to {@code maxDocuments}
 * (K right-sizing) while keeping any tail in RRF order, the budget timeout reaches the client, and every
 * failure mode degrades — cross-encoder error → LLM reranker → raw RRF — preserving never-worse-than-baseline.
 */
class CrossEncoderRerankerTest {

    private static final RecallPrompts PROMPTS = new RecallPrompts();

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

    /** A scripted LLM reranker whose reply is the same regardless of the request. */
    private static CandidateReranker llm(TestDoubleProvider provider) {
        return new CandidateReranker(provider, PROMPTS, 20);
    }

    /** An LLM reranker that is never expected to run (cross-encoder succeeds or input is trivial). */
    private static CandidateReranker llmNoop() {
        return new CandidateReranker(TestDoubleProvider.create(), PROMPTS, 20);
    }

    /** A cross-encoder client that records its call and returns the scores produced by {@code scorer}. */
    private static final class StubClient implements CrossEncoderClient {
        private final Function<List<String>, double[]> scorer;
        List<String> lastDocuments;
        Duration lastTimeout;
        int calls;

        StubClient(Function<List<String>, double[]> scorer) {
            this.scorer = scorer;
        }

        @Override
        public double[] scoreDocuments(String query, List<String> documents, Duration requestTimeout) {
            calls++;
            lastDocuments = documents;
            lastTimeout = requestTimeout;
            return scorer.apply(documents);
        }
    }

    @Test
    void crossEncoderReordersAndMarksCalibrated() {
        // Head order is a,b,c; calibrated scores a=0.5, b=0.1, c=0.9 -> descending order c, a, b.
        List<RecallHit> rrf = pages("a", "b", "c");
        StubClient client = new StubClient(docs -> new double[] {0.5, 0.1, 0.9});
        // The LLM fallback would order c,b,a if it ever ran; proving it does not.
        TestDoubleProvider provider = TestDoubleProvider.builder()
                .chatResponder(req -> "{\"rankings\":[{\"id\":\"a\",\"relevance\":0.1},"
                        + "{\"id\":\"b\",\"relevance\":0.5},{\"id\":\"c\",\"relevance\":0.9}]}")
                .build();
        CrossEncoderReranker reranker = new CrossEncoderReranker(client, llm(provider), 50);

        Reranker.Result out = reranker.rerank("q", rrf, null);

        assertThat(out.calibrated()).isTrue();
        assertThat(out.hits()).extracting(RecallHit::id).containsExactly("c", "a", "b");
        assertThat(out.hits().get(0).score()).isEqualTo(0.9);
        assertThat(out.hits()).extracting(RecallHit::rank).containsExactly(1, 2, 3);
        assertThat(client.calls).isEqualTo(1);
        assertThat(provider.chatCalls()).as("LLM fallback unused when cross-encoder succeeds").isEmpty();
    }

    @Test
    void scoresTheWholePoolUpToMaxDocuments() {
        // Pool of 6, maxDocuments 50: the cross-encoder scores ALL 6 (cheap reranker scores the pool,
        // unlike the LLM cap K) — the K right-sizing that lets memory_query(limit=20) rerank enough.
        List<RecallHit> rrf = pages("a", "b", "c", "d", "e", "f");
        StubClient client = new StubClient(docs -> {
            double[] s = new double[docs.size()];
            for (int i = 0; i < s.length; i++) {
                s[i] = (s.length - i) / (double) s.length;
            }
            return s;
        });
        CrossEncoderReranker reranker = new CrossEncoderReranker(client, llmNoop(), 50);

        Reranker.Result out = reranker.rerank("q", rrf, null);

        assertThat(client.lastDocuments).hasSize(6); // entire pool scored
        assertThat(out.hits()).hasSize(6);
        assertThat(out.calibrated()).isTrue();
    }

    @Test
    void capsHeadAtMaxDocumentsAndKeepsTailInRrfOrder() {
        // 4 candidates, maxDocuments=2: a,b are scored (b above a); c,d are the tail kept in RRF order,
        // retaining their original RRF score (1/3, 1/4), only their rank re-stamped.
        List<RecallHit> rrf = pages("a", "b", "c", "d"); // RRF scores 1/1, 1/2, 1/3, 1/4
        StubClient client = new StubClient(docs -> new double[] {0.3, 0.9});
        CrossEncoderReranker reranker = new CrossEncoderReranker(client, llmNoop(), 2);

        Reranker.Result out = reranker.rerank("q", rrf, null);

        assertThat(client.lastDocuments).hasSize(2); // only the head was sent to the cross-encoder
        assertThat(out.hits()).extracting(RecallHit::id).containsExactly("b", "a", "c", "d");
        assertThat(out.calibrated()).isTrue();
        assertThat(out.hits().get(2).score()).isEqualTo(1.0 / 3); // tail keeps RRF score
        assertThat(out.hits().get(3).score()).isEqualTo(1.0 / 4);
        assertThat(out.hits()).extracting(RecallHit::rank).containsExactly(1, 2, 3, 4);
    }

    @Test
    void absentCrossEncoderDelegatesToLlmUncalibrated() {
        // No cross-encoder client (Voyage not configured): the seam IS the LLM reranker; uncalibrated.
        List<RecallHit> rrf = pages("a", "b", "c");
        TestDoubleProvider provider = TestDoubleProvider.builder()
                .chatResponder(req -> "{\"rankings\":[{\"id\":\"a\",\"relevance\":0.1},"
                        + "{\"id\":\"b\",\"relevance\":0.5},{\"id\":\"c\",\"relevance\":0.9}]}")
                .build();
        CrossEncoderReranker reranker = new CrossEncoderReranker(null, llm(provider), 50);

        Reranker.Result out = reranker.rerank("q", rrf, null);

        assertThat(out.hits()).extracting(RecallHit::id).containsExactly("c", "b", "a");
        assertThat(out.calibrated()).isFalse();
        assertThat(provider.chatCalls()).hasSize(1);
    }

    @Test
    void crossEncoderErrorDegradesToLlm() {
        List<RecallHit> rrf = pages("a", "b", "c");
        StubClient failing = new StubClient(docs -> {
            throw new LlmException("voyage down");
        });
        TestDoubleProvider provider = TestDoubleProvider.builder()
                .chatResponder(req -> "{\"rankings\":[{\"id\":\"a\",\"relevance\":0.1},"
                        + "{\"id\":\"b\",\"relevance\":0.5},{\"id\":\"c\",\"relevance\":0.9}]}")
                .build();
        CrossEncoderReranker reranker = new CrossEncoderReranker(failing, llm(provider), 50);

        Reranker.Result out = reranker.rerank("q", rrf, null);

        assertThat(out.hits()).extracting(RecallHit::id).containsExactly("c", "b", "a");
        assertThat(out.calibrated()).isFalse();
        assertThat(failing.calls).isEqualTo(1);
        assertThat(provider.chatCalls()).hasSize(1);
    }

    @Test
    void crossEncoderAndLlmFailureKeepRrfOrder() {
        // Worst case: cross-encoder errors AND the LLM reranker errors -> the unchanged RRF order, never
        // worse than the hybrid baseline.
        List<RecallHit> rrf = pages("a", "b", "c");
        StubClient failing = new StubClient(docs -> {
            throw new LlmException("voyage down");
        });
        TestDoubleProvider provider = TestDoubleProvider.builder()
                .chatResponder(req -> {
                    throw new LlmException("llm down");
                })
                .build();
        CrossEncoderReranker reranker = new CrossEncoderReranker(failing, llm(provider), 50);

        Reranker.Result out = reranker.rerank("q", rrf, null);

        assertThat(out.hits()).isSameAs(rrf); // unchanged RRF order
        assertThat(out.calibrated()).isFalse();
    }

    @Test
    void mismatchedScoreCountDegradesToLlm() {
        // A misbehaving client returns the wrong number of scores: degrade rather than mis-rank.
        List<RecallHit> rrf = pages("a", "b", "c");
        StubClient bad = new StubClient(docs -> new double[] {0.9}); // 1 score for 3 docs
        TestDoubleProvider provider = TestDoubleProvider.builder()
                .chatResponder(req -> "{\"rankings\":[{\"id\":\"a\",\"relevance\":0.1},"
                        + "{\"id\":\"b\",\"relevance\":0.5},{\"id\":\"c\",\"relevance\":0.9}]}")
                .build();
        CrossEncoderReranker reranker = new CrossEncoderReranker(bad, llm(provider), 50);

        Reranker.Result out = reranker.rerank("q", rrf, null);

        assertThat(out.calibrated()).isFalse();
        assertThat(out.hits()).extracting(RecallHit::id).containsExactly("c", "b", "a"); // LLM fallback
    }

    @Test
    void passesPerCallTimeoutToTheClient() {
        List<RecallHit> rrf = pages("a", "b");
        StubClient client = new StubClient(docs -> new double[] {0.9, 0.1});
        CrossEncoderReranker reranker = new CrossEncoderReranker(client, llmNoop(), 50);

        reranker.rerank("q", rrf, Duration.ofMillis(1500));

        assertThat(client.lastTimeout).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    void singletonAndEmptyInputReturnedWithoutClientCall() {
        StubClient client = new StubClient(docs -> new double[docs.size()]);
        CrossEncoderReranker reranker = new CrossEncoderReranker(client, llmNoop(), 50);

        List<RecallHit> one = pages("a");
        Reranker.Result outOne = reranker.rerank("q", one, null);
        assertThat(outOne.hits()).isSameAs(one);
        assertThat(outOne.calibrated()).isFalse();

        Reranker.Result outEmpty = reranker.rerank("q", List.of(), null);
        assertThat(outEmpty.hits()).isEmpty();
        assertThat(outEmpty.calibrated()).isFalse();

        assertThat(client.calls).as("trivial inputs never reach the client").isZero();
    }
}
