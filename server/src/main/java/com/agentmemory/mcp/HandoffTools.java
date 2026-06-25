package com.agentmemory.mcp;

import com.agentmemory.core.Handoff;
import com.agentmemory.core.SessionId;
import com.agentmemory.handoff.HandoffException;
import com.agentmemory.handoff.HandoffService;
import com.agentmemory.recall.Scope;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The three handoff MCP tools (issue #22; ARCHITECTURE §3.4): {@code memory_handoff_begin},
 * {@code memory_handoff_accept}, {@code memory_handoff_cancel}. Unlike the {@link MemoryTools} read
 * surface these <em>mutate</em> the handoff lifecycle, so they advertise {@code readOnlyHint=false}.
 *
 * <ul>
 *   <li><b>begin</b> — synthesize an LLM-written handoff for a session and open it (the explicit path
 *       for a client with no {@code session-end} hook). Requires {@code sessionId}.</li>
 *   <li><b>accept</b> — return the latest open handoff for the project and mark it consumed. Single-use:
 *       a second call returns "no open handoff". This is the cross-agent injection point — any agent in
 *       the project consumes the one a prior agent left.</li>
 *   <li><b>cancel</b> — expire the latest open handoff without consuming it (a mistaken one).</li>
 * </ul>
 *
 * <p>Scope is resolved exactly like the read tools (via {@link ScopeResolver}): explicit
 * {@code workspace}+{@code project}, else the most recently active project (DD-003). The heavy lifting
 * — LLM call, structured-JSON parse, atomic single-writer persistence, index-in-tx — lives in
 * {@link HandoffService}; a tool just resolves scope, calls one method, and shapes the JSON result.
 */
public final class HandoffTools {

    private final HandoffService handoffs;
    private final ScopeResolver scopes;
    private final McpJson json;

    public HandoffTools(HandoffService handoffs, ScopeResolver scopes, McpJson json) {
        this.handoffs = handoffs;
        this.scopes = scopes;
        this.json = json;
    }

    /** @return the three handoff tool specifications to register on the MCP server. */
    public List<SyncToolSpecification> all() {
        return List.of(handoffBegin(), handoffAccept(), handoffCancel());
    }

    // --- shared bits -------------------------------------------------------------------------------

    /**
     * Mutating-tool annotations: not read-only, but {@code destructiveHint=false} — begin supersedes a
     * prior open handoff and cancel expires one, neither destroys durable knowledge (the wiki/pages are
     * untouched); accept is a single-use consume. {@code idempotentHint=false}: a second begin opens a
     * new handoff; a second accept returns nothing.
     */
    private static final ToolAnnotations MUTATING =
            new ToolAnnotations(null, /*readOnlyHint*/ false, /*destructiveHint*/ false,
                    /*idempotentHint*/ false, /*openWorldHint*/ false, null);

    private static final Map<String, Object> SCOPE_PROPS = Map.of(
            "workspace", McpJson.stringProp(
                    "Workspace slug. Omit (with project) to use the most recently active project."),
            "project", McpJson.stringProp(
                    "Project slug. Omit (with workspace) to use the most recently active project."));

    private static Tool mutatingTool(String name, String description,
            Map<String, Object> properties, List<String> required) {
        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(McpJson.objectSchema(properties, required))
                .annotations(MUTATING)
                .build();
    }

    private static Map<String, Object> withScope(Map<String, Object> extra) {
        Map<String, Object> props = new LinkedHashMap<>(extra);
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
                    } catch (HandoffException e) {
                        return McpJson.error("handoff generation failed: " + e.getMessage());
                    } catch (IllegalArgumentException e) {
                        return McpJson.error("invalid request: " + e.getMessage());
                    } catch (RuntimeException e) {
                        return McpJson.error("handoff tool failed: " + e.getMessage());
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

    // --- memory_handoff_begin ----------------------------------------------------------------------

    private SyncToolSpecification handoffBegin() {
        Tool tool = mutatingTool(
                "memory_handoff_begin",
                "Generate and open an LLM-written handoff for a session (the explicit path when there "
                        + "is no session-end hook). The model reads the session's captured events and "
                        + "writes a 'where you left off' note: summary, open questions, next steps. "
                        + "Supersedes any prior open handoff for the project. Returns the opened handoff.",
                withScope(Map.of(
                        "sessionId", McpJson.stringProp(
                                "The session to summarize (this run's session id, a UUID)."))),
                List.of("sessionId"));
        return spec(tool, request -> {
            Map<String, Object> args = request.arguments();
            Scope scope = scopes.resolve(args);
            SessionId session;
            try {
                session = SessionId.of(requiredStr(args, "sessionId"));
            } catch (IllegalArgumentException e) {
                return McpJson.error("invalid 'sessionId': " + e.getMessage());
            }
            Handoff handoff = handoffs.begin(scope, session);
            return json.ok(HandoffDto.opened(scope, handoff));
        });
    }

    // --- memory_handoff_accept ---------------------------------------------------------------------

    private SyncToolSpecification handoffAccept() {
        Tool tool = mutatingTool(
                "memory_handoff_accept",
                "Fetch the latest OPEN handoff for the project and mark it consumed (single-use). "
                        + "Call this at session start to pick up where the previous agent left off; a "
                        + "second call returns no handoff. Consumable cross-agent within the project.",
                withScope(Map.of()),
                List.of());
        return spec(tool, request -> {
            Scope scope = scopes.resolve(request.arguments());
            Optional<Handoff> accepted = handoffs.accept(scope);
            return json.ok(accepted
                    .map(h -> HandoffDto.of(scope, h, true))
                    .orElseGet(() -> HandoffDto.none(scope)));
        });
    }

    // --- memory_handoff_cancel ---------------------------------------------------------------------

    private SyncToolSpecification handoffCancel() {
        Tool tool = mutatingTool(
                "memory_handoff_cancel",
                "Expire the latest OPEN handoff for the project without consuming it (use when a "
                        + "handoff was opened in error). Returns the expired handoff, or none if there "
                        + "was nothing open.",
                withScope(Map.of()),
                List.of());
        return spec(tool, request -> {
            Scope scope = scopes.resolve(request.arguments());
            Optional<Handoff> cancelled = handoffs.cancel(scope);
            return json.ok(cancelled
                    .map(h -> HandoffDto.of(scope, h, true))
                    .orElseGet(() -> HandoffDto.none(scope)));
        });
    }

    /**
     * The flat JSON shape a handoff tool returns — a stable wire contract for agents, kept separate
     * from the {@link Handoff} domain record (like {@link McpDtos}). {@code present} is false when no
     * handoff was open, so {@code accept}/{@code cancel} always return a well-formed object the client
     * can branch on rather than an error.
     */
    record HandoffDto(
            McpDtos.ScopeView scope,
            boolean present,
            String id,
            String fromSession,
            String status,
            String summary,
            List<String> openQuestions,
            List<String> nextSteps,
            String createdAt,
            String acceptedAt) {

        static HandoffDto of(Scope scope, Handoff h, boolean present) {
            return new HandoffDto(
                    McpDtos.ScopeView.of(scope),
                    present,
                    h.id().toString(),
                    h.fromSession().toString(),
                    h.status().wire(),
                    h.summary(),
                    List.copyOf(h.openQuestions()),
                    List.copyOf(h.nextSteps()),
                    h.createdAt().toString(),
                    h.acceptedAt() == null ? null : h.acceptedAt().toString());
        }

        /** A freshly opened handoff (begin): present, status open. */
        static HandoffDto opened(Scope scope, Handoff h) {
            return of(scope, h, true);
        }

        /** No open handoff for the project. */
        static HandoffDto none(Scope scope) {
            return new HandoffDto(
                    McpDtos.ScopeView.of(scope), false, null, null, null, null,
                    List.of(), List.of(), null, null);
        }
    }
}
