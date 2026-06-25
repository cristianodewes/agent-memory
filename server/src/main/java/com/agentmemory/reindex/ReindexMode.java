package com.agentmemory.reindex;

/**
 * How much of the index a reindex run rebuilds (issue #14).
 *
 * <ul>
 *   <li>{@link #FULL} — wipe the derived index ({@code pages}, {@code links}, and their cascaded
 *       {@code page_embeddings}) and rebuild it from every page in {@code wiki/}. This is the literal
 *       safety net DD-002 describes: drop the index, run reindex, get an equivalent index back.
 *       Capture tables ({@code sessions}, {@code observations}, {@code audit_log}) are never touched.</li>
 *   <li>{@link #INCREMENTAL} — rebuild only the pages whose files changed since a git ref, discovered
 *       via {@code wiki/}'s git history (the #13 {@code WikiGit}). Added/modified files become new
 *       page versions; deleted files retire their page. Cheaper for the common "a few pages changed"
 *       case; produces the same end state as {@link #FULL} for the changed set (parity is tested).</li>
 * </ul>
 */
public enum ReindexMode {
    FULL,
    INCREMENTAL
}
