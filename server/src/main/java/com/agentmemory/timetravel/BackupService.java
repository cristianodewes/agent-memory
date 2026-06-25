package com.agentmemory.timetravel;

import java.nio.file.Path;

/**
 * Online backup/restore of the DB-only primary state (issue #34; Survey §2.11). The markdown wiki is
 * already git-backed (DD-002), so this covers only what git does not: the capture log and operational
 * state ({@code sessions}, {@code observations}, {@code handoffs}, {@code audit_log},
 * {@code pending_writes}), the identity rows, and the (expensive) {@code page_embeddings} — see
 * {@link BackupTables}.
 *
 * <ul>
 *   <li><b>{@link #backup(Path)}</b> reads those tables into a {@code .tar.gz} while the source stays
 *       fully writable (a consistent snapshot taken in one read transaction).</li>
 *   <li><b>{@link #restore(Path, boolean)}</b> reloads them, which is destructive (it truncates first)
 *       and therefore guarded by the live-process check (invariant #9): refused while a live
 *       agent-memory process holds the data dir unless {@code force} is set.</li>
 * </ul>
 */
public interface BackupService {

    /**
     * Write an online backup of the DB-only primary state to {@code target} (a {@code .tar.gz}).
     *
     * @param target the archive path to create (parent dirs are created).
     * @return the archive written and the per-table row counts.
     */
    BackupResult backup(Path target);

    /**
     * Restore the DB-only primary state from a backup archive, truncating the target tables first.
     * Destructive — performs the live-process check (invariant #9) and refuses if a live holder is
     * present unless {@code force}.
     *
     * @param source the {@code .tar.gz} produced by {@link #backup(Path)}.
     * @param force  bypass the live-process refusal.
     * @return the restore outcome (performed/refused + row counts).
     */
    RestoreResult restore(Path source, boolean force);
}
