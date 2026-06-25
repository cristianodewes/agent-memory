package com.agentmemory.recall;

/**
 * Which arm of recall produced a {@link RecallHit}. The distinction matters to callers and the UI:
 * a {@link #PAGE} hit is durable compiled knowledge, whereas a {@link #RAW_OBSERVATION} hit is an
 * uncompiled capture returned only as a <em>bounded fallback</em> when no compiled page matched —
 * lower-confidence and explicitly labeled as such (ARCHITECTURE §3.3; issue #15 acceptance).
 */
public enum HitSource {

    /** A compiled wiki page (the {@code pages} FTS + link-graph arms). */
    PAGE,

    /** A raw captured observation (the bounded {@code observations_fts} fallback). */
    RAW_OBSERVATION
}
