package com.agentmemory.links;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses {@code [[...]]} wikilinks out of a page body into page-scoped target {@link Identity}s,
 * resolving each scope relative to the <em>source</em> page's {@code (workspace, project)} (issue
 * #27). Pure and IO-free, so the same logic serves both the writer path ({@link WikiLinkService}) and
 * #14 reindex.
 *
 * <h2>Scoped forms</h2>
 * <ul>
 *   <li>{@code [[path]]} — a page in the <strong>same project</strong> as the source.</li>
 *   <li>{@code [[project:path]]} — a page in a <strong>sibling project</strong> of the same workspace.</li>
 *   <li>{@code [[workspace/project:path]]} — a page in <strong>another workspace</strong>.</li>
 * </ul>
 * An optional display text after a pipe is supported in every form: {@code [[target|as written]]}
 * keeps {@code as written} as the link's anchor and {@code target} as the destination.
 *
 * <h2>Parsing rules</h2>
 * The scope prefix is the text up to the <em>first</em> colon; the rest is the path (so a path may
 * itself contain {@code /}, e.g. {@code concepts/recall}). A prefix with no {@code /} is a project; a
 * prefix split once on {@code /} is {@code workspace/project}. The path is normalized through
 * {@link PagePath} (the same normal form pages are stored under, so {@code [[Concepts/Recall]]}
 * matches {@code concepts/recall.md}). The workspace/project segments are normalized through their
 * value types. A link whose target is malformed (blank path, traversal, empty scope segment) is
 * <strong>skipped</strong> with a debug log rather than failing the whole page write — one bad link
 * in prose must not abort a consolidation. Duplicate targets within a page collapse to one link
 * (first anchor wins).
 */
public final class WikiLinkParser {

    private static final Logger log = LoggerFactory.getLogger(WikiLinkParser.class);

    /**
     * Matches {@code [[ ... ]]}; the inner text is captured lazily and may contain a {@code |} alias.
     * The inner class excludes {@code [} and {@code ]} so an empty {@code [[]]} does not match and an
     * unterminated {@code [[} cannot swallow following brackets to pair with a distant {@code ]]}
     * (link text never legitimately contains a raw square bracket).
     */
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[\\s*([^\\[\\]]+?)\\s*\\]\\]");

    /**
     * Parse all wikilinks in {@code body}, resolved against the source page's identity.
     *
     * @param source the page-scoped identity of the page the body belongs to; never null.
     * @param body   the markdown body (may be null/empty -> no links).
     * @return the distinct, well-formed links in source order (first occurrence's anchor kept).
     */
    public List<WikiLink> parse(Identity source, String body) {
        if (source == null || !source.isPageScoped()) {
            throw new IllegalArgumentException("source identity must be page-scoped");
        }
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        // Keyed by the target Identity (a record with value equality) so duplicate targets in one
        // body collapse to a single link, keeping the first occurrence's anchor.
        Map<Identity, WikiLink> byTarget = new LinkedHashMap<>();
        Matcher m = WIKILINK.matcher(body);
        while (m.find()) {
            WikiLink link = parseOne(source, m.group(1));
            if (link != null) {
                byTarget.putIfAbsent(link.target(), link);
            }
        }
        return new ArrayList<>(byTarget.values());
    }

    /** Parse a single inner token (without the surrounding brackets); null if malformed. */
    private WikiLink parseOne(Identity source, String inner) {
        String token = inner.trim();
        if (token.isEmpty()) {
            return null;
        }
        // Split off an optional display alias: target|display.
        String anchor = null;
        String targetSpec = token;
        int pipe = token.indexOf('|');
        if (pipe >= 0) {
            targetSpec = token.substring(0, pipe).trim();
            String display = token.substring(pipe + 1).trim();
            anchor = display.isEmpty() ? null : display;
            if (targetSpec.isEmpty()) {
                return null;
            }
        }

        try {
            Identity target = resolveTarget(source, targetSpec);
            return new WikiLink(target, anchor, token);
        } catch (IllegalArgumentException e) {
            log.debug("skipping malformed wikilink [[{}]] in {}: {}",
                    token, source.page().value(), e.getMessage());
            return null;
        }
    }

    /**
     * Resolve a target spec to a page-scoped identity using the source's coordinates as the default
     * scope. Throws {@link IllegalArgumentException} (caught by the caller) for malformed specs.
     */
    private Identity resolveTarget(Identity source, String spec) {
        int colon = spec.indexOf(':');
        if (colon < 0) {
            // [[path]] — same project as the source.
            return Identity.ofPage(source.workspace(), source.project(), PagePath.of(spec));
        }

        String scope = spec.substring(0, colon).trim();
        String rawPath = spec.substring(colon + 1).trim();
        if (scope.isEmpty() || rawPath.isEmpty()) {
            throw new IllegalArgumentException("empty scope or path");
        }
        PagePath path = PagePath.of(rawPath);

        int slash = scope.indexOf('/');
        if (slash < 0) {
            // [[project:path]] — sibling project in the same workspace.
            return Identity.ofPage(source.workspace(), ProjectId.of(scope), path);
        }
        // [[workspace/project:path]] — other workspace. Split the scope ONCE on '/'.
        String ws = scope.substring(0, slash).trim();
        String proj = scope.substring(slash + 1).trim();
        if (ws.isEmpty() || proj.isEmpty() || proj.indexOf('/') >= 0) {
            throw new IllegalArgumentException("malformed workspace/project scope: " + scope);
        }
        return Identity.ofPage(WorkspaceId.of(ws), ProjectId.of(proj), path);
    }
}
