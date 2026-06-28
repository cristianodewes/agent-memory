package com.agentmemory.recall;

import com.agentmemory.llm.Embedder;
import com.agentmemory.llm.EmbeddingResult;
import com.agentmemory.llm.LlmException;
import com.agentmemory.store.PageEmbeddingStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The vector (semantic) arm of recall — issue #16. Given a query, it embeds the text with the
 * optional {@link Embedder} (#6) and asks {@link PageEmbeddingStore} for the nearest latest pages by
 * cosine distance, returning a {@link RankedList} that {@link HybridRecallService} folds into the
 * same RRF fusion as the FTS and graph arms (#15). Because RRF consumes only ordinal rank, the
 * cosine distances need no normalization against {@code ts_rank} / graph in-degree.
 *
 * <h2>Graceful degradation (DD-005) — recall never fails here</h2>
 * The vector arm is optional. {@link #rank} returns an <em>empty</em> arm (so fusion proceeds with
 * FTS + graph unchanged) whenever embeddings are unavailable: no embedder configured, the embedder's
 * width does not match the {@code page_embeddings} contract, or the embed call throws. Each distinct
 * cause logs a <strong>one-time</strong> degraded-recall warning. This arm is only wired in when an
 * {@link Embedder} bean exists; even then it must tolerate runtime unavailability.
 *
 * <p>Stateless except for the one-time warning guards; safe to share.
 */
public class VectorArm {

    /** The fusion arm name (diagnostics / tests), alongside {@code "fts"} and {@code "graph"}. */
    public static final String ARM_NAME = "vector";

    private static final Logger log = LoggerFactory.getLogger(VectorArm.class);

    private final PageEmbeddingStore store;
    private final Embedder embedder; // nullable: optional axis (DD-005)

    private final AtomicBoolean warnedDisabled = new AtomicBoolean(false);
    private final AtomicBoolean warnedDimMismatch = new AtomicBoolean(false);
    private final AtomicBoolean warnedUnreachable = new AtomicBoolean(false);

    /**
     * @param store    the {@code page_embeddings} reader; never null.
     * @param embedder the configured embedder, or {@code null} when the embeddings axis is off.
     */
    public VectorArm(PageEmbeddingStore store, Embedder embedder) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.store = store;
        this.embedder = embedder;
    }

    /** @return {@code true} if an embedder is configured and its width matches the column contract. */
    public boolean enabled() {
        return embedder != null && embedder.dimensions() == PageEmbeddingStore.EMBEDDING_DIM;
    }

    /**
     * Produce the vector arm for a query: the ranked page-version ids (nearest first) plus the
     * displayable {@link Candidate} for each, so {@link HybridRecallService} can register hits the
     * other arms did not supply. Returns an {@link Result#empty() empty} result — never throws —
     * when the arm is unavailable, so the caller fuses FTS + graph exactly as before.
     *
     * @param workspace workspace slug.
     * @param project   project slug.
     * @param text      the query text to embed; never null/blank.
     * @param limit     max vector candidates to retrieve.
     * @return the vector arm's ranked list and its candidates (possibly empty).
     */
    public Result rank(String workspace, String project, String text, int limit) {
        if (!enabled()) {
            warnOnce(warnedDisabled,
                    "Vector recall is disabled (no matching embeddings provider); using FTS + graph only.");
            return Result.empty();
        }
        if (text == null || text.isBlank()) {
            return Result.empty();
        }

        // The ENTIRE arm — embedding the query AND the pgvector nearest-neighbour query — is guarded:
        // any failure (an unreachable embedder, but equally a pgvector-specific DB error such as a
        // missing extension/index or a statement timeout on the heavier vector query) degrades to an
        // empty arm so recall falls back to FTS + graph. The FTS/graph arms use core tsvector and run
        // before this, so they are not coupled to a pgvector failure — without this guard such a
        // failure would propagate out of recall and even skip the raw-observation fallback (DD-005:
        // "recall NEVER fails"; a one-time degraded-recall warning is logged instead).
        try {
            EmbeddingResult embedded = embedder.embed(text);
            if (embedded.dim() != PageEmbeddingStore.EMBEDDING_DIM) {
                warnOnce(warnedDimMismatch,
                        "Query embedder returned a " + embedded.dim() + "-dim vector (expected "
                                + PageEmbeddingStore.EMBEDDING_DIM + "); skipping the vector arm.");
                return Result.empty();
            }

            List<PageEmbeddingStore.VectorHit> hits = store.nearestLatest(
                    workspace, project, embedded.vector(), embedder.id(), embedder.model(), limit);

            List<String> keys = new ArrayList<>(hits.size());
            List<Candidate> candidates = new ArrayList<>(hits.size());
            int rank = 1;
            for (PageEmbeddingStore.VectorHit h : hits) {
                keys.add(h.pageId());
                candidates.add(new Candidate(h.pageId(), vectorHit(h, rank)));
                rank++;
            }
            return new Result(new RankedList(ARM_NAME, keys), candidates);
        } catch (LlmException e) {
            warnOnce(warnedUnreachable,
                    "Embedding the query failed (" + e.getMessage()
                            + "); recall degraded to FTS + graph for this request.");
            return Result.empty();
        } catch (RuntimeException e) {
            // Includes Spring DataAccessException from the pgvector query (extension/index broken,
            // timeout): the vector arm is best-effort, so degrade rather than fail the whole recall.
            warnOnce(warnedUnreachable,
                    "Vector arm failed (" + e.getMessage()
                            + "); recall degraded to FTS + graph for this request.");
            return Result.empty();
        }
    }

    /**
     * The displayable payload for a vector-only hit: path/title/snippet are not loaded by the vector
     * query (the FTS/graph arms supply richer ones and win the first-writer merge), so a vector-only
     * hit carries just its id and {@link HitSource#PAGE} source — still navigable by id. Its recency
     * metadata ({@code updatedAt}/{@code layer}) is likewise unloaded (left {@code null} via the
     * convenience constructor), so the recency prior leaves a vector-only hit unpenalized (issue #140);
     * a page the FTS/graph arms also found keeps their richer payload, recency included. The placeholder
     * score is the cosine similarity {@code 1 - distance} clamped to {@code [0, 1]} (the {@code <=>}
     * cosine distance is in {@code [0, 2]}, so an anti-correlated vector would otherwise yield a negative
     * "similarity"); the {@link Fusion} overwrites it with the fused score regardless.
     */
    private static RecallHit vectorHit(PageEmbeddingStore.VectorHit h, int rank) {
        double similarity = Math.max(0.0, Math.min(1.0, 1.0 - h.distance()));
        return new RecallHit(HitSource.PAGE, h.pageId(), null, null, null, similarity, rank, null);
    }

    private static void warnOnce(AtomicBoolean guard, String message) {
        if (guard.compareAndSet(false, true)) {
            log.warn(message);
        }
    }

    /**
     * The vector arm's contribution: its {@link RankedList} (the ordered keys for fusion) and the
     * {@link Candidate}s carrying each key's displayable hit, so the service can register any the
     * other arms did not already supply.
     *
     * @param ranked     the ranked keys, best-first (empty when the arm is unavailable).
     * @param candidates the displayable candidates aligned with {@code ranked}.
     */
    public record Result(RankedList ranked, List<Candidate> candidates) {

        public Result {
            if (ranked == null) {
                throw new IllegalArgumentException("ranked must not be null");
            }
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        /** @return an empty vector arm (no keys, no candidates) — the degraded/disabled case. */
        public static Result empty() {
            return new Result(new RankedList(ARM_NAME, List.of()), List.of());
        }

        /** @return {@code true} when the arm contributed no candidates. */
        public boolean isEmpty() {
            return candidates.isEmpty();
        }
    }
}
