package com.agentmemory.reindex;

import com.agentmemory.store.PageRecord;

/**
 * The (re)embedding seam reindex calls when {@link ReindexOptions#reEmbed()} is on (issue #14). It is
 * deliberately a thin hook, not an embedder: reindex owns walking the wiki and rebuilding
 * {@code pages}/{@code links}, but the embeddings axis is a separate, optional concern (DD-005) owned
 * by #16/#6 — reindex must not implement it.
 *
 * <p>The default wiring binds {@link Noop}, so a re-embed request degrades to a logged no-op when no
 * embedder is configured (recall simply stays on FTS + graph). When #16 lands, it can publish a real
 * {@code ReindexEmbeddingHook} bean (e.g. one driving {@code com.agentmemory.llm.Embedder} into
 * {@code page_embeddings}) and reindex picks it up without change.
 */
public interface ReindexEmbeddingHook {

    /**
     * (Re)compute and store the embedding for one just-indexed page version. Implementations should
     * be idempotent for a given {@code (page version, provider, model)} (the {@code page_embeddings}
     * unique constraint). Must not throw for an absent/optional embedder — degrade and return.
     *
     * @param page the page version that was indexed this run.
     */
    void embed(PageRecord page);

    /** @return whether this hook will actually produce embeddings (false for the no-op). */
    boolean isActive();

    /** The default no-op: re-embedding requested but no embedder configured. */
    final class Noop implements ReindexEmbeddingHook {
        @Override
        public void embed(PageRecord page) {
            // intentionally nothing — embeddings axis not configured
        }

        @Override
        public boolean isActive() {
            return false;
        }
    }
}
