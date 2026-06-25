package com.agentmemory.mcp;

import com.agentmemory.consolidate.ConsolidationOutcome;
import com.agentmemory.consolidate.Consolidator;
import com.agentmemory.consolidate.MemoryExplore;
import com.agentmemory.core.SessionId;
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
 * The two consolidation MCP tools (issue #19; ARCHITECTURE §5.1): {@code memory_consolidate} and
 * {@code memory_explore}. Thin handlers over {@link Consolidator} (LLM multi-page fan-out) and
 * {@link MemoryExplore} (LLM prose digest) — they resolve the scope ({@link ScopeResolver}, DD-003),
 * validate arguments, call the one service, and shape the JSON result; the heavy lifting (LLM call,
 * structured parse, atomic fan-out, snapshot) lives in the services.
 *
 * <ul>
 *   <li><b>memory_consolidate</b> — rewrites a session's durable knowledge into long-lived pages,
 *       superseding prior versions; {@code multi_page=true} fans out across folders atomically (one
 *       commit, all-or-nothing). <em>Destructive</em> hint: it creates new page versions that
 *       supersede existing latest pages.</li>
 *   <li><b>memory_explore</b> — a calibrated prose digest of the project (verbosity scales with time
 *       since last activity). Read-only.</li>
 * </ul>
 */
public final class ConsolidationTools {

    /** Actor recorded in the audit log for consolidation arriving through the MCP tool surface. */
    private static final String ACTOR_MCP = "mcp";

    private final Consolidator consolidator;
    private final MemoryExplore explore;
    private final ScopeResolver scopes;
    private final McpJson json;

    public ConsolidationTools(
            Consolidator consolidator, MemoryExplore explore, ScopeResolver scopes, McpJson json) {
        this.consolidator = consolidator;
        this.explore = explore;
        this.scopes = scopes;
        this.json = json;
    }

    /** @return the two consolidation tool specifications to register on the MCP server. */
    public List<SyncToolSpecification> all() {
        return List.of(memoryConsolidate(), memoryExplore());
    }

    // --- shared bits -------------------------------------------------------------------------------

    /** Consolidate creates new versions that supersede existing pages: destructive, not idempotent. */
    private static final ToolAnnotations CONSOLIDATE =
            new ToolAnnotations(null, /*readOnlyHint*/ false, /*destructiveHint*/ true,
                    /*idempotentHint*/ false, /*openWorldHint*/ false, null);

    /** Explore is read-only (it only reads the snapshot + asks the model for prose). */
    private static final ToolAnnotations EXPLORE =
            new ToolAnnotations(null, /*readOnlyHint*/ true, /*destructiveHint*/ false,
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
                        return McpJson.error("consolidation tool failed: " + e.getMessage());
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

    private static boolean boolArg(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(v.toString());
    }

    // --- memory_consolidate ------------------------------------------------------------------------

    private SyncToolSpecification memoryConsolidate() {
        Tool tool = Tool.builder()
                .name("memory_consolidate")
                .description(
                        "Compile a session's durable, reusable knowledge into long-lived wiki pages "
                                + "(concepts/decisions/gotchas/procedures), superseding prior versions. "
                                + "With multi_page=true the model may fan out into several pages across "
                                + "folders, written atomically as one commit (all-or-nothing). Requires "
                                + "the session_id to consolidate. Returns the pages written.")
                .inputSchema(McpJson.objectSchema(withScope(Map.of(
                        "session_id", McpJson.stringProp(
                                "The session to consolidate (a UUID, e.g. this run's session id)."),
                        "multi_page", Map.of("type", "boolean", "description",
                                "Allow fan-out into multiple pages (default false = a single page)."))),
                        List.of("session_id")))
                .annotations(CONSOLIDATE)
                .build();
        return spec(tool, request -> {
            Map<String, Object> args = request.arguments();
            Scope scope = scopes.resolve(args);
            SessionId session;
            try {
                session = SessionId.of(requiredStr(args, "session_id"));
            } catch (IllegalArgumentException e) {
                return McpJson.error("invalid 'session_id': " + e.getMessage());
            }
            boolean multiPage = boolArg(args, "multi_page");
            ConsolidationOutcome outcome = consolidator.consolidate(scope, session, multiPage, ACTOR_MCP);
            return json.ok(ConsolidateResult.of(scope, outcome));
        });
    }

    // --- memory_explore ----------------------------------------------------------------------------

    private SyncToolSpecification memoryExplore() {
        Tool tool = Tool.builder()
                .name("memory_explore")
                .description(
                        "A calibrated PROSE digest of the project's compiled memory — what is here and "
                                + "where things stand. Verbosity scales with time since last activity "
                                + "(fresh: a line; stale: a fuller catch-up). Read-only; builds on the "
                                + "briefing snapshot plus one LLM call.")
                .inputSchema(McpJson.objectSchema(withScope(Map.of()), List.of()))
                .annotations(EXPLORE)
                .build();
        return spec(tool, request -> {
            Scope scope = scopes.resolve(request.arguments());
            MemoryExplore.ExploreResult result = explore.explore(scope);
            return json.ok(result);
        });
    }

    /**
     * The wire shape {@code memory_consolidate} returns: the resolved scope, how many pages were
     * written, whether it was a no-op (no observations), and the written page paths/titles/versions.
     */
    record ConsolidateResult(
            McpDtos.ScopeView scope, String status, int pageCount, List<WrittenPage> pages) {

        static ConsolidateResult of(Scope scope, ConsolidationOutcome outcome) {
            List<WrittenPage> written = outcome.pages().stream().map(WrittenPage::of).toList();
            return new ConsolidateResult(
                    McpDtos.ScopeView.of(scope),
                    outcome.status().name().toLowerCase(java.util.Locale.ROOT),
                    written.size(),
                    written);
        }

        /** One written durable page: its path, title, new version id, and whether it superseded one. */
        record WrittenPage(String path, String title, String versionId, boolean superseded) {
            static WrittenPage of(PageRecord r) {
                return new WrittenPage(
                        r.page().path().value(),
                        r.page().title(),
                        r.id().value().toString(),
                        r.page().supersedes() != null);
            }
        }
    }
}
