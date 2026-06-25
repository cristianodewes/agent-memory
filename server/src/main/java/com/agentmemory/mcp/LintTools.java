package com.agentmemory.mcp;

import com.agentmemory.curate.Contradiction;
import com.agentmemory.curate.CuratorFinding;
import com.agentmemory.curate.LintReport;
import com.agentmemory.curate.MemoryLintService;
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
 * The {@code memory_lint} MCP tool (issue #29): runs the rule-based curator over the resolved project
 * and, unless told otherwise, an LLM contradiction pass, then returns the findings — and, when not a
 * dry run, persists them as a {@code _lint/} page. A thin handler over {@link MemoryLintService}.
 *
 * <p>One surface covers the issue's "curator" and "memory_lint" items via two flags:
 * <ul>
 *   <li>{@code dry_run} (default {@code true}) — report only; {@code false} stages a {@code _lint/} page.</li>
 *   <li>{@code contradictions} (default {@code true}) — include the (paid) LLM pass; {@code false} is a
 *       zero-cost, purely rule-based run.</li>
 * </ul>
 *
 * <p>Advertised non-read-only (it can write a {@code _lint/} page) but not destructive (it adds/updates
 * a single report page); a {@code dry_run} mutates nothing.
 */
public final class LintTools {

    private final MemoryLintService lint;
    private final ScopeResolver scopes;
    private final McpJson json;

    public LintTools(MemoryLintService lint, ScopeResolver scopes, McpJson json) {
        this.lint = lint;
        this.scopes = scopes;
        this.json = json;
    }

    /** @return the lint tool specification(s) to register on the MCP server. */
    public List<SyncToolSpecification> all() {
        return List.of(memoryLint());
    }

    // Writes a single _lint/ report page when not a dry run: not read-only, not destructive,
    // idempotent (re-running overwrites the same report page).
    private static final ToolAnnotations LINT =
            new ToolAnnotations(null, /*readOnlyHint*/ false, /*destructiveHint*/ false,
                    /*idempotentHint*/ true, /*openWorldHint*/ false, null);

    private static final Map<String, Object> SCOPE_PROPS = Map.of(
            "workspace", McpJson.stringProp(
                    "Workspace slug. Omit (with project) to use the most recently active project."),
            "project", McpJson.stringProp(
                    "Project slug. Omit (with workspace) to use the most recently active project."));

    private SyncToolSpecification memoryLint() {
        var props = new LinkedHashMap<String, Object>();
        props.put("dry_run", McpJson.boolProp(
                "Preview only: return the findings without writing anything. Defaults to true; pass "
                        + "false to stage a _lint/ report page."));
        props.put("contradictions", McpJson.boolProp(
                "Run the LLM contradiction pass in addition to the zero-cost rules. Defaults to true; "
                        + "pass false for a purely rule-based, zero-cost run."));
        props.putAll(SCOPE_PROPS);
        Tool tool = Tool.builder()
                .name("memory_lint")
                .description(
                        "Audit a project's memory for maintenance issues: rule-based findings (cold "
                                + "episodic pages, stale slots, duplicate titles, dangling "
                                + "cross-project links) plus, by default, an LLM pass that flags pages "
                                + "that contradict each other. Returns the findings; pass dry_run=false "
                                + "to persist them as a _lint/ report page, or contradictions=false for "
                                + "a zero-cost rule-only run.")
                .inputSchema(McpJson.objectSchema(props, List.of()))
                .annotations(LINT)
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
            boolean dryRun = boolArg(args.get("dry_run"), true);
            boolean contradictions = boolArg(args.get("contradictions"), true);
            LintReport report = lint.lint(scope, contradictions, dryRun);
            return json.ok(LintResultView.of(scope, report, dryRun, contradictions));
        } catch (ScopeResolver.ScopeUnresolvedException e) {
            return McpJson.error("scope error: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return McpJson.error("invalid request: " + e.getMessage());
        } catch (RuntimeException e) {
            return McpJson.error("memory tool failed: " + e.getMessage());
        }
    }

    private static boolean boolArg(Object raw, boolean dflt) {
        if (raw == null) {
            return dflt;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(raw.toString());
    }

    /** A rule finding in the lint result. */
    record FindingView(String rule, String path, String detail) {
        static FindingView of(CuratorFinding f) {
            return new FindingView(f.rule().name(), f.path(), f.detail());
        }
    }

    /** A contradiction in the lint result. */
    record ContradictionView(List<String> pages, String explanation) {
        static ContradictionView of(Contradiction c) {
            return new ContradictionView(c.pages(), c.explanation());
        }
    }

    /** The flat JSON shape {@code memory_lint} returns. */
    record LintResultView(
            String workspace, String project, boolean dryRun, boolean contradictionsRequested,
            boolean written, String lintPath,
            int ruleFindingCount, int contradictionCount,
            List<FindingView> ruleFindings, List<ContradictionView> contradictions) {

        static LintResultView of(
                Scope scope, LintReport r, boolean dryRun, boolean contradictionsRequested) {
            return new LintResultView(
                    scope.workspaceSlug(), scope.projectSlug(), dryRun, contradictionsRequested,
                    r.written(), r.lintPath(),
                    r.ruleFindings().size(), r.contradictions().size(),
                    r.ruleFindings().stream().map(FindingView::of).toList(),
                    r.contradictions().stream().map(ContradictionView::of).toList());
        }
    }
}
