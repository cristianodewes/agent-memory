package com.agentmemory.graph;

import com.agentmemory.core.Identity;

/**
 * One node in the unified cross-project dependency graph (issue #28): a single latest wiki page,
 * addressed by a globally-unique {@link #id} of the form {@code workspace/project/path}. Carrying the
 * three identity coordinates plus the {@code id} lets a frontend group nodes by workspace/project
 * while still keying edges by one stable string. The {@code title} is the page's current title.
 *
 * @param id        globally-unique node id {@code workspace/project/path} (never null).
 * @param workspace the page's workspace slug.
 * @param project   the page's project slug.
 * @param path      the page's project-relative path.
 * @param title     the page's current title.
 */
public record GraphNode(String id, String workspace, String project, String path, String title) {

    /** Build the stable node id {@code workspace/project/path} for a page-scoped identity. */
    public static String idOf(Identity identity) {
        return identity.workspace().value() + "/" + identity.project().value() + "/"
                + identity.page().value();
    }
}
