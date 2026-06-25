package com.agentmemory.reindex;

/**
 * Rebuilds the Postgres index from the markdown wiki (issue #14; ARCHITECTURE §2.3, DD-002). Because
 * {@code wiki/} is the source of truth and Postgres is a <em>derived</em> index, this operation makes
 * the database disposable: drop it (or let it drift) and {@code reindex} reconstructs {@code pages},
 * the generated FTS column, and the {@code links} graph — and, only when explicitly asked, the
 * {@code page_embeddings}.
 *
 * <p>Capture tables ({@code sessions}, {@code observations}, {@code audit_log}) are <strong>never</strong>
 * rebuilt here: they are primary, not derived (DD-002), and belong to backup/restore (#34).
 *
 * <p>Runs are <strong>idempotent and resumable</strong>: a {@link ReindexMode#FULL} run wipes and
 * rebuilds, so re-running converges to the same state; an {@link ReindexMode#INCREMENTAL} run skips
 * files whose content already matches the current latest version, so replaying it is a no-op.
 */
public interface ReindexService {

    /**
     * Rebuild the index per {@code options}.
     *
     * @param options mode (full/incremental), the incremental git ref, and the re-embed flag; never
     *                null.
     * @return a report of what was rebuilt and what was skipped.
     */
    ReindexReport reindex(ReindexOptions options);
}
