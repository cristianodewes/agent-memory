package com.agentmemory.llmrecall;

import com.agentmemory.recall.RecallHit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-orders the re-ranked recall candidates by <strong>Maximal Marginal Relevance</strong> (issue
 * #141, Fase 4) — the post-rerank, pre-trim step that maximizes the marginal information of the final
 * page so it is not several bullets of the same topic. The graph arm tends to pull one-hop neighbours
 * of the FTS seeds (which share a subject with the seed), and nothing in the rerank+trim guarantees
 * diversity in the final cut of {@code limit} hits; MMR fixes exactly that.
 *
 * <h2>Algorithm</h2>
 * Greedy selection over the candidate pool. Starting from the empty selection, each step picks the
 * still-unselected candidate {@code c} maximizing
 * <pre>{@code   mmr(c) = λ·rel(c) − (1−λ)·max_{s∈selected} cos(c, s)}</pre>
 * where {@code rel(c)} is the candidate's {@link RecallHit#score() score} (the calibrated cross-encoder
 * relevance when the rerank calibrated it, else the LLM/RRF score it carries) and {@code cos} is the
 * cosine similarity of the two candidates' embeddings. {@code λ} (in {@code [0, 1]}, ~0.7) trades
 * relevance against diversity: the first pick is always the most relevant (the redundancy term is
 * empty), and later picks are promoted when they add information the already-selected hits lack. Ties
 * are broken <em>stably by original rank</em> (the earlier candidate in the input wins), so the pass is
 * deterministic.
 *
 * <h2>Cost</h2>
 * Pure CPU — cosine over the ≤K candidate vectors ({@code K} ≤ ~20), microseconds — plus the single
 * bounded {@link Embeddings#forPageIds embeddings fetch}. It is therefore <em>not</em> bounded by the
 * LLM time budget; {@link LlmRecallService} runs it whether or not the LLM rerank fired.
 *
 * <h2>Never worse than baseline (DD-005)</h2>
 * MMR is a pure upside that only has an effect when embeddings exist. When the embeddings axis is off,
 * the fetch errors, or no candidate has a stored vector, the diversity input is empty and the input
 * order (the rerank order) is returned <em>unchanged</em>. A candidate that has no embedding while
 * others do is treated as <strong>diversity-neutral</strong> — it neither receives a redundancy
 * penalty nor imposes one on others (its similarity to everything is taken as 0), so a partial
 * embedding index degrades smoothly rather than dropping or distorting the un-embedded hits. The pass
 * never throws and never rescores a hit — it only re-orders, so the {@code calibrated} flag and every
 * hit's score flow through untouched (the issue #133 absolute gate downstream still holds).
 *
 * <p>Stateless given its {@link Embeddings} source; safe to share.
 */
public final class MmrDiversifier {

    private static final Logger log = LoggerFactory.getLogger(MmrDiversifier.class);

    /**
     * The candidate-embedding source: the stored vector for each candidate page-version id. The
     * production binding reads {@code page_embeddings} via
     * {@link com.agentmemory.recall.PageEmbeddingService#embeddingsFor} (one bounded query under the
     * active provider/model); tests pin vectors directly. Candidates without a stored vector are simply
     * omitted from the returned map.
     */
    @FunctionalInterface
    public interface Embeddings {
        /** @return page-version id → embedding vector, for the ids that have one (others omitted). */
        Map<String, float[]> forPageIds(Collection<String> pageIds);
    }

    private final Embeddings embeddings;
    private final double lambda;

    /**
     * @param embeddings the candidate-embedding source; never null.
     * @param lambda     the relevance/diversity trade-off, in {@code [0, 1]} (higher favours relevance).
     */
    public MmrDiversifier(Embeddings embeddings, double lambda) {
        if (embeddings == null) {
            throw new IllegalArgumentException("embeddings source must not be null");
        }
        if (lambda < 0.0 || lambda > 1.0) {
            throw new IllegalArgumentException("mmr lambda must be in [0,1], was " + lambda);
        }
        this.embeddings = embeddings;
        this.lambda = lambda;
    }

    /**
     * Re-order {@code hits} (the re-ranked candidates, best-first) by MMR. Returns the input unchanged
     * when there is nothing to re-order (≤ 1 hit), when no candidate has an embedding (degrade to the
     * rerank order), or on any error — it never throws.
     *
     * @param hits the re-ranked candidate hits; may be null.
     * @return the MMR-ordered hits, or {@code hits} unchanged when diversity is unavailable.
     */
    public List<RecallHit> diversify(List<RecallHit> hits) {
        if (hits == null || hits.size() <= 1) {
            return hits;
        }
        try {
            List<String> ids = new ArrayList<>(hits.size());
            for (RecallHit h : hits) {
                ids.add(h.id());
            }
            Map<String, float[]> vectors = embeddings.forPageIds(ids);
            if (vectors == null || vectors.isEmpty()) {
                return hits; // DD-005: no embeddings → keep the rerank order (never worse than baseline)
            }
            return greedy(hits, vectors);
        } catch (RuntimeException e) {
            // Diversity is best-effort; a failure here must never fail recall — keep the rerank order.
            log.debug("MMR diversity pass failed ({}); keeping the rerank order", e.toString());
            return hits;
        }
    }

    /** The greedy MMR selection; {@code vectors} is non-empty (at least one candidate has an embedding). */
    private List<RecallHit> greedy(List<RecallHit> hits, Map<String, float[]> vectors) {
        int n = hits.size();
        // Precompute the L2 norm of each usable vector (the cosine denominator); a zero-norm vector is
        // unusable for cosine, so drop it here and treat that candidate as having no embedding.
        Map<String, Double> norms = new HashMap<>(vectors.size() * 2);
        for (Map.Entry<String, float[]> e : vectors.entrySet()) {
            double norm = l2Norm(e.getValue());
            if (norm > 0.0) {
                norms.put(e.getKey(), norm);
            }
        }

        boolean[] selected = new boolean[n];
        List<Integer> selectedIdx = new ArrayList<>(n);
        List<RecallHit> ordered = new ArrayList<>(n);
        for (int step = 0; step < n; step++) {
            int bestIdx = -1;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                if (selected[i]) {
                    continue;
                }
                double rel = hits.get(i).score();
                double redundancy = maxSimilarityToSelected(i, hits, selectedIdx, vectors, norms);
                double mmr = lambda * rel - (1.0 - lambda) * redundancy;
                // Strict greater-than keeps the earliest (lowest original rank) candidate on a tie, so
                // the selection is deterministic and stable against the rerank order.
                if (mmr > bestScore) {
                    bestScore = mmr;
                    bestIdx = i;
                }
            }
            selected[bestIdx] = true;
            selectedIdx.add(bestIdx);
            ordered.add(hits.get(bestIdx));
        }
        return ordered;
    }

    /**
     * The maximum cosine similarity of candidate {@code i} to any already-selected candidate — its
     * redundancy. A candidate with no usable embedding, or with no comparable selected candidate yet,
     * contributes redundancy {@code 0} (diversity-neutral). The raw maximum (which may be negative for
     * anti-correlated vectors) is used, so a candidate dissimilar to all selected hits is correctly
     * rewarded.
     */
    private static double maxSimilarityToSelected(
            int i,
            List<RecallHit> hits,
            List<Integer> selectedIdx,
            Map<String, float[]> vectors,
            Map<String, Double> norms) {
        String id = hits.get(i).id();
        float[] vi = vectors.get(id);
        Double ni = norms.get(id);
        if (vi == null || ni == null) {
            return 0.0; // candidate without a usable embedding → diversity-neutral (no penalty)
        }
        double max = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (int s : selectedIdx) {
            String sid = hits.get(s).id();
            float[] vs = vectors.get(sid);
            Double ns = norms.get(sid);
            if (vs == null || ns == null || vs.length != vi.length) {
                continue; // a selected hit without a usable/comparable vector imposes no redundancy
            }
            double cos = dot(vi, vs) / (ni * ns);
            if (!any || cos > max) {
                max = cos;
                any = true;
            }
        }
        return any ? max : 0.0;
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0.0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    private static double l2Norm(float[] v) {
        if (v == null) {
            return 0.0;
        }
        double sum = 0.0;
        for (float x : v) {
            sum += (double) x * x;
        }
        return Math.sqrt(sum);
    }
}
