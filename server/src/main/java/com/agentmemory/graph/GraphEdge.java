package com.agentmemory.graph;

/**
 * One directed edge in the unified dependency graph (issue #28): a wikilink from a source page to a
 * target page. The endpoints are the {@code workspace/project/path} node ids ({@link GraphNode#id}).
 *
 * <p>Only <strong>resolved</strong> links are edges in the graph proper — an edge's {@code to} always
 * names a page that currently exists. Unresolved links (deferred or dangling) are reported separately
 * ({@link DanglingRef}) rather than as edges to a non-existent node, so the node/edge set stays
 * internally consistent (every edge endpoint is a real node).
 *
 * @param from         source node id {@code workspace/project/path} (never null).
 * @param to           target node id {@code workspace/project/path} (never null; the target exists).
 * @param anchor       the wikilink display text as written, or {@code null}.
 * @param crossProject whether {@code from} and {@code to} live in different {@code (workspace,
 *     project)} — the cross-project relationships #28 makes visible.
 */
public record GraphEdge(String from, String to, String anchor, boolean crossProject) {
}
