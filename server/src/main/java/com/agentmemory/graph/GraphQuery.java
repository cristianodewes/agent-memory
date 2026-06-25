package com.agentmemory.graph;

import com.agentmemory.recall.Scope;

/**
 * The inputs to a unified-graph read (issue #28): an optional {@code scope} filter and the edge
 * pagination window.
 *
 * <p>By default ({@code scope == null}) the graph is <strong>cross-project</strong> — every resolved
 * link in the store. When a {@code scope} is given, the result is narrowed to edges that <em>touch</em>
 * that project (the page on either end lives in it), so a client can ask "show the graph around this
 * project" — including the cross-project edges that leave or enter it — without pulling the whole
 * store.
 *
 * @param scope  the project to narrow to, or {@code null} for the full cross-project graph.
 * @param limit  max edges to return (bounded by the caller; the graph is paged by edges).
 * @param offset zero-based edge offset.
 */
public record GraphQuery(Scope scope, int limit, int offset) {

    public GraphQuery {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
    }

    /** The full cross-project graph, paged. */
    public static GraphQuery crossProject(int limit, int offset) {
        return new GraphQuery(null, limit, offset);
    }

    /** The graph narrowed to edges touching {@code scope}, paged. */
    public static GraphQuery scoped(Scope scope, int limit, int offset) {
        return new GraphQuery(scope, limit, offset);
    }
}
