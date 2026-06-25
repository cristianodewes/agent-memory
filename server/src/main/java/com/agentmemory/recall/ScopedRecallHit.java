package com.agentmemory.recall;

/**
 * A {@link RecallHit} annotated with the {@link Scope} it came from — the per-hit scope tag a
 * cross-project query needs (issue #29) so a caller can tell which project produced each hit when the
 * search spanned several. For a single-project query the annotation is redundant (every hit shares the
 * one scope), so it is only surfaced on the cross-project path.
 *
 * @param workspace the origin workspace slug of {@code hit}; never null.
 * @param project   the origin project slug of {@code hit}; never null.
 * @param hit       the underlying ranked hit (its {@code rank} is the global cross-scope rank); never null.
 */
public record ScopedRecallHit(String workspace, String project, RecallHit hit) {

    public ScopedRecallHit {
        if (workspace == null) {
            throw new IllegalArgumentException("scopedRecallHit.workspace must not be null");
        }
        if (project == null) {
            throw new IllegalArgumentException("scopedRecallHit.project must not be null");
        }
        if (hit == null) {
            throw new IllegalArgumentException("scopedRecallHit.hit must not be null");
        }
    }
}
