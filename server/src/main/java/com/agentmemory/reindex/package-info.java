/**
 * Reindex: rebuild the derived Postgres index from the markdown wiki source of truth
 * (issue #14; ARCHITECTURE §2.3, DD-002).
 *
 * <p>Because {@code wiki/} is authoritative and Postgres is derived, this package makes the database
 * disposable: {@link com.agentmemory.reindex.ReindexService} walks {@code wiki/}, parses each page
 * with the #13 {@code com.agentmemory.wiki.MarkdownDocument}, and rebuilds {@code pages} (with its
 * generated FTS column) plus the wikilink {@code links} graph. Link extraction + maintenance is
 * delegated to the issue #27 {@link com.agentmemory.links.WikiLinkService} (the single authority for
 * the {@code links} table and its canonical scoped grammar), so a rebuilt graph is identical to one a
 * normal page write produces. Reindex runs full (wipe + rebuild) or incremental (only git-changed
 * files), is idempotent and resumable, and re-embeds only when explicitly asked via the optional
 * {@link com.agentmemory.reindex.ReindexEmbeddingHook} (the embeddings axis is owned by #16/#6, never
 * implemented here).
 *
 * <p>Capture tables ({@code sessions}, {@code observations}, {@code audit_log}) are <em>primary</em>,
 * not derived (DD-002), and are never touched by reindex; they belong to backup/restore (#34).
 */
package com.agentmemory.reindex;
