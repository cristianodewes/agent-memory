package com.agentmemory.mcp;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two <strong>write</strong> MCP tools (issue #20, ARCHITECTURE §5.1): {@code memory_write_page}
 * and {@code memory_delete_page}. Both are thin handlers over the {@link MemoryWriteService} admission
 * chain — they resolve the scope ({@link ScopeResolver}, DD-003), validate arguments, call the one
 * service method, and shape the JSON result. The heavy lifting (redaction, versioning, the atomic
 * row+file+commit+audit, idempotent delete) lives in the service, not here.
 *
 * <p>Unlike the read tools, these advertise write {@link ToolAnnotations}: {@code memory_write_page}
 * is non-destructive but not idempotent (it creates a new version each call); {@code
 * memory_delete_page} is destructive and idempotent (deleting a missing/already-deleted page is a
 * no-op success). Surfacing these hints lets a client decide when to confirm before calling.
 */
public final class MemoryWriteTools {

    private final MemoryWriteService writes;
    private final ScopeResolver scopes;
    private final McpJson json;

    public MemoryWriteTools(MemoryWriteService writes, ScopeResolver scopes, McpJson json) {
        this.writes = writes;
        this.scopes = scopes;
        this.json = json;
    }

    /** @return the two write tool specifications to register on the MCP server. */
    public List<SyncToolSpecification> all() {
        return List.of(memoryWritePage(), memoryDeletePage());
    }

    // --- shared bits -------------------------------------------------------------------------------

    private static final ToolAnnotations WRITE_CREATE =
            new ToolAnnotations(null, /*readOnlyHint*/ false, /*destructiveHint*/ false,
                    /*idempotentHint*/ false, /*openWorldHint*/ false, null);

    private static final ToolAnnotations WRITE_DELETE =
            new ToolAnnotations(null, /*readOnlyHint*/ false, /*destructiveHint*/ true,
                    /*idempotentHint*/ true, /*openWorldHint*/ false, null);

    private static final Map<String, Object> SCOPE_PROPS = Map.of(
            "workspace", McpJson.stringProp(
                    "Workspace slug. Omit (with project) to use the most recently active project."),
            "project", McpJson.stringProp(
                    "Project slug. Omit (with workspace) to use the most recently active project."));

    private static Map<String, Object> withScope(Map<String, Object> extra) {
        var props = new LinkedHashMap<String, Object>(extra);
        props.putAll(SCOPE_PROPS);
        return props;
    }

    private SyncToolSpecification spec(Tool tool,
            java.util.function.Function<CallToolRequest, CallToolResult> handler) {
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    try {
                        return handler.apply(request);
                    } catch (ScopeResolver.ScopeUnresolvedException e) {
                        return McpJson.error("scope error: " + e.getMessage());
                    } catch (IllegalArgumentException e) {
                        return McpJson.error("invalid request: " + e.getMessage());
                    } catch (RuntimeException e) {
                        return McpJson.error("memory tool failed: " + e.getMessage());
                    }
                })
                .build();
    }

    private static String requiredStr(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("missing required '" + key + "'");
        }
        return v.toString();
    }

    private Identity pageIdentity(Scope scope, String path) {
        return Identity.ofPage(scope.workspace(), scope.project(), PagePath.of(path));
    }

    // --- memory_write_page -------------------------------------------------------------------------

    private SyncToolSpecification memoryWritePage() {
        Tool tool = Tool.builder()
                .name("memory_write_page")
                .description(
                        "Create or update a durable memory page at an exact path (e.g. "
                                + "concepts/recall.md). Use this when the user explicitly asks to "
                                + "remember a permanent fact or rule. The body is privacy-redacted, "
                                + "versioned (a new version supersedes any prior one), written to the "
                                + "wiki and committed, and is immediately searchable. Returns the "
                                + "stored page's path, title and version id.")
                .inputSchema(McpJson.objectSchema(withScope(Map.of(
                        "path", McpJson.stringProp("Exact page path, e.g. concepts/recall.md."),
                        "title", McpJson.stringProp("The page title."),
                        "body", McpJson.stringProp("The markdown body to remember."))),
                        List.of("path", "title", "body")))
                .annotations(WRITE_CREATE)
                .build();
        return spec(tool, request -> {
            Map<String, Object> args = request.arguments();
            Scope scope = scopes.resolve(args);
            String path = requiredStr(args, "path");
            String title = requiredStr(args, "title");
            String body = requiredStr(args, "body");

            Identity id = pageIdentity(scope, path);
            PageRecord saved = writes.writePage(id, title, body, MemoryWriteService.ACTOR_MCP);
            return json.ok(McpWriteDtos.WriteResult.of(scope, saved));
        });
    }

    // --- memory_delete_page ------------------------------------------------------------------------

    private SyncToolSpecification memoryDeletePage() {
        Tool tool = Tool.builder()
                .name("memory_delete_page")
                .description(
                        "Delete a memory page by exact path. Idempotent: deleting a path that does not "
                                + "exist is a no-op success. Removes the page from the index and the "
                                + "wiki (committed), so it stops being searchable. Returns whether a "
                                + "page was actually removed.")
                .inputSchema(McpJson.objectSchema(withScope(Map.of(
                        "path", McpJson.stringProp("Exact page path to delete, e.g. concepts/recall.md."))),
                        List.of("path")))
                .annotations(WRITE_DELETE)
                .build();
        return spec(tool, request -> {
            Map<String, Object> args = request.arguments();
            Scope scope = scopes.resolve(args);
            String path = requiredStr(args, "path");

            Identity id = pageIdentity(scope, path);
            boolean removed = writes.deletePage(id, MemoryWriteService.ACTOR_MCP);
            return json.ok(McpWriteDtos.DeleteResult.of(scope, path, removed));
        });
    }
}
