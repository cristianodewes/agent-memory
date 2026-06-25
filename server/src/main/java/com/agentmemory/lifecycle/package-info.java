/**
 * Lifecycle operations on projects and the data dir (issue #33; ARCHITECTURE design goal #6,
 * invariant #9; Survey §2.7/§2.10).
 *
 * <p>Because identity is a typed 3-tuple {@code (workspace, project, path)} and projects are isolated
 * by construction, these ops are cheap and sibling-safe:
 * {@link com.agentmemory.lifecycle.ProjectLifecycleService} renames a project (a slug update across
 * the denormalized columns + a wiki dir move), moves it across workspaces, or purges it (delete the
 * wiki subtree + DB rows + cascade embeddings). Cross-project {@code links} are re-pointed and every op
 * is written to {@code audit_log} with its before/after identity.
 *
 * <p>{@link com.agentmemory.lifecycle.ResetService} is the destructive full wipe, guarded by the
 * live-process check (invariant #9): {@link com.agentmemory.lifecycle.ProcessLock} stamps the data dir
 * with the running server's PID at startup, and {@code reset} refuses while a live holder is detected
 * unless explicitly forced.
 */
package com.agentmemory.lifecycle;
