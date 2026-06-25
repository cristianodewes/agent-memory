package com.agentmemory.wiki;

import com.agentmemory.core.PagePath;
import java.util.Locale;

/**
 * The category of a wiki page, mirroring the top-level folders consolidation writes into
 * (ARCHITECTURE §2.2: {@code sessions/ concepts/ decisions/ gotchas/ procedures/}) plus the
 * underscore-prefixed control folders ({@code _rules/ _slots/ _lint/}). Stored in the page
 * frontmatter ({@link PageFrontmatter#kind()}); the wire token is the folder name.
 *
 * <p>{@link #fromPath(PagePath)} derives the kind from the path's top folder so a freshly compiled
 * page gets the right {@code kind} for free; an unknown/blank folder maps to {@link #OTHER}.
 */
public enum PageKind {
    SESSION("sessions"),
    CONCEPT("concepts"),
    DECISION("decisions"),
    GOTCHA("gotchas"),
    PROCEDURE("procedures"),
    RULE("_rules"),
    SLOT("_slots"),
    LINT("_lint"),
    OTHER("other");

    private final String wire;

    PageKind(String wire) {
        this.wire = wire;
    }

    /** @return the wire token (the folder name) for this kind. */
    public String wire() {
        return wire;
    }

    /**
     * Derive the kind from a page path's top folder ({@code concepts/recall.md} → {@link #CONCEPT}).
     * A root-level page or unrecognized folder maps to {@link #OTHER}.
     *
     * @param path the page path; never null.
     * @return the matching kind.
     */
    public static PageKind fromPath(PagePath path) {
        String folder = path.topFolder();
        return fromWire(folder);
    }

    /**
     * Parse a wire token (folder name) to a kind, case-insensitively. Blank/unknown → {@link #OTHER}.
     *
     * @param token the folder name / wire token.
     * @return the matching kind, or {@link #OTHER}.
     */
    public static PageKind fromWire(String token) {
        if (token == null || token.isBlank()) {
            return OTHER;
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        for (PageKind k : values()) {
            if (k.wire.equals(normalized)) {
                return k;
            }
        }
        return OTHER;
    }
}
