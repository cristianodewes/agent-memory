/**
 * Recovery and onboarding capabilities the markdown+git memory model makes natural (issue #34;
 * Survey §2.10/§2.11, DD-002):
 *
 * <ul>
 *   <li><b>Time-travel</b> ({@link com.agentmemory.timetravel.TimeTravelService}) — list recent wiki
 *       commits as checkpoints and restore a single page's markdown from any revision, then reindex
 *       it (#14).</li>
 *   <li><b>Backup/restore</b> ({@link com.agentmemory.timetravel.BackupService}) — an online,
 *       {@code pg_dump}-free backup of the DB-only primary state to a {@code .tar.gz} while the source
 *       stays writable, and a live-process-guarded (invariant #9) restore.</li>
 *   <li><b>Bootstrap</b> ({@link com.agentmemory.timetravel.BootstrapService}) — compile a project's
 *       pre-existing history (git log, README, docs, module headers, rules) into seed wiki pages via
 *       one structured LLM pass (DD-005, invariant #7).</li>
 * </ul>
 *
 * <p>The derived {@code pages}/{@code links}/{@code page_embeddings} are excluded from backup: they
 * are rebuildable from the git-backed wiki by reindex (DD-002). The wiki itself is already
 * time-travelable via its git history, so backup covers only what git does not.
 */
package com.agentmemory.timetravel;
