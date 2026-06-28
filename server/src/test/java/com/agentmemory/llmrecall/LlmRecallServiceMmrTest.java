package com.agentmemory.llmrecall;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.llm.CrossEncoderClient;
import com.agentmemory.llm.TestDoubleProvider;
import com.agentmemory.recall.HitSource;
import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.RecallQuery;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.RecallService;
import com.agentmemory.recall.Scope;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Hermetic coverage of the MMR diversity step <em>inside</em> the wired {@link LlmRecallService}
 * pipeline (issue #141): the pass runs after the cross-encoder rerank and before the trim, it
 * re-orders the candidates for diversity, it preserves the {@code calibrated} flag (so the #133
 * absolute gate still applies), and it degrades to the rerank order when embeddings are absent or the
 * config switch is off. A pinned in-memory embeddings source and a scripted cross-encoder keep the
 * ordering known up front — no Spring, no DB.
 */
class LlmRecallServiceMmrTest {

    private static final Scope SCOPE = Scope.of("ws", "proj");
    private static final RecallPrompts PROMPTS = new RecallPrompts();

    private static RecallHit page(String id, int rank) {
        return new RecallHit(
                HitSource.PAGE, id, "p/" + id + ".md", "Title " + id, null, 1.0 / rank, rank, "snip " + id);
    }

    private static final class StubBase implements RecallService {
        private final RecallResult result;

        StubBase(RecallResult result) {
            this.result = result;
        }

        @Override
        public RecallResult search(RecallQuery query) {
            return result;
        }
    }

    /** Cross-encoder that scores the head A,B,C as 0.9,0.8,0.7 → calibrated order A,B,C (unchanged). */
    private static Reranker calibratedReranker(double a, double b, double c) {
        CrossEncoderClient cross = (q, docs, t) -> new double[] {a, b, c};
        // The LLM fallback is never reached (the cross-encoder succeeds); a no-op provider satisfies it.
        return new CrossEncoderReranker(cross, new CandidateReranker(TestDoubleProvider.create(), PROMPTS, 20), 50);
    }

    private static MmrDiversifier mmr(Map<String, float[]> vectors, double lambda) {
        MmrDiversifier.Embeddings src = idsArg -> {
            Map<String, float[]> out = new HashMap<>();
            for (String id : idsArg) {
                float[] v = vectors.get(id);
                if (v != null) {
                    out.put(id, v);
                }
            }
            return out;
        };
        return new MmrDiversifier(src, lambda);
    }

    private static LlmRecallProperties props(boolean mmrEnabled) {
        return new LlmRecallProperties(
                true, 20, 2, 2, 6000L, true,
                new LlmRecallProperties.CrossEncoder(true, "rerank-2-lite", 50),
                new LlmRecallProperties.Mmr(mmrEnabled, 0.7),
                new LlmRecallProperties.Expansion(false, 4),
                new LlmRecallProperties.Injection(5, 0.0, 0.35, 1200, null));
    }

    private static LlmRecallService service(Reranker reranker, MmrDiversifier mmr, LlmRecallProperties props) {
        StubBase base = new StubBase(RecallResult.ofPages(List.of(page("A", 1), page("B", 2), page("C", 3))));
        return new LlmRecallService(
                base, /*expander*/ null, reranker, mmr, /*reinforcer*/ (s, h) -> {},
                props, System::currentTimeMillis, new RecallMetrics(null));
    }

    @Test
    void mmrRunsAfterRerankReordersForDiversityAndPreservesCalibrated() {
        // Cross-encoder ranks A,B,C (0.9,0.8,0.7, calibrated). A and B are near-duplicates; C is diverse,
        // so MMR promotes C above the redundant B → A, C, B — while keeping the calibrated flag + scores.
        Map<String, float[]> vecs = new HashMap<>();
        vecs.put("A", new float[] {1f, 0f});
        vecs.put("B", new float[] {1f, 0f});
        vecs.put("C", new float[] {0f, 1f});

        RecallResult out = service(calibratedReranker(0.9, 0.8, 0.7), mmr(vecs, 0.7), props(true))
                .search(new RecallQuery("q", SCOPE, 10));

        assertThat(out.hits()).extracting(RecallHit::id).containsExactly("A", "C", "B");
        assertThat(out.calibrated()).as("MMR only re-orders; the cross-encoder's calibration is preserved")
                .isTrue();
        // Scores travel with their hits through the re-order; ranks are re-stamped 1..n by the trim.
        assertThat(out.hits()).extracting(RecallHit::score).containsExactly(0.9, 0.7, 0.8);
        assertThat(out.hits()).extracting(RecallHit::rank).containsExactly(1, 2, 3);
    }

    @Test
    void absentEmbeddingsKeepTheRerankOrderAndCalibration() {
        // Same calibrated rerank, but the embeddings source has nothing → MMR is a no-op, the rerank
        // order A,B,C stands, and calibrated is still true (never worse than the baseline).
        RecallResult out = service(calibratedReranker(0.9, 0.8, 0.7), mmr(Map.of(), 0.7), props(true))
                .search(new RecallQuery("q", SCOPE, 10));

        assertThat(out.hits()).extracting(RecallHit::id).containsExactly("A", "B", "C");
        assertThat(out.calibrated()).isTrue();
    }

    @Test
    void mmrDisabledByConfigKeepsTheRerankOrderEvenWithEmbeddings() {
        // Embeddings would diversify, but mmr.enabled=false gates the step off → pure rerank order.
        Map<String, float[]> vecs = new HashMap<>();
        vecs.put("A", new float[] {1f, 0f});
        vecs.put("B", new float[] {1f, 0f});
        vecs.put("C", new float[] {0f, 1f});

        RecallResult out = service(calibratedReranker(0.9, 0.8, 0.7), mmr(vecs, 0.7), props(false))
                .search(new RecallQuery("q", SCOPE, 10));

        assertThat(out.hits()).extracting(RecallHit::id).containsExactly("A", "B", "C");
    }
}
