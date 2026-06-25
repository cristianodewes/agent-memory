package com.agentmemory.links;

/**
 * A page related to another by a link, as seen from one side of the edge (issue #27). Used for both
 * <strong>backlinks</strong> (pages whose body links <em>to</em> a target) and outgoing links (the
 * resolved destinations a page points at). It deliberately carries the related page's
 * {@code (workspace, project)} so a cross-project relationship is visible at a glance — a backlink
 * from a sibling project or another workspace is not silently flattened to look local.
 *
 * @param workspace the related page's workspace slug; never null.
 * @param project   the related page's project slug; never null.
 * @param path      the related page's path; never null.
 * @param title     the related page's title, or {@code null} if the page is not (yet) resolved.
 * @param anchor    the wikilink display text as written on the linking side, or {@code null}.
 * @param crossProject whether this related page is in a different {@code (workspace, project)} than
 *     the page it is related to.
 */
public record RelatedPage(
        String workspace,
        String project,
        String path,
        String title,
        String anchor,
        boolean crossProject) {
}
