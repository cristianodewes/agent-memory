package com.agentmemory.recall;

import com.agentmemory.core.PageId;
import com.agentmemory.llm.Embedder;
import com.agentmemory.llm.EmbeddingResult;
import com.agentmemory.llm.LlmException;
import com.agentmemory.store.PageEmbeddingStore;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates and stores page embeddings — the <em>write</em> side of the semantic-recall arm (issue
 * #16). Page content is embedded via the optional {@link Embedder} (#6) and persisted to
 * {@code page_embeddings} ({@link PageEmbeddingStore}) with its denormalized {@code {provider, model,
 * dim}} (invariant #8). It also exposes a bounded by-id <em>read</em> of stored vectors
 * ({@link #embeddingsFor}) for the recall MMR diversity pass (issue #141). Two callers drive the
 * write side:
 *
 * <ul>
 *   <li><strong>On write/consolidation</strong> — after a page version is stored, embed it so it is
 *       semantically searchable. (Wiring this onto the store write path is a one-line call; #16 keeps
 *       the policy here so the store stays embedding-agnostic.)</li>
 *   <li><strong>Backfill / reindex (#14)</strong> — {@link #embedPage} is the seam #14 calls to
 *       (re)generate the vector for an existing page version. #16 exposes the seam but does not
 *       implement the reindex walk.</li>
 * </ul>
 *
 * <h2>Graceful degradation (DD-005)</h2>
 * Embeddings are a separate, default-on-but-optional axis. This service <strong>never throws</strong>
 * into a write: if no embedder is configured, the embedder is unreachable, or the embedder's width
 * does not match the {@code page_embeddings} column contract
 * ({@value com.agentmemory.store.PageEmbeddingStore#EMBEDDING_DIM}), the page is simply stored without
 * a vector and recall falls back to FTS + graph. Each distinct degradation logs a <em>one-time</em>
 * warning (not once per page) so the signal is visible without flooding the log.
 *
 * <h2>Dimension mismatch (provider/model swap)</h2>
 * If the configured embedder produces a width other than the column contract, every vector is skipped
 * with a one-time warning rather than attempted and rejected by the column — the operator must either
 * point the embeddings model at a matching one or migrate the column (V7 doc). This is the "detected
 * and handled (re-embed or skip with warning)" the issue requires; re-embed of a same-width model is
 * handled transparently by {@link PageEmbeddingStore#upsert}'s conflict clause.
 */
public class PageEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(PageEmbeddingService.class);

    private final PageEmbeddingStore store;
    private final Embedder embedder; // nullable: embeddings axis is optional (DD-005)

    // One-time warning guards so a persistent degradation does not log per page.
    private final AtomicBoolean warnedNoEmbedder = new AtomicBoolean(false);
    private final AtomicBoolean warnedDimMismatch = new AtomicBoolean(false);
    private final AtomicBoolean warnedUnreachable = new AtomicBoolean(false);
    private final AtomicBoolean warnedFetchFailed = new AtomicBoolean(false);

    /**
     * @param store    the {@code page_embeddings} persistence; never null.
     * @param embedder the configured embedder, or {@code null} when the embeddings axis is off.
     */
    public PageEmbeddingService(PageEmbeddingStore store, Embedder embedder) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.store = store;
        this.embedder = embedder;
    }

    /** @return {@code true} if an embedder is configured <em>and</em> its width matches the contract. */
    public boolean embeddingsEnabled() {
        return embedder != null && embedder.dimensions() == PageEmbeddingStore.EMBEDDING_DIM;
    }

    /**
     * Embed the given page content and store the vector for {@code pageId}. Best-effort: returns
     * {@code true} when a vector was stored, {@code false} on any graceful skip (no embedder, width
     * mismatch, or provider failure). Never throws on a provider/transport error — the caller's write
     * must not be coupled to embeddings availability (DD-005).
     *
     * @param pageId the page-version id to attach the embedding to; never null.
     * @param title  the page title (folded into the embedded text); may be null/blank.
     * @param body   the page body (the bulk of the embedded text); may be null/blank.
     * @return {@code true} iff an embedding was generated and stored.
     */
    public boolean embedPage(PageId pageId, String title, String body) {
        if (pageId == null) {
            throw new IllegalArgumentException("pageId must not be null");
        }
        if (embedder == null) {
            warnOnce(warnedNoEmbedder,
                    "No embeddings provider configured; pages will not be semantically searchable "
                            + "(recall degrades to FTS + graph). Set agent-memory.embeddings.auth.provider "
                            + "to enable the vector arm.");
            return false;
        }
        if (embedder.dimensions() != PageEmbeddingStore.EMBEDDING_DIM) {
            warnOnce(warnedDimMismatch,
                    "Embedder '" + embedder.id() + "' model '" + embedder.model() + "' produces "
                            + embedder.dimensions() + "-dim vectors but the page_embeddings column is "
                            + PageEmbeddingStore.EMBEDDING_DIM + "-dim; skipping embeddings (vector recall "
                            + "disabled). Point agent-memory.embeddings.auth.model at a "
                            + PageEmbeddingStore.EMBEDDING_DIM + "-dim model or migrate the column width.");
            return false;
        }

        String text = embedText(title, body);
        if (text.isBlank()) {
            return false; // nothing to embed (empty page); not a degradation
        }

        try {
            EmbeddingResult result = embedder.embed(text);
            // Defence in depth: the embedder asserts its own width, but re-check against the column
            // contract before the write so a misbehaving provider cannot corrupt the column.
            if (result.dim() != PageEmbeddingStore.EMBEDDING_DIM) {
                warnOnce(warnedDimMismatch,
                        "Embedder '" + embedder.id() + "' returned a " + result.dim()
                                + "-dim vector (expected " + PageEmbeddingStore.EMBEDDING_DIM
                                + "); skipping this embedding.");
                return false;
            }
            store.upsert(pageId, result);
            return true;
        } catch (LlmException e) {
            // Provider unreachable / rejected: degrade, do not fail the page write.
            warnOnce(warnedUnreachable,
                    "Embedding provider '" + embedder.id() + "' failed (" + e.getMessage()
                            + "); pages written while it is unavailable will not be in the vector index. "
                            + "Recall continues with FTS + graph.");
            return false;
        } catch (RuntimeException e) {
            // Any other unexpected error is still non-fatal to the write (DD-005).
            warnOnce(warnedUnreachable,
                    "Unexpected error while embedding a page (" + e.getMessage()
                            + "); skipping the vector for this write.");
            return false;
        }
    }

    /**
     * The stored embedding vectors for a set of candidate page-version ids — the <em>read</em> side
     * used by the recall MMR diversity pass (issue #141) to compare candidates by their own vectors.
     * Reads under the active embedder's {@code {provider, model}} so only comparable vectors are
     * returned, in one bounded query (no embed/network call); the {@link PageEmbeddingStore} owns the
     * SQL.
     *
     * <p>Best-effort and never throws into the recall path (DD-005): returns an <strong>empty</strong>
     * map when the embeddings axis is off or width-mismatched ({@link #embeddingsEnabled()} is false),
     * or when the fetch errors — the caller (MMR) then keeps the rerank order, so diversity is a pure
     * upside that is simply absent when embeddings are. Candidates with no stored vector are omitted
     * from the map (the caller treats them as diversity-neutral).
     *
     * @param pageIds the candidate page-version ids; already bounded to the recall candidate pool.
     * @return page-version id → embedding vector for those candidates that have one; empty when
     *     embeddings are unavailable.
     */
    public Map<String, float[]> embeddingsFor(Collection<String> pageIds) {
        if (pageIds == null || pageIds.isEmpty() || !embeddingsEnabled()) {
            return Map.of();
        }
        try {
            return store.fetchByPageIds(List.copyOf(pageIds), embedder.id(), embedder.model());
        } catch (RuntimeException e) {
            // A diversity read must never escalate into recall; degrade to "no embeddings" (DD-005).
            warnOnce(warnedFetchFailed,
                    "Fetching candidate embeddings for recall diversity failed (" + e.getMessage()
                            + "); recall continues without the MMR diversity pass.");
            return Map.of();
        }
    }

    /**
     * The text fed to the embedder for a page: title then body, so a page whose body is empty still
     * embeds its title. Kept package-visible-free (private) — the join is an internal detail.
     */
    private static String embedText(String title, String body) {
        String t = title == null ? "" : title.strip();
        String b = body == null ? "" : body.strip();
        if (t.isEmpty()) {
            return b;
        }
        if (b.isEmpty()) {
            return t;
        }
        return t + "\n\n" + b;
    }

    private static void warnOnce(AtomicBoolean guard, String message) {
        if (guard.compareAndSet(false, true)) {
            log.warn(message);
        }
    }
}
