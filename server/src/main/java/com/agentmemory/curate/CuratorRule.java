package com.agentmemory.curate;

/**
 * The rule-based maintenance checks the curator runs (issue #29). All are zero-cost (pure SQL / the
 * already-computed link graph — no LLM), so a curator run is free and deterministic; the LLM
 * contradiction pass is layered on separately by {@code memory_lint}.
 */
public enum CuratorRule {

    /** A latest episodic page that has gone cold (not accessed for a long time) — a demotion/forget candidate. */
    COLD_EPISODIC,

    /** A {@code _slots/} page that has not been updated in a long time — a slot that may be out of date. */
    STALE_SLOT,

    /** Two or more latest pages in the project sharing a normalized title — likely duplicates to merge. */
    DUPLICATE_TITLE,

    /** A dangling wikilink to a different project (stale, unresolved) — a broken cross-project reference. */
    DANGLING_CROSS_PROJECT
}
