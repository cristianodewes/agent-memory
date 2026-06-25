package com.agentmemory.links;

import com.agentmemory.core.Identity;

/**
 * One parsed wikilink: the page-scoped {@link #target} identity it points at (resolved relative to
 * the source page's {@code (workspace, project)}), the optional display {@link #anchor} text, and the
 * raw {@link #token} as written in the markdown (for diagnostics). Produced by {@link WikiLinkParser}
 * and consumed by {@link WikiLinkService} (and by #14 reindex, which reuses the same parse).
 *
 * <p>The target is always page-scoped (it names a specific path) and may be cross-project — its
 * {@code (workspace, project)} can differ from the source's. Whether the target page actually exists
 * yet is <em>not</em> decided here; resolution to a concrete page id (or a deferred {@code NULL})
 * happens in {@link WikiLinkService} against the store.
 *
 * @param target the page-scoped target identity (possibly cross-project); never null.
 * @param anchor the display text after a {@code |}, or {@code null} when the link had none.
 * @param token  the raw inner token as written (e.g. {@code project:concepts/recall}); never null.
 */
public record WikiLink(Identity target, String anchor, String token) {

    public WikiLink {
        if (target == null || !target.isPageScoped()) {
            throw new IllegalArgumentException("wikilink target must be page-scoped (path required)");
        }
        if (token == null) {
            throw new IllegalArgumentException("wikilink token must not be null");
        }
    }
}
