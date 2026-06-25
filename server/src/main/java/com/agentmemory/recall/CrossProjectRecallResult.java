package com.agentmemory.recall;

import java.util.List;

/**
 * The outcome of a cross-project recall (issue #29): the scopes that were searched and the merged,
 * globally-ranked {@link ScopedRecallHit hits}, each tagged with its origin project. Mirrors
 * {@link RecallResult} (its single-scope sibling) plus the {@code scopes} list and per-hit scope
 * annotation.
 *
 * <p><strong>Isolation.</strong> {@code scopes} is exactly the set that was searched; a hit can only
 * come from one of them, so a cross-project query never leaks outside the requested projects (issue
 * #29 acceptance).
 *
 * @param scopes      the projects searched (one entry for the degenerate single-scope case; every
 *                    project for {@code global}); never null.
 * @param rawFallback {@code true} iff the hits are the bounded raw-observation fallback (surfaced only
 *                    when no scope produced a compiled-page hit), mirroring {@link RecallResult}.
 * @param hits        the merged hits, globally ranked best-first, each annotated with its scope.
 */
public record CrossProjectRecallResult(
        List<Scope> scopes, boolean rawFallback, List<ScopedRecallHit> hits) {

    public CrossProjectRecallResult {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    /** @return {@code true} when there are no hits at all. */
    public boolean isEmpty() {
        return hits.isEmpty();
    }
}
