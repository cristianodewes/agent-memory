package com.agentmemory.mcp;

import com.agentmemory.autoimprove.AutoImproveGate;
import com.agentmemory.autoimprove.PendingWriteRecord;
import com.agentmemory.recall.Scope;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The {@code memory_auto_improve} MCP tool (issue #30): the human surface over the auto-improve approval
 * gate. Three actions over {@link AutoImproveGate}:
 * <ul>
 *   <li>{@code report} (default, read-only) — the recent self-improvement proposals in a project and their
 *       status ({@code proposed}/{@code applied}/{@code rejected}).</li>
 *   <li>{@code approve} — apply a held {@code proposed} edit through the normal durable-write path.</li>
 *   <li>{@code reject} — discard a held {@code proposed} edit, leaving memory untouched.</li>
 * </ul>
 *
 * <p>Approve/reject operate by proposal id (the proposal already carries its own scope), so they need no
 * workspace/project. This surface is independent of the deferred review engine (#29/#19): it works on
 * whatever proposals exist, whether produced by the scheduler later or recorded by a direct submit.
 */
public final class AutoImproveTools {

    private final AutoImproveGate gate;
    private final ScopeResolver scopes;
    private final McpJson json;

    public AutoImproveTools(AutoImproveGate gate, ScopeResolver scopes, McpJson json) {
        this.gate = gate;
        this.scopes = scopes;
        this.json = json;
    }

    /** @return the single auto-improve tool specification to register on the MCP server. */
    public List<SyncToolSpecification> all() {
        return List.of(memoryAutoImprove());
    }

    // Not read-only (approve applies a write), not destructive (reject only marks a row), not idempotent
    // (a second approve/reject of the same id fails the state check).
    private static final ToolAnnotations WRITE_DECISION =
            new ToolAnnotations(null, /*readOnlyHint*/ false, /*destructiveHint*/ false,
                    /*idempotentHint*/ false, /*openWorldHint*/ false, null);

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private SyncToolSpecification memoryAutoImprove() {
        Tool tool = Tool.builder()
                .name("memory_auto_improve")
                .description(
                        "Review and act on this project's self-improvement proposals (edits to durable "
                                + "memory the system proposed from reviewing past sessions). action="
                                + "'report' (default) lists recent proposals and their status; "
                                + "action='approve' with an id applies a held proposal through the normal "
                                + "write path; action='reject' with an id discards one. Approving/"
                                + "rejecting needs only the id. Scope (workspace+project) applies to "
                                + "report and defaults to the most recently active project.")
                .inputSchema(McpJson.objectSchema(Map.of(
                        "action", McpJson.stringProp(
                                "One of 'report' (default), 'approve', 'reject'."),
                        "id", McpJson.stringProp(
                                "Proposal id to approve/reject (required for those actions)."),
                        "limit", McpJson.intProp(
                                "report: max proposals to return (default 20, max 100)."),
                        "workspace", McpJson.stringProp(
                                "Workspace slug (report). Omit to use the most recently active project."),
                        "project", McpJson.stringProp(
                                "Project slug (report). Omit to use the most recently active project.")),
                        List.of()))
                .annotations(WRITE_DECISION)
                .build();
        return spec(tool, request -> {
            Map<String, Object> args = request.arguments();
            String action = str(args.get("action"), "report").toLowerCase(Locale.ROOT);
            return switch (action) {
                case "report" -> report(args);
                case "approve" -> json.ok(ProposalView.of(gate.approve(proposalId(args))));
                case "reject" -> json.ok(ProposalView.of(gate.reject(proposalId(args))));
                default -> McpJson.error("invalid request: unknown action '" + action
                        + "' (expected report, approve, or reject)");
            };
        });
    }

    private CallToolResult report(Map<String, Object> args) {
        Scope scope = scopes.resolve(args);
        int limit = limitArg(args);
        List<PendingWriteRecord> rows = gate.report(scope, limit);
        List<ProposalView> views = rows.stream().map(ProposalView::of).toList();
        return json.ok(new ReportView(scope.workspaceSlug(), scope.projectSlug(), views.size(), views));
    }

    private static UUID proposalId(Map<String, Object> args) {
        Object raw = args.get("id");
        if (raw == null || raw.toString().isBlank()) {
            throw new IllegalArgumentException("missing required 'id'");
        }
        try {
            return UUID.fromString(raw.toString().strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'id' is not a valid proposal id: " + raw);
        }
    }

    private static int limitArg(Map<String, Object> args) {
        Object raw = args.get("limit");
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        int n = raw instanceof Number num ? num.intValue() : Integer.parseInt(raw.toString().strip());
        return Math.max(1, Math.min(MAX_LIMIT, n));
    }

    private static String str(Object v, String fallback) {
        return v == null || v.toString().isBlank() ? fallback : v.toString().strip();
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
                    } catch (IllegalStateException e) {
                        return McpJson.error("conflict: " + e.getMessage());
                    } catch (RuntimeException e) {
                        return McpJson.error("memory tool failed: " + e.getMessage());
                    }
                })
                .build();
    }

    /** Client-facing view of one proposal row (timestamps/ids as strings for stable JSON). */
    record ProposalView(
            String id, String path, String status, String kind, String rationale,
            String session, String createdAt, String decidedAt, String appliedAt) {

        static ProposalView of(PendingWriteRecord r) {
            return new ProposalView(
                    r.id().toString(),
                    r.path(),
                    r.status().db(),
                    r.kind(),
                    r.rationale(),
                    r.sessionId() == null ? null : r.sessionId().toString(),
                    iso(r.createdAt()),
                    iso(r.decidedAt()),
                    iso(r.appliedAt()));
        }

        private static String iso(Instant i) {
            return i == null ? null : i.toString();
        }
    }

    /** The report payload. */
    record ReportView(String workspace, String project, int count, List<ProposalView> proposals) {}
}
