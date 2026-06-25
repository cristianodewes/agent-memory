package com.agentmemory.graph;

import java.util.List;

/**
 * A page of the unified dependency graph (issue #28): the resolved {@link GraphEdge edges} in the
 * requested window, the {@link GraphNode nodes} they touch (so the returned subgraph is
 * self-contained — every edge endpoint is present as a node), and the cursor metadata for paging
 * through a large store. {@code totalEdges} is the full count of resolved edges matching the query
 * (across all pages), so a client knows whether more remain.
 *
 * <p>The graph is paged by its <strong>edges</strong> (the graph is fundamentally its edge set);
 * {@code nodes} is exactly the distinct set of endpoints of {@code edges}, not an independent page.
 *
 * @param nodes      the distinct page nodes referenced by {@code edges} (never null).
 * @param edges      the resolved edges in this page, deterministically ordered (never null).
 * @param limit      the edge page size that was applied.
 * @param offset     the zero-based offset of the first edge in this page.
 * @param totalEdges the total number of resolved edges matching the query, across all pages.
 */
public record DependencyGraph(
        List<GraphNode> nodes, List<GraphEdge> edges, int limit, int offset, long totalEdges) {

    public DependencyGraph {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}
