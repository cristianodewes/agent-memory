package com.agentmemory.reindex;

import com.agentmemory.recall.PageEmbeddingService;
import com.agentmemory.store.PageRecord;

/**
 * Wires reindex's optional re-embed step (issue #14) to the #16 embed seam,
 * {@link PageEmbeddingService#embedPage}. When an embedder is configured this is what makes
 * {@code reindex --reembed} actually (re)generate {@code page_embeddings}; embeddings are keyed to the
 * page <em>version</em> id, so backfilling re-embeds the current latest version
 * ({@link PageRecord#id()}). {@link PageEmbeddingService} owns all embedding policy (provider,
 * dimension contract, graceful degradation, in-place upsert), so this adapter stays a thin bridge and
 * reindex never depends on embedding internals.
 *
 * <p>Registered only when a {@link PageEmbeddingService} bean exists (i.e. #16 is on the classpath and
 * wired); otherwise reindex falls back to {@link ReindexEmbeddingHook.Noop} and a re-embed request is
 * a logged no-op (the embeddings axis is optional, DD-005).
 */
public final class PageEmbeddingReindexHook implements ReindexEmbeddingHook {

    private final PageEmbeddingService embeddings;

    public PageEmbeddingReindexHook(PageEmbeddingService embeddings) {
        if (embeddings == null) {
            throw new IllegalArgumentException("embeddings service must not be null");
        }
        this.embeddings = embeddings;
    }

    @Override
    public void embed(PageRecord page) {
        // embedPage is best-effort and never throws into the caller (DD-005): a provider failure or a
        // dimension mismatch degrades to "no vector for this page", not a failed reindex.
        embeddings.embedPage(page.id(), page.page().title(), page.page().body());
    }

    @Override
    public boolean isActive() {
        // Only "active" when embeddings can actually be produced (embedder present + width matches the
        // page_embeddings column contract), so reindex logs the accurate skip-vs-run message.
        return embeddings.embeddingsEnabled();
    }
}
