package com.agentmemory.recall;

import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;

/**
 * The {@code (workspace, project)} coordinate a recall query runs against. Issue #15 handles the
 * single-project case: the caller resolves "the current project" (from the MCP session / request,
 * #17) and passes it explicitly here. Multi-scope fan-out and {@code global=true} search across
 * sibling projects are a later concern (#29) and intentionally not modeled at this layer — keeping
 * the type a plain pair means #29 can wrap it (e.g. a {@code List<Scope>}) without reshaping recall.
 *
 * @param workspace the workspace coordinate; never null.
 * @param project   the project coordinate; never null.
 */
public record Scope(WorkspaceId workspace, ProjectId project) {

    public Scope {
        if (workspace == null) {
            throw new IllegalArgumentException("scope.workspace must not be null");
        }
        if (project == null) {
            throw new IllegalArgumentException("scope.project must not be null");
        }
    }

    /**
     * Convenience factory from raw slugs (normalized by the {@code core} value types).
     *
     * @param workspace workspace slug.
     * @param project   project slug.
     * @return the scope pair.
     */
    public static Scope of(String workspace, String project) {
        return new Scope(WorkspaceId.of(workspace), ProjectId.of(project));
    }

    /** @return the workspace slug. */
    public String workspaceSlug() {
        return workspace.value();
    }

    /** @return the project slug. */
    public String projectSlug() {
        return project.value();
    }
}
