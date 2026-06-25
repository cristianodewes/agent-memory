package com.agentmemory.recall;

/**
 * An internal, ranked candidate row from one recall arm before fusion: a stable {@code key} (the
 * page-version or observation id used to dedupe/fuse across arms) and the displayable
 * {@link RecallHit} payload the arm assembled (path/title/snippet/source). The arm's own ordering is
 * carried positionally in the {@link RankedList}; the numeric {@code rank}/{@code score} on the hit
 * here are placeholders the {@link Fusion} overwrites with the final fused values.
 *
 * @param key the stable fusion key (canonical UUID text).
 * @param hit the displayable hit payload.
 */
record Candidate(String key, RecallHit hit) {}
