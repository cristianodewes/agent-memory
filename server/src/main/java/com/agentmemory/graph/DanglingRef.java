package com.agentmemory.graph;

/**
 * An unresolved wikilink — one whose target page does not currently exist ({@code to_page_id IS NULL})
 * — classified by how long it has stayed that way (issue #28). The link table stores forward links
 * deferred-safe (#27): a link written before its target exists is kept and resolves the moment the
 * target appears. So an unresolved link is one of two things, distinguished here by age:
 *
 * <ul>
 *   <li><strong>deferred</strong> ({@code dangling = false}) — recent enough that the target may still
 *       legitimately appear (a forward reference to a page not written yet).</li>
 *   <li><strong>dangling</strong> ({@code dangling = true}) — older than the staleness cutoff with no
 *       target; a likely typo or a reference to a page that was never created / was deleted. This is
 *       the lint signal #28 surfaces (distinct from a benign deferred link).</li>
 * </ul>
 *
 * @param fromId        source node id {@code workspace/project/path} (the page that has the bad link).
 * @param fromWorkspace the source page's workspace slug.
 * @param fromProject   the source page's project slug.
 * @param fromPath      the source page's path.
 * @param targetId      the intended (unresolved) target id {@code workspace/project/path}.
 * @param anchor        the wikilink display text as written, or {@code null}.
 * @param ageSeconds    how long the link has been unresolved, in seconds (since {@code created_at}).
 * @param dangling      {@code true} if classified dangling (stale); {@code false} if still deferred.
 */
public record DanglingRef(
        String fromId,
        String fromWorkspace,
        String fromProject,
        String fromPath,
        String targetId,
        String anchor,
        long ageSeconds,
        boolean dangling) {
}
