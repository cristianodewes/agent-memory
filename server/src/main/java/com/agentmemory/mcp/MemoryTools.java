package com.agentmemory.mcp;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.RecallQuery;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.RecallService;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.SlotsReader;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The five <strong>read-only</strong> MCP tools (issue #17, ARCHITECTURE §5.1): {@code memory_query},
 * {@code memory_recent}, {@code memory_read_page}, {@code memory_status}, {@code memory_briefing}.
 * Each is a thin handler over the recall ({@link RecallService}, #15/#16) and store
 * ({@link PageRepository}, #12) services plus the auxiliary {@link McpReadRepository}; the heavy
 * lifting lives in those layers, so a tool only resolves the scope, calls one service, and shapes the
 * JSON result.
 *
 * <p>Every tool advertises the read-only {@link ToolAnnotations} hint ({@code readOnlyHint=true},
 * {@code destructiveHint=false}) so a client knows it is safe to call without confirmation. Scope is
 * resolved per call by {@link ScopeResolver}: explicit {@code workspace}+{@code project} override,
 * otherwise the most recently active project (DD-003). A scope that cannot be resolved becomes a
 * clear tool error, not a guess.
 */
public final class MemoryTools {

    private final RecallService recall;
    private final PageRepository pages;
    private final McpReadRepository reads;
    private final ScopeResolver scopes;
    private final SlotsReader slots;
    private final McpJson json;

    public MemoryTools(
            RecallService recall,
            PageRepository pages,
            McpReadRepository reads,
            ScopeResolver scopes,
            SlotsReader slots,
            McpJson json) {
        this.recall = recall;
        this.pages = pages;
        this.reads = reads;
        this.scopes = scopes;
        this.slots = slots;
        this.json = json;
    }

    /** @return the five read tool specifications to register on the MCP server. */
    public List<SyncToolSpecification> all() {
        return List.of(memoryQuery(), memoryRecent(), memoryReadPage(), memoryStatus(), memoryBriefing());
    }

    // --- shared bits -------------------------------------------------------------------------------

    private static final ToolAnnotations READ_ONLY =
            new ToolAnnotations(null, /*readOnlyHint*/ true, /*destructiveHint*/ false,
                    /*idempotentHint*/ true, /*openWorldHint*/ false, null);

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;

    private static final Map<String, Object> SCOPE_PROPS = Map.of(
            "workspace", McpJson.stringProp(
                    "Workspace slug. Omit (with project) to use the most recently active project."),
            "project", McpJson.stringProp(
                    "Project slug. Omit (with workspace) to use the most recently active project."));

    /** Build a Tool with the read-only annotations and an object input schema. */
    private static Tool readTool(String name, String description,
            Map<String, Object> properties, List<String> required) {
        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(McpJson.objectSchema(properties, required))
                .annotations(READ_ONLY)
                .build();
    }

    private static Map<String, Object> withScope(Map<String, Object> extra) {
        var props = new java.util.LinkedHashMap<String, Object>(extra);
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

    private static int limitArg(Map<String, Object> args) {
        Object raw = args.get("limit");
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        int n = (raw instanceof Number num) ? num.intValue() : Integer.parseInt(raw.toString());
        if (n <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        return Math.min(n, MAX_LIMIT);
    }

    private static String requiredStr(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("missing required '" + key + "'");
        }
        return v.toString();
    }

    // --- memory_query ------------------------------------------------------------------------------

    private SyncToolSpecification memoryQuery() {
        Tool tool = readTool(
                "memory_query",
                "Hybrid recall over the project's memory: full-text + link-graph neighborhood fused "
                        + "with Reciprocal Rank Fusion, with a bounded raw-observation fallback when no "
                        + "compiled page matches. Returns ranked hits (path, score, rank, snippet).",
                withScope(Map.of(
                        "query", McpJson.stringProp("The search text."),
                        "limit", McpJson.intProp("Max hits to return (default 10, max 100)."))),
                List.of("query"));
        return spec(tool, request -> {
            Map<String, Object> args = request.arguments();
            String text = requiredStr(args, "query");
            Scope scope = scopes.resolve(args);
            RecallResult result = recall.search(new RecallQuery(text, scope, limitArg(args)));
            return json.ok(McpDtos.QueryResult.of(scope, result));
        });
    }

    // --- memory_recent -----------------------------------------------------------------------------

    private SyncToolSpecification memoryRecent() {
        Tool tool = readTool(
                "memory_recent",
                "The most recently updated pages in the project (latest versions only), newest first.",
                withScope(Map.of(
                        "limit", McpJson.intProp("Max pages to return (default 10, max 100)."))),
                List.of());
        return spec(tool, request -> {
            Map<String, Object> args = request.arguments();
            Scope scope = scopes.resolve(args);
            int limit = limitArg(args);
            // listLatest already returns updated_at DESC; cap to the requested limit.
            List<McpDtos.RecentPage> recent = pages.listLatest(scope.workspace(), scope.project())
                    .stream().limit(limit).map(McpDtos.RecentPage::of).toList();
            return json.ok(new McpDtos.RecentResult(McpDtos.ScopeView.of(scope), recent.size(), recent));
        });
    }

    // --- memory_read_page --------------------------------------------------------------------------

    private SyncToolSpecification memoryReadPage() {
        Tool tool = readTool(
                "memory_read_page",
                "Read a page's full body. Give an exact 'path', or omit it to read the top hit for a "
                        + "'query' (hybrid recall). Returns the title, body and version metadata.",
                withScope(Map.of(
                        "path", McpJson.stringProp("Exact page path, e.g. concepts/recall.md."),
                        "query", McpJson.stringProp(
                                "Search text; the top hit is read when 'path' is omitted."))),
                List.of());
        return spec(tool, request -> {
            Map<String, Object> args = request.arguments();
            Scope scope = scopes.resolve(args);
            String path = str(args.get("path"));
            String query = str(args.get("query"));

            if (path != null && !path.isBlank()) {
                Identity id = Identity.ofPage(scope.workspace(), scope.project(), PagePath.of(path));
                Optional<PageRecord> page = pages.readLatest(id);
                return page
                        .<CallToolResult>map(p -> json.ok(McpDtos.PageView.of(p, false)))
                        .orElseGet(() -> McpJson.error("no page at path '" + path + "' in "
                                + scope.workspaceSlug() + "/" + scope.projectSlug()));
            }
            if (query != null && !query.isBlank()) {
                // Resolve the top hit by recall, then read its full body.
                RecallResult hits = recall.search(new RecallQuery(query, scope, 1));
                Optional<RecallHit> top = hits.hits().stream().findFirst();
                if (top.isEmpty() || top.get().path() == null) {
                    return McpJson.error("no page matched query '" + query + "'");
                }
                Identity id = Identity.ofPage(
                        scope.workspace(), scope.project(), PagePath.of(top.get().path()));
                return pages.readLatest(id)
                        .<CallToolResult>map(p -> json.ok(McpDtos.PageView.of(p, true)))
                        .orElseGet(() -> McpJson.error("top hit page vanished: " + top.get().path()));
            }
            throw new IllegalArgumentException("provide either 'path' or 'query'");
        });
    }

    // --- memory_status -----------------------------------------------------------------------------

    private SyncToolSpecification memoryStatus() {
        Tool tool = readTool(
                "memory_status",
                "Lifetime counts for the project: pages, observations, sessions and links.",
                withScope(Map.of()),
                List.of());
        return spec(tool, request -> {
            Scope scope = scopes.resolve(request.arguments());
            McpReadRepository.Counts c = reads.counts(scope);
            return json.ok(new McpDtos.StatusResult(
                    McpDtos.ScopeView.of(scope), c.pages(), c.observations(), c.sessions(), c.links()));
        });
    }

    // --- memory_briefing ---------------------------------------------------------------------------

    private SyncToolSpecification memoryBriefing() {
        Tool tool = readTool(
                "memory_briefing",
                "A structured snapshot of the project (NO LLM call): counts, recent activity windows, "
                        + "the _rules/ listing, the memory slots (auto-pinned _slots/ pages with their "
                        + "slot_kind), and the most recent pages.",
                withScope(Map.of(
                        "limit", McpJson.intProp("Max recent pages to include (default 10, max 100)."))),
                List.of());
        return spec(tool, request -> {
            Map<String, Object> args = request.arguments();
            Scope scope = scopes.resolve(args);
            int limit = limitArg(args);
            McpReadRepository.Counts c = reads.counts(scope);
            List<McpDtos.RecentPage> recent = pages.listLatest(scope.workspace(), scope.project())
                    .stream().limit(limit).map(McpDtos.RecentPage::of).toList();
            // Slots are surfaced as a dedicated section with their write regime (slot_kind), read from
            // the wiki files (source of truth) so a schema-free _slots/ page still carries its kind.
            List<McpDtos.SlotView> slotViews = slots.list(scope.workspace(), scope.project())
                    .stream().map(McpDtos.SlotView::of).toList();
            return json.ok(new McpDtos.BriefingResult(
                    McpDtos.ScopeView.of(scope),
                    c.pages(), c.observations(), c.sessions(), c.links(), c.dependents(),
                    reads.observationsInLastDays(scope, 7),
                    reads.observationsInLastDays(scope, 30),
                    reads.latestPathsUnder(scope, "_rules/", 50),
                    slotViews,
                    recent));
        });
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
