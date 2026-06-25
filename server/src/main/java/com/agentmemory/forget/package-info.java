/**
 * The forget sweep (issue #25, ARCHITECTURE §5.1): the eviction pass that keeps the store healthy.
 * Cold pages (low retention per the shared {@code store.RetentionScorer} #24) are soft-deleted —
 * dropped from "latest" and marked {@code deleted_at}, with the markdown + git history retained for
 * recovery — and soft-deletes that stay cold past {@code hard_delete_after_days} without access are
 * purged (row + wiki file removed). Semantic-layer, slot ({@code _slots/}), and recently-accessed
 * pages are exempt. Exposed as the {@code memory_forget_sweep} MCP tool with a {@code dry_run}
 * preview; every applied sweep is audited and never bypasses the single writer (invariant #2).
 */
package com.agentmemory.forget;
