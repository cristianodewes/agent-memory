package com.agentmemory.mcp;

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
 */
public class ScopeResolver {

    private final McpReadRepository reads;

    public ScopeResolver(McpReadRepository reads) {
        this.reads = reads;
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
        // No explicit scope: default to the most recently active project (DD-003).
        Optional<Scope> recent = reads.mostRecentActivityScope();
        return recent.orElseThrow(() -> new ScopeUnresolvedException(
                "no workspace/project given and no recent hook activity to default from; pass "
                        + "'workspace' and 'project' explicitly"));
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
