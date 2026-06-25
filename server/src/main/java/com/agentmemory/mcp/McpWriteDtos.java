package com.agentmemory.mcp;

import com.agentmemory.mcp.McpDtos.ScopeView;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;

/**
 * The flat JSON shapes the MCP <strong>write</strong> tools return (issue #20). Kept beside
 * {@link McpDtos} and, like it, deliberately decoupled from the domain records: the tool wire format
 * is a stable contract for agents and serializes cleanly with the bare Jackson 3 mapper.
 */
final class McpWriteDtos {

    private McpWriteDtos() {}

    /**
     * {@code memory_write_page} result: the resolved scope, the stored page's path/title, the new
     * version id, and whether this write superseded an existing page (vs. created a fresh one).
     */
    record WriteResult(
            ScopeView scope, String path, String title, String versionId, boolean superseded) {
        static WriteResult of(Scope scope, PageRecord saved) {
            return new WriteResult(
                    ScopeView.of(scope),
                    saved.page().path().value(),
                    saved.page().title(),
                    saved.id().value().toString(),
                    saved.page().supersedes() != null);
        }
    }

    /**
     * {@code memory_delete_page} result: the resolved scope, the path, and whether a page actually
     * existed and was removed ({@code removed=false} is the idempotent no-op success).
     */
    record DeleteResult(ScopeView scope, String path, boolean removed) {
        static DeleteResult of(Scope scope, String path, boolean removed) {
            return new DeleteResult(ScopeView.of(scope), path, removed);
        }
    }
}
