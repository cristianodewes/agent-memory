package com.agentmemory.reindex;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts wikilinks from a page body so reindex can rebuild the {@code links} graph (issue #14;
 * ARCHITECTURE §3.3, §4.2). Wikilinks are written {@code [[target]]} or {@code [[target|alias]]}; the
 * {@code target} resolves against the <em>source</em> page's identity:
 *
 * <ul>
 *   <li><strong>Same project</strong> — {@code [[concepts/recall.md]]} (a project-root-relative page
 *       path, normalized by {@code core.PagePath}). The {@code .md} suffix is optional in the source
 *       text; {@link PagePath} adds it.</li>
 *   <li><strong>Explicit scope</strong> — {@code [[workspace:project:concepts/recall.md]]} names a
 *       (possibly cross-project) target. Exactly two leading {@code :}-separated segments are read as
 *       {@code workspace} and {@code project}; everything after the second colon is the page path
 *       (so a path may itself contain no further constraints).</li>
 * </ul>
 *
 * <p>The parser is deliberately small and dependency-free (no markdown AST): pages are
 * machine-written, and a regex over {@code [[...]]} is robust enough while staying fast on the
 * reindex hot path. It is <em>defensive</em> — a token whose path is empty or that fails
 * {@code PagePath} normalization (e.g. a traversal attempt {@code ../../etc}) is skipped with a debug
 * log rather than producing a corrupt link. Duplicate targets within one body are de-duplicated
 * (a page linking the same target twice yields one edge), preserving first-seen order.
 *
 * <p>Fenced/inline code is <em>not</em> stripped: a {@code [[token]]} inside a code block is still
 * recorded. That is intentional for v1 (keeps the parser trivial and deterministic); the cost is a
 * spurious edge only if a code sample literally contains wikilink syntax, which never affects FTS
 * ranking and is harmless to the graph arm.
 */
public final class WikilinkParser {

    private static final Logger log = LoggerFactory.getLogger(WikilinkParser.class);

    /**
     * Matches a non-greedy {@code [[ ... ]]} span. The inner group excludes {@code ]} so {@code ]]}
     * cannot be straddled and nested brackets do not over-match.
     */
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]]+?)]]");

    /**
     * Parse every wikilink in {@code body}, resolving each against {@code source}.
     *
     * @param source the page-scoped identity of the page whose body this is; never null.
     * @param body   the markdown body (may be empty/null → no links).
     * @return the distinct, in-order links found (possibly empty); never null.
     */
    public List<WikilinkRef> parse(Identity source, String body) {
        if (source == null || !source.isPageScoped()) {
            throw new IllegalArgumentException("wikilink source must be page-scoped (path required)");
        }
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        List<WikilinkRef> out = new ArrayList<>();
        Set<Identity> seen = new LinkedHashSet<>();
        Matcher m = WIKILINK.matcher(body);
        while (m.find()) {
            String raw = m.group(1).trim();
            if (raw.isEmpty()) {
                continue;
            }
            // The display text after the first '|' is an alias, not part of the target.
            String token = raw;
            int pipe = raw.indexOf('|');
            if (pipe >= 0) {
                token = raw.substring(0, pipe).trim();
            }
            if (token.isEmpty()) {
                continue;
            }
            Identity target = resolveTarget(source, token);
            if (target == null) {
                continue; // malformed/unsafe token already logged; never store a half-link
            }
            if (seen.add(target)) {
                out.add(new WikilinkRef(target, raw));
            }
        }
        return out;
    }

    /**
     * Resolve a single wikilink token to a page-scoped target identity, or {@code null} if it cannot
     * be turned into a safe page path.
     */
    private Identity resolveTarget(Identity source, String token) {
        String workspace = source.workspace().value();
        String project = source.project().value();
        String pathPart = token;

        // Explicit scope form workspace:project:path — split on the first two colons only, so a path
        // segment is never mistaken for a scope and a windows-style drive is impossible (paths are
        // project-root-relative). A single leading colon (":path") or trailing-only colon is treated
        // as a malformed scope and rejected.
        int firstColon = token.indexOf(':');
        if (firstColon >= 0) {
            int secondColon = token.indexOf(':', firstColon + 1);
            if (secondColon < 0) {
                log.debug("skipping wikilink with single-colon (ambiguous scope): [[{}]]", token);
                return null;
            }
            String ws = token.substring(0, firstColon).trim();
            String proj = token.substring(firstColon + 1, secondColon).trim();
            pathPart = token.substring(secondColon + 1).trim();
            if (ws.isEmpty() || proj.isEmpty() || pathPart.isEmpty()) {
                log.debug("skipping wikilink with empty scope component: [[{}]]", token);
                return null;
            }
            workspace = ws;
            project = proj;
        }

        try {
            return Identity.ofPage(
                    WorkspaceId.of(workspace), ProjectId.of(project), PagePath.of(pathPart));
        } catch (RuntimeException e) {
            // PathNormalizer rejects traversal / illegal paths; WorkspaceId/ProjectId reject bad slugs.
            log.debug("skipping unresolvable wikilink [[{}]]: {}", token, e.getMessage());
            return null;
        }
    }
}
