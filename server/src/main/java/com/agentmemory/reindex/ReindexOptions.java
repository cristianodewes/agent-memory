package com.agentmemory.reindex;

/**
 * Inputs to a reindex run (issue #14): which {@link ReindexMode}, the git ref an incremental run
 * diffs against, and whether to re-embed.
 *
 * @param mode      {@link ReindexMode#FULL} or {@link ReindexMode#INCREMENTAL}; never null.
 * @param sinceRef  for {@link ReindexMode#INCREMENTAL}, the git revision to diff the working tree
 *                  against (e.g. {@code "HEAD~1"}, a commit sha, or {@code null}/blank to mean "since
 *                  the previous commit", i.e. {@code HEAD~1}). Ignored for {@link ReindexMode#FULL}.
 * @param reEmbed   whether to (re)compute {@code page_embeddings} for the indexed pages. Off by
 *                  default because embedding is expensive (acceptance: re-embedding only runs when
 *                  explicitly requested). When on, it is still a no-op unless an embedder is
 *                  configured (the embeddings axis is optional, DD-005); reindex never implements the
 *                  embedder itself (#16/#6 own that).
 */
public record ReindexOptions(ReindexMode mode, String sinceRef, boolean reEmbed) {

    public ReindexOptions {
        if (mode == null) {
            throw new IllegalArgumentException("reindex mode must not be null");
        }
    }

    /** A full rebuild with no re-embedding — the common recovery default. */
    public static ReindexOptions full() {
        return new ReindexOptions(ReindexMode.FULL, null, false);
    }

    /** An incremental rebuild since {@code sinceRef} (null ⇒ {@code HEAD~1}) with no re-embedding. */
    public static ReindexOptions incremental(String sinceRef) {
        return new ReindexOptions(ReindexMode.INCREMENTAL, sinceRef, false);
    }

    /** This run's options with {@code reEmbed} forced on. */
    public ReindexOptions withReEmbed(boolean value) {
        return new ReindexOptions(mode, sinceRef, value);
    }
}
