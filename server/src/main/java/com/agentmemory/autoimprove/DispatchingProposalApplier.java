package com.agentmemory.autoimprove;

import com.agentmemory.recall.Scope;
import java.util.Map;

/**
 * The production {@link ProposalApplier}: routes an approved proposal to the applier for its
 * {@code kind} (issue #101). Content {@link ProposalKinds#PAGE_EDIT} goes to the #30 write-path applier;
 * {@link ProposalKinds#PAGE_FORGET} and {@link ProposalKinds#LINK_FIX} go to their corrective-action
 * appliers. Keeping the {@link ProposalApplier} seam means the {@link AutoImproveGate} is unchanged — it
 * still calls one {@code apply(scope, write)} and the eval-gating / audit logic is independent of which
 * action runs.
 *
 * <p>An unknown kind <strong>fails closed</strong>: it throws rather than silently dropping the proposal,
 * so the gate records the apply as failed (the row stays {@code proposed}) instead of marking a no-op
 * applied. With the {@code pending_writes_kind_valid} CHECK (V15) only allowlisted kinds can ever be
 * stored, so this is a belt-and-braces guard.
 */
public final class DispatchingProposalApplier implements ProposalApplier {

    private final Map<String, ProposalApplier> byKind;

    public DispatchingProposalApplier(
            ContentProposalApplier content,
            ForgetProposalApplier forget,
            LinkFixProposalApplier linkFix) {
        this.byKind = Map.of(
                ProposalKinds.PAGE_EDIT, content,
                ProposalKinds.PAGE_FORGET, forget,
                ProposalKinds.LINK_FIX, linkFix);
    }

    @Override
    public void apply(Scope scope, ProposedWrite write) {
        ProposalApplier applier = byKind.get(write.kind());
        if (applier == null) {
            throw new IllegalArgumentException("no applier for proposal kind: " + write.kind());
        }
        applier.apply(scope, write);
    }
}
