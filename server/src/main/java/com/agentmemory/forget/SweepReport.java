package com.agentmemory.forget;

import java.util.List;

/**
 * The outcome of a forget sweep (issue #25): the pages soft-deleted (cold, now dropped from "latest")
 * and the pages purged (long-cold soft-deletes, now hard-deleted). When {@code dryRun} is true these
 * are the pages that <em>would</em> be affected and nothing was mutated; otherwise they are what was
 * actually changed. {@code exemptSkipped} counts latest pages that scored cold but were protected
 * (semantic / slot / recently-accessed), so a caller can see the guard did its job.
 *
 * @param dryRun        whether this was a preview ({@code true} = nothing mutated).
 * @param softDeleted   pages soft-deleted (or that would be) this run.
 * @param purged        pages purged (or that would be) this run.
 * @param exemptSkipped number of cold latest pages skipped because they were exempt from sweeping.
 */
public record SweepReport(
        boolean dryRun,
        List<SweepCandidate> softDeleted,
        List<SweepCandidate> purged,
        int exemptSkipped) {

    public SweepReport {
        softDeleted = softDeleted == null ? List.of() : List.copyOf(softDeleted);
        purged = purged == null ? List.of() : List.copyOf(purged);
    }

    /** @return number of pages soft-deleted (or previewed). */
    public int softDeletedCount() {
        return softDeleted.size();
    }

    /** @return number of pages purged (or previewed). */
    public int purgedCount() {
        return purged.size();
    }
}
