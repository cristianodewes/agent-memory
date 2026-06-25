package com.agentmemory.wiki;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The YAML frontmatter contract for a wiki page (issue #13 scope). Every markdown page in {@code
 * wiki/} carries this header block so the file is self-describing — the source of truth (DD-002)
 * does not depend on the Postgres index to know a page's identity, kind, or timestamps. The watcher
 * parses it back when reconciling an external edit.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code title} — human title (required).</li>
 *   <li>{@code kind} — the page category ({@link PageKind}); defaults from the path's top folder.</li>
 *   <li>{@code pinned} — whether the page is exempt from retention sweeps (#24); defaults false.</li>
 *   <li>{@code slot_kind} — optional slot classifier for {@code _slots/} pages (nullable).</li>
 *   <li>{@code workspace} / {@code project} / {@code path} — the 3-tuple identity (invariant #4),
 *       so the file alone reconstructs its {@link Identity}.</li>
 *   <li>{@code created_at} / {@code updated_at} — RFC-3339 UTC instants.</li>
 * </ul>
 *
 * <p>Serialized as a flat {@code key: value} block (see {@link MarkdownDocument}); values are
 * single-line and quoted defensively. This is intentionally a tiny, strict subset of YAML rather
 * than a full YAML dependency — pages are machine-written and the parser rejects anything it does
 * not recognize cleanly.
 */
public record PageFrontmatter(
        String title,
        PageKind kind,
        boolean pinned,
        String slotKind,
        WorkspaceId workspace,
        ProjectId project,
        PagePath path,
        Instant createdAt,
        Instant updatedAt) {

    public PageFrontmatter {
        if (title == null) {
            throw new IllegalArgumentException("frontmatter.title must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("frontmatter.kind must not be null");
        }
        if (workspace == null || project == null || path == null) {
            throw new IllegalArgumentException("frontmatter identity (workspace/project/path) required");
        }
        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("frontmatter timestamps must not be null");
        }
    }

    /** @return the page-scoped 3-tuple {@link Identity} this frontmatter describes. */
    public Identity identity() {
        return Identity.ofPage(workspace, project, path);
    }

    /**
     * The ordered frontmatter keys as written to the file. {@code slot_kind} is omitted when null
     * (absent ⇔ null, matching the domain wire contract).
     *
     * @return an insertion-ordered map of frontmatter key → string value.
     */
    public Map<String, String> toOrderedMap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("kind", kind.wire());
        m.put("pinned", Boolean.toString(pinned));
        if (slotKind != null) {
            m.put("slot_kind", slotKind);
        }
        m.put("workspace", workspace.value());
        m.put("project", project.value());
        m.put("path", path.value());
        m.put("created_at", createdAt.toString());
        m.put("updated_at", updatedAt.toString());
        return m;
    }
}
