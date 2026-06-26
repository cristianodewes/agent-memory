package com.agentmemory.autoimprove;

import com.agentmemory.core.PagePath;
import com.agentmemory.forget.ForgetSweepService;
import com.agentmemory.recall.Scope;

/**
 * Applies a {@link ProposalKinds#PAGE_FORGET} proposal by soft-deleting the cold page through the forget
 * sweep service (issue #101, curator {@code COLD_EPISODIC} action) — the page drops from "latest" and is
 * marked {@code deleted_at}, recoverable until purge. The page is {@code write.path()}; idempotent when
 * the page is already gone.
 */
public final class ForgetProposalApplier implements ProposalApplier {

    private final ForgetSweepService forget;

    public ForgetProposalApplier(ForgetSweepService forget) {
        this.forget = forget;
    }

    @Override
    public void apply(Scope scope, ProposedWrite write) {
        String reason = write.rationale() == null ? "curator COLD_EPISODIC" : write.rationale();
        forget.forgetPage(scope.workspace(), scope.project(), PagePath.of(write.path()), reason);
    }
}
