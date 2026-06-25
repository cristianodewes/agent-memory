package com.agentmemory.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Middle coordinate of the 3-tuple identity {@code (workspace, project, path)} (invariant #4). A
 * project nests under a {@link WorkspaceId} and is the directory holding a single codebase's
 * memory (ARCHITECTURE §4.1: {@code wiki/<workspace>/<project>/...}). Projects are deliberately
 * cheap to rename / move / purge (design goal #6), which is why identity is carried as a typed
 * value everywhere rather than baked into row keys.
 *
 * <p>Serializes as a bare JSON string (its normalized slug). Same normal form as
 * {@link WorkspaceId}: trimmed, lower-cased ASCII, non-blank, single segment (no separators / NUL).
 *
 * @param value the normalized project slug; never null or blank.
 */
public record ProjectId(String value) {

    public ProjectId {
        value = WorkspaceId.normalizeSlug(value, "project");
    }

    /**
     * Jackson / call-site factory from the wire string.
     *
     * @param value raw project slug.
     * @return the normalized {@code ProjectId}.
     */
    @JsonCreator
    public static ProjectId of(String value) {
        return new ProjectId(value);
    }

    /** @return the normalized slug, serialized as a bare JSON string. */
    @JsonValue
    @Override
    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
