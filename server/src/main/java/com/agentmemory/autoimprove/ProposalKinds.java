package com.agentmemory.autoimprove;

/**
 * The {@code pending_writes.kind} vocabulary the auto-improve loop produces and dispatches on. The
 * content kind ({@link #PAGE_EDIT}, issue #30) is a page upsert applied through the write service; the
 * action kinds (issue #101) are corrective edits the {@link DispatchingProposalApplier} routes to
 * purpose-built appliers. The schema-level allowlist (the {@code pending_writes_kind_valid} CHECK,
 * migration V15) is kept in lockstep with this set.
 *
 * <p>Deferred follow-up kinds ({@code page.merge} for {@code DUPLICATE_TITLE}, {@code slot.refresh} for
 * {@code STALE_SLOT}) are intentionally <strong>not</strong> declared here and not in the CHECK: nothing
 * dormant — a kind exists only once it has a real source and applier. Adding one is a constant here, a
 * branch in the dispatcher, and one line in a follow-up migration's CHECK.
 */
public final class ProposalKinds {

    /** Content upsert of a page (create/update {@code title}/{@code body}); the #30 content path. */
    public static final String PAGE_EDIT = "page.edit";

    /** Forget (soft-delete/demote) the page at {@code path}; from the curator's {@code COLD_EPISODIC} rule. */
    public static final String PAGE_FORGET = "page.forget";

    /**
     * Fix a broken wikilink by pruning it from the page at {@code path}; from the curator's
     * {@code DANGLING_CROSS_PROJECT} rule. The dangling target travels in {@code params[target]} as
     * {@code workspace/project/path}.
     */
    public static final String LINK_FIX = "link.fix";

    /** {@code link.fix} parameter: the dangling target to prune, as {@code workspace/project/path}. */
    public static final String PARAM_TARGET = "target";

    private ProposalKinds() {}
}
