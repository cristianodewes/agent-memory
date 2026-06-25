package com.agentmemory.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Innermost coordinate of the 3-tuple identity {@code (workspace, project, path)} (invariant #4):
 * the path of a wiki page <em>relative to its project root</em>, e.g. {@code concepts/recall.md} or
 * {@code sessions/2026-06-25.md}. Carrying this as a typed value (not a raw {@link String}) is what
 * makes "the same page" a single key across hooks, the API and the store.
 *
 * <p>The raw input is run through {@link PathNormalizer} (separators, slash collapsing, {@code .md}
 * suffix, ASCII lower-casing, traversal rejection — see that class for the full rule set), so any
 * accepted {@code PagePath} is already in canonical, byte-stable normal form. It serializes as a
 * bare JSON string of that normal form.
 *
 * @param value the normalized, project-root-relative page path; always ends in {@code .md}.
 */
public record PagePath(String value) {

    public PagePath {
        value = PathNormalizer.normalize(value);
    }

    /**
     * Jackson / call-site factory from the wire string. Normalizes via {@link PathNormalizer}.
     *
     * @param value raw page path (any separator style, with or without {@code .md}).
     * @return the normalized {@code PagePath}.
     */
    @JsonCreator
    public static PagePath of(String value) {
        return new PagePath(value);
    }

    /** @return the normalized path, serialized as a bare JSON string. */
    @JsonValue
    @Override
    public String value() {
        return value;
    }

    /**
     * @return the final segment (file name) of the path, e.g. {@code recall.md} for
     *         {@code concepts/recall.md}.
     */
    public String fileName() {
        int slash = value.lastIndexOf('/');
        return slash < 0 ? value : value.substring(slash + 1);
    }

    /**
     * @return the top-level folder of the path (e.g. {@code concepts}, {@code sessions}), or an
     *         empty string when the page sits at the project root.
     */
    public String topFolder() {
        int slash = value.indexOf('/');
        return slash < 0 ? "" : value.substring(0, slash);
    }

    @Override
    public String toString() {
        return value;
    }
}
