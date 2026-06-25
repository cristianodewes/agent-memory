package com.agentmemory.timetravel;

import java.time.Instant;

/**
 * One wiki commit surfaced as a time-travel checkpoint (issue #34). The wiki is a single git repo
 * (DD-002; {@code WikiGit}), so every consolidation/session-end write is a commit and the commit log
 * is the list of points the wiki can be inspected at or a page restored from.
 *
 * @param sha       the full commit sha (the revision a {@code restore-page} can target).
 * @param shortSha  the abbreviated sha (first 10 chars) for compact display.
 * @param message   the commit message (e.g. {@code "consolidate: sessions/abc.md"}).
 * @param author    the commit author's display name.
 * @param committedAt the commit timestamp.
 */
public record Checkpoint(String sha, String shortSha, String message, String author,
                         Instant committedAt) {

    public Checkpoint {
        if (sha == null || sha.isBlank()) {
            throw new IllegalArgumentException("checkpoint sha must not be blank");
        }
    }
}
