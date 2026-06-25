package com.agentmemory.mcp;

import com.agentmemory.forget.ForgetSweepService;
import com.agentmemory.forget.SweepCandidate;
import com.agentmemory.forget.SweepReport;
import com.agentmemory.recall.Scope;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code memory_forget_sweep} MCP tool (issue #25): runs the forget sweep over the resolved
 * project and returns what was soft-deleted and purged. A thin handler over {@link ForgetSweepService}
 * — resolves the scope ({@link ScopeResolver}, DD-003), reads the {@code dry_run} flag, calls the one
 * service method, and shapes the JSON result.
 *
 * <p>Advertised with a <strong>destructive</strong> {@link ToolAnnotations} hint (it soft-deletes and
 * purges pages) so a client confirms before calling — except a {@code dry_run} is a pure preview. The
 * tool defaults to {@code dry_run=false}; callers that want a preview pass {@code dry_run: true}.
 */
public final class MemorySweepTools {

    private final ForgetSweepService sweep;
    private final ScopeResolver scopes;
    private final McpJson json;

    public MemorySweepTools(ForgetSweepService sweep, ScopeResolver scopes, McpJson json) {
        this.sweep = sweep;
        this.scopes = scopes;
        this.json = json;
    }

    /** @return the sweep tool specification(s) to register on the MCP server. */
    public List<SyncToolSpecification> all() {
        return List.of(memoryForgetSweep());
    }

    // The sweep mutates (soft-delete + purge): destructive, not read-only. Not idempotent in general
    // (a second run can soft-delete pages that have since gone cold), though a dry_run mutates nothing.
    private static final ToolAnnotations SWEEP =
            new ToolAnnotations(null, /*readOnlyHint*/ false, /*destructiveHint*/ true,
                    /*idempotentHint*/ false, /*openWorldHint*/ false, null);

    private static final Map<String, Object> SCOPE_PROPS = Map.of(
            "workspace", McpJson.stringProp(
                    "Workspace slug. Omit (with project) to use the most recently active project."),
            "project", McpJson.stringProp(
                    "Project slug. Omit (with workspace) to use the most recently active project."));

    private SyncToolSpecification memoryForgetSweep() {
        var props = new LinkedHashMap<String, Object>();
        props.put("dry_run", McpJson.boolProp(
                "Preview only: report what would be soft-deleted/purged without changing anything. "
                        + "Defaults to false (the sweep runs)."));
        props.putAll(SCOPE_PROPS);
        Tool tool = Tool.builder()
                .name("memory_forget_sweep")
                .description(
                        "Run the forget sweep for a project: cold pages (low retention) are soft-deleted "
                                + "(dropped from latest, recoverable until purge), and long-cold "
                                + "soft-deletes are purged. Semantic, slot (_slots/), and recently "
                                + "accessed pages are never swept. Pass dry_run=true for a no-mutation "
                                + "preview. Returns the soft-deleted and purged page paths and counts.")
                .inputSchema(McpJson.objectSchema(props, List.of()))
                .annotations(SWEEP)
                .build();
        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> handle(request))
                .build();
    }

    private CallToolResult handle(CallToolRequest request) {
        try {
            Map<String, Object> args = request.arguments();
            Scope scope = scopes.resolve(args);
            boolean dryRun = boolArg(args.get("dry_run"));
            SweepReport report = sweep.sweep(scope.workspace(), scope.project(), dryRun);
            return json.ok(SweepResultView.of(scope, report));
        } catch (ScopeResolver.ScopeUnresolvedException e) {
            return McpJson.error("scope error: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return McpJson.error("invalid request: " + e.getMessage());
        } catch (RuntimeException e) {
            return McpJson.error("memory tool failed: " + e.getMessage());
        }
    }

    private static boolean boolArg(Object raw) {
        if (raw == null) {
            return false;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(raw.toString());
    }

    /** The flat JSON shape {@code memory_forget_sweep} returns. */
    record SweepResultView(
            String workspace, String project, boolean dryRun,
            int softDeletedCount, int purgedCount, int exemptSkipped,
            List<SweepCandidate> softDeleted, List<SweepCandidate> purged) {

        static SweepResultView of(Scope scope, SweepReport r) {
            return new SweepResultView(
                    scope.workspaceSlug(), scope.projectSlug(), r.dryRun(),
                    r.softDeletedCount(), r.purgedCount(), r.exemptSkipped(),
                    r.softDeleted(), r.purged());
        }
    }
}
