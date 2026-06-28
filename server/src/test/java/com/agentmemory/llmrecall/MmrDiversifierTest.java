package com.agentmemory.llmrecall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.recall.HitSource;
import com.agentmemory.recall.RecallHit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Hermetic unit coverage for {@link MmrDiversifier} (issue #141): the greedy
 * {@code λ·rel − (1−λ)·max cos} selection, the diversity-neutral treatment of candidates without an
 * embedding, the stable tie-break by original rank, and the never-worse-than-baseline degradations
 * (no embeddings / source error → the input order, unchanged; never throws). No Spring, no DB — the
 * candidate-embedding source is a pinned in-memory map, so the ordering is known up front.
 */
class MmrDiversifierTest {

    private static final double LAMBDA = 0.7;

    /** A PAGE hit carrying {@code score} as its relevance; rank/snippet are irrelevant to MMR. */
    private static RecallHit page(String id, double score) {
        return new RecallHit(HitSource.PAGE, id, "p/" + id + ".md", "Title " + id, null, score, 0, "s");
    }

    /** An embeddings source that returns the pinned vectors for whichever candidate ids it is asked. */
    private static MmrDiversifier.Embeddings pinned(Map<String, float[]> vectors) {
        return ids -> {
            Map<String, float[]> out = new HashMap<>();
            for (String id : ids) {
                float[] v = vectors.get(id);
                if (v != null) {
                    out.put(id, v);
                }
            }
            return out;
        };
    }

    private static List<String> ids(List<RecallHit> hits) {
        return hits.stream().map(RecallHit::id).toList();
    }

    @Test
    void diverseCandidateRisesAboveTheSecondNearDuplicate() {
        // Rerank order A, B, C (by relevance). A and B are near-duplicates (identical embedding); C is
        // diverse (orthogonal). MMR must promote the diverse C above the redundant B — the acceptance
        // criterion for #141.
        List<RecallHit> hits = List.of(page("A", 0.9), page("B", 0.8), page("C", 0.7));
        Map<String, float[]> vecs = new HashMap<>();
        vecs.put("A", new float[] {1f, 0f});
        vecs.put("B", new float[] {1f, 0f}); // duplicate of A
        vecs.put("C", new float[] {0f, 1f}); // diverse

        List<RecallHit> out = new MmrDiversifier(pinned(vecs), LAMBDA).diversify(hits);

        assertThat(ids(out)).containsExactly("A", "C", "B");
        // MMR only re-orders: every hit's score is carried through unchanged (the #133 gate reads it).
        assertThat(out).extracting(RecallHit::score).containsExactly(0.9, 0.7, 0.8);
    }

    @Test
    void candidateWithoutAnEmbeddingIsDiversityNeutralAndStillRises() {
        // A and B are near-duplicates; C has NO embedding. A diversity-neutral C (no redundancy penalty)
        // out-scores the redundant B and rises, while imposing no penalty on anything itself.
        List<RecallHit> hits = List.of(page("A", 0.9), page("B", 0.8), page("C", 0.7));
        Map<String, float[]> vecs = new HashMap<>();
        vecs.put("A", new float[] {1f, 0f});
        vecs.put("B", new float[] {1f, 0f});
        // C intentionally absent from the map.

        List<RecallHit> out = new MmrDiversifier(pinned(vecs), LAMBDA).diversify(hits);

        assertThat(ids(out)).containsExactly("A", "C", "B");
    }

    @Test
    void noEmbeddingsKeepsTheRerankOrderUnchanged() {
        // Empty embeddings (axis off / nothing indexed) → degrade to the rerank order (never worse).
        List<RecallHit> hits = List.of(page("A", 0.9), page("B", 0.8), page("C", 0.7));

        List<RecallHit> out = new MmrDiversifier(pinned(Map.of()), LAMBDA).diversify(hits);

        assertThat(out).isSameAs(hits); // returned unchanged, not re-copied
    }

    @Test
    void embeddingSourceErrorDegradesToTheRerankOrderAndNeverThrows() {
        List<RecallHit> hits = List.of(page("A", 0.9), page("B", 0.8), page("C", 0.7));
        MmrDiversifier.Embeddings failing = idsArg -> {
            throw new IllegalStateException("embeddings store down");
        };

        List<RecallHit> out = new MmrDiversifier(failing, LAMBDA).diversify(hits);

        assertThat(out).isSameAs(hits);
    }

    @Test
    void stableTieBreakKeepsTheOriginalOrderOnEqualScoresAndVectors() {
        // All equal relevance and identical embeddings → every step is a tie; the lowest original rank
        // wins, so the input order is preserved (deterministic).
        List<RecallHit> hits = List.of(page("A", 0.5), page("B", 0.5), page("C", 0.5));
        Map<String, float[]> vecs = new HashMap<>();
        vecs.put("A", new float[] {1f, 0f});
        vecs.put("B", new float[] {1f, 0f});
        vecs.put("C", new float[] {1f, 0f});

        List<RecallHit> out = new MmrDiversifier(pinned(vecs), LAMBDA).diversify(hits);

        assertThat(ids(out)).containsExactly("A", "B", "C");
    }

    @Test
    void lambdaOneIsPureRelevanceAndIgnoresDiversity() {
        // λ=1 drops the redundancy term entirely → MMR collapses to the relevance (rerank) order even
        // when candidates are near-duplicates.
        List<RecallHit> hits = List.of(page("A", 0.9), page("B", 0.8), page("C", 0.7));
        Map<String, float[]> vecs = new HashMap<>();
        vecs.put("A", new float[] {1f, 0f});
        vecs.put("B", new float[] {1f, 0f});
        vecs.put("C", new float[] {0f, 1f});

        List<RecallHit> out = new MmrDiversifier(pinned(vecs), 1.0).diversify(hits);

        assertThat(ids(out)).containsExactly("A", "B", "C");
    }

    @Test
    void singleHitAndEmptyAndNullAreReturnedUnchanged() {
        MmrDiversifier mmr = new MmrDiversifier(pinned(Map.of()), LAMBDA);
        List<RecallHit> one = List.of(page("A", 0.9));
        assertThat(mmr.diversify(one)).isSameAs(one);
        assertThat(mmr.diversify(List.of())).isEmpty();
        assertThat(mmr.diversify(null)).isNull();
    }

    @Test
    void constructorRejectsAnOutOfRangeLambda() {
        assertThatThrownBy(() -> new MmrDiversifier(pinned(Map.of()), 1.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lambda");
        assertThatThrownBy(() -> new MmrDiversifier(null, 0.7))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
