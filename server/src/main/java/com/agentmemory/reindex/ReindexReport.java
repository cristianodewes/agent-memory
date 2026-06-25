package com.agentmemory.reindex;

import java.util.List;

/**
 * The outcome of a reindex run (issue #14): what was rebuilt and what could not be. Returned to the
 * caller (the {@code POST /reindex} endpoint and the CLI) so an operator sees exactly how much of the
 * wiki was indexed and which files were skipped.
 *
 * @param mode          the mode that ran.
 * @param filesScanned  page files considered (after excluding non-page files such as {@code log.md}).
 * @param pagesIndexed  page versions written (created or updated) this run.
 * @param pagesDeleted  pages retired because their file was removed (incremental only).
 * @param linksWritten  link rows inserted from the indexed pages' bodies.
 * @param linksResolved deferred links newly resolved to a now-existing target.
 * @param skipped       files skipped, each with a short reason (malformed frontmatter, unparseable,
 *                      non-page), so a bad page surfaces instead of silently corrupting the index.
 */
public record ReindexReport(
        ReindexMode mode,
        int filesScanned,
        int pagesIndexed,
        int pagesDeleted,
        int linksWritten,
        int linksResolved,
        List<SkippedFile> skipped) {

    public ReindexReport {
        skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }

    /** @return {@code true} if any scanned file was skipped (so the caller can warn). */
    public boolean hasSkips() {
        return !skipped.isEmpty();
    }

    /**
     * One skipped file and why.
     *
     * @param path   the path relative to {@code wiki/} (forward-slashed), or the file name.
     * @param reason a short human reason.
     */
    public record SkippedFile(String path, String reason) {
        public SkippedFile {
            if (path == null) {
                throw new IllegalArgumentException("skipped.path must not be null");
            }
            if (reason == null) {
                throw new IllegalArgumentException("skipped.reason must not be null");
            }
        }
    }
}
