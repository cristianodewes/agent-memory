package com.agentmemory.timetravel;

import com.agentmemory.core.Identity;

/**
 * The outcome of a {@code restore-page} (issue #34): a single markdown file was restored from a git
 * revision back into the working tree and the derived index was rebuilt for it.
 *
 * @param identity     the page-scoped identity that was restored ({@code workspace/project/path}).
 * @param fromRev      the git revision the content was read from (as requested by the caller).
 * @param commitSha    the new commit recording the restore, or {@code null} when the restored bytes
 *                     were byte-identical to what is already on disk (a no-op restore, no new commit).
 * @param pagesIndexed how many pages the follow-up incremental reindex (re)indexed (normally 1).
 * @param changed      {@code true} when the restore actually changed the file (and thus committed +
 *                     reindexed); {@code false} for a no-op (the working tree already matched the rev).
 */
public record RestorePageResult(Identity identity, String fromRev, String commitSha,
                                int pagesIndexed, boolean changed) {
}
