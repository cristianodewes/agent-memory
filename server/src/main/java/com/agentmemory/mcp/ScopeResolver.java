package com.agentmemory.mcp;

import com.agentmemory.config.AutoScope;
import com.agentmemory.core.ActorResolver;
import com.agentmemory.core.CaptureSessionResolver;
import com.agentmemory.recall.Scope;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the {@code (workspace, project)} an MCP tool call operates on (DD-003). A caller may pass
 * {@code workspace} + {@code project} explicitly (a cross-project query); otherwise the scope
 * defaults to the <em>most recently active</em> project from hook capture — the session's "current
 * project" without the agent having to name it. (Multi-scope {@code scopes} / {@code global} fan-out
 * is #29; this resolver handles the single-project default + explicit override the read tools need.)
 *
 * <p>If {@code workspace} is given, {@code project} must be too (and vice-versa) — a half-specified
 * scope is a usage error, surfaced to the tool as a {@link ScopeUnresolvedException} so it returns a
 * clear tool error rather than guessing.
 *
 * <h2>auto_scope (issue #39)</h2>
 * On a shared server the implicit default is isolated per the configured {@link AutoScope} mode:
 * <ul>
 *   <li>{@link AutoScope#SINGLE_SLOT} (default) — the server's globally most-recent project (unchanged
 *       single-user behavior).</li>
 *   <li>{@link AutoScope#PER_ACTOR} — the most-recent project of the <em>authenticated</em> caller
 *       ({@link ActorResolver}, read on this MCP request thread). With no authenticated actor it falls
 *       back to the global default, so loopback / single-user is unaffected.</li>
 *   <li>{@link AutoScope#SESSION_AWARE} (issue #87) — the most-recent project of <em>this capture
 *       session</em> ({@link CaptureSessionResolver}, from the {@code X-Agent-Memory-Session} request
 *       header), so two sessions of the same user resolve to their own lanes. If no session id reached
 *       the request it <strong>fail-fasts</strong> ({@link ScopeUnresolvedException}) — never a silent
 *       fall-back to the global scope, which on a shared server would leak activity across sessions.</li>
 * </ul>
 * An explicit {@code workspace}+{@code project} always wins regardless of mode.
 */
public class ScopeResolver {

    private final McpReadRepository reads;
    private final AutoScope autoScope;
    private final ActorResolver actors;
    private final CaptureSessionResolver sessions;

    /** Default resolver: {@link AutoScope#SINGLE_SLOT}, no actor/session (single-user / tests). */
    public ScopeResolver(McpReadRepository reads) {
        this(reads, AutoScope.SINGLE_SLOT, ActorResolver.NONE, CaptureSessionResolver.NONE);
    }

    /** Back-compat constructor (no session resolver): {@link AutoScope#SESSION_AWARE} then fail-fasts. */
    public ScopeResolver(McpReadRepository reads, AutoScope autoScope, ActorResolver actors) {
        this(reads, autoScope, actors, CaptureSessionResolver.NONE);
    }

    /**
     * @param reads     the activity-scope reads.
     * @param autoScope the auto_scope isolation mode (#39/#87); {@code null} ⇒ {@link AutoScope#SINGLE_SLOT}.
     * @param actors    resolves the authenticated caller for {@link AutoScope#PER_ACTOR}; {@code null} ⇒
     *     {@link ActorResolver#NONE} (never attributes, i.e. always the global default).
     * @param sessions  resolves the capture session id for {@link AutoScope#SESSION_AWARE}; {@code null} ⇒
     *     {@link CaptureSessionResolver#NONE} (no session ⇒ session_aware fail-fasts, never global).
     */
    public ScopeResolver(McpReadRepository reads, AutoScope autoScope, ActorResolver actors,
            CaptureSessionResolver sessions) {
        this.reads = reads;
        this.autoScope = (autoScope == null) ? AutoScope.SINGLE_SLOT : autoScope;
        this.actors = (actors == null) ? ActorResolver.NONE : actors;
        this.sessions = (sessions == null) ? CaptureSessionResolver.NONE : sessions;
    }

    /**
     * Resolve the scope for a tool call from its argument map.
     *
     * @param args the tool arguments (may contain {@code workspace} / {@code project}); never null.
     * @return the resolved scope.
     * @throws ScopeUnresolvedException if the scope is half-specified, the slugs are invalid, or no
     *     scope was given and no hook activity exists to default from.
     */
    public Scope resolve(Map<String, Object> args) {
        String workspace = str(args.get("workspace"));
        String project = str(args.get("project"));

        boolean hasWs = workspace != null && !workspace.isBlank();
        boolean hasProj = project != null && !project.isBlank();

        if (hasWs ^ hasProj) {
            throw new ScopeUnresolvedException(
                    "specify both 'workspace' and 'project', or neither (to use the most recently "
                            + "active project)");
        }
        if (hasWs) {
            try {
                return Scope.of(workspace, project);
            } catch (IllegalArgumentException e) {
                throw new ScopeUnresolvedException("invalid scope: " + e.getMessage());
            }
        }
        // No explicit scope: default to the most recently active project (DD-003), isolated per the
        // auto_scope mode. SINGLE_SLOT is the global most-recent; PER_ACTOR restricts to the
        // authenticated caller's own activity; SESSION_AWARE restricts to THIS capture session (and
        // fail-fasts when no session id reached the request — never silently widening to global).
        Optional<Scope> recent = switch (autoScope) {
            case SINGLE_SLOT -> reads.mostRecentActivityScope(null);
            case PER_ACTOR -> reads.mostRecentActivityScope(actors.currentActor());
            case SESSION_AWARE -> reads.mostRecentActivityScopeForSession(requireSessionId());
        };
        return recent.orElseThrow(() -> new ScopeUnresolvedException(
                "no workspace/project given and no recent hook activity to default from; pass "
                        + "'workspace' and 'project' explicitly"));
    }

    /**
     * The capture session id for a {@link AutoScope#SESSION_AWARE} resolution, or fail-fast (issue #87
     * central guard): if no session id reached the boundary (the MCP request carried no
     * {@code X-Agent-Memory-Session} header) we NEVER widen to the global scope — that would leak one
     * session's default project to another on a shared server — but raise a clear, actionable error.
     */
    private String requireSessionId() {
        String sessionId = sessions.currentSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new ScopeUnresolvedException(
                    "auto_scope 'session_aware' requires the capture session id, but this request "
                            + "carried no '" + CaptureSessionResolver.SESSION_HEADER + "' header; pass "
                            + "'workspace' and 'project' explicitly, or configure the agent-memory MCP "
                            + "client to send the session header.");
        }
        return sessionId;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** Raised when a tool call's scope cannot be determined; the tool maps it to a tool error. */
    public static final class ScopeUnresolvedException extends RuntimeException {
        public ScopeUnresolvedException(String message) {
            super(message);
        }
    }
}
