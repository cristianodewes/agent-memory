package com.agentmemory.autoimprove;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.mcp.MemoryWriteService;
import com.agentmemory.recall.Scope;
import java.util.List;

/**
 * Applies a content {@link ProposalKinds#PAGE_EDIT} proposal through the normal durable-write path
 * (atomic page write + git commit + audit + link sync) — the original #30 applier, unchanged behavior,
 * now one branch of the {@link DispatchingProposalApplier}.
 */
public final class ContentProposalApplier implements ProposalApplier {

    private final MemoryWriteService writes;

    public ContentProposalApplier(MemoryWriteService writes) {
        this.writes = writes;
    }

    @Override
    public void apply(Scope scope, ProposedWrite write) {
        Identity id = Identity.ofPage(scope.workspace(), scope.project(), PagePath.of(write.path()));
        writes.writePages(
                List.of(new MemoryWriteService.PageWrite(id, write.title(), write.body())),
                "auto-improve");
    }
}
