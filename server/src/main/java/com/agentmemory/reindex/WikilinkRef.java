package com.agentmemory.reindex;

import com.agentmemory.core.Identity;

/**
 * One wikilink occurrence parsed out of a page body (issue #14). It pairs the intended
 * {@code target} identity with the raw {@code anchor} token exactly as written in the markdown, so a
 * {@link com.agentmemory.core.Link} row can be recorded whether or not the target page exists yet
 * (deferred-safe resolution — see {@code links} V5 and {@code core.Link}).
 *
 * <p>The parser ({@link WikilinkParser}) resolves the token against the <em>source</em> page's
 * identity: a bare {@code [[concepts/recall.md]]} names a page in the same {@code (workspace,
 * project)}; the explicit scoped form {@code [[workspace:project:path]]} names a (possibly
 * cross-project) target. The {@code target} is always page-scoped; this type is only created for
 * tokens that resolve to a concrete target path (a syntactically empty/garbage token is dropped by
 * the parser, never stored as a half-link).
 *
 * @param target the page-scoped identity this link points at (possibly cross-project); never null.
 * @param anchor the wikilink token as written between the {@code [[ ]]} (including any {@code |alias}),
 *               for display/debugging; never null.
 */
public record WikilinkRef(Identity target, String anchor) {

    public WikilinkRef {
        if (target == null || !target.isPageScoped()) {
            throw new IllegalArgumentException("wikilink target must be page-scoped (path required)");
        }
        if (anchor == null) {
            throw new IllegalArgumentException("wikilink anchor must not be null");
        }
    }
}
