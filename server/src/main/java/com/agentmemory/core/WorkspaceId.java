package com.agentmemory.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Top coordinate of the 3-tuple identity {@code (workspace, project, path)} (invariant #4). A
 * workspace groups related projects — typically one developer/org/laptop — and is the outermost
 * directory under {@code wiki/} (ARCHITECTURE §4.1). Resolved by the client from a
 * {@code .agent-memory.toml} marker or derived from the git root (§2.1).
 *
 * <p>This is a typed wrapper, never a loose {@link String}, so the identity tuple is impossible to
 * mis-order or leave half-populated on a domain row. It serializes as a bare JSON string (its
 * normalized value), not an object — see the contract under {@code docs/contracts/}.
 *
 * <p>Normal form: trimmed, lower-cased ASCII (case-insensitive, matching {@link PagePath}),
 * non-blank, and free of path separators / NUL (a workspace is a single directory name, not a
 * path). Equality is value equality on the normalized slug.
 *
 * @param value the normalized workspace slug; never null or blank.
 */
public record WorkspaceId(String value) {

    public WorkspaceId {
        value = normalizeSlug(value, "workspace");
    }

    /**
     * Jackson / call-site factory from the wire string. Equivalent to the canonical constructor but
     * named for readability at call sites and used as the {@link JsonCreator}.
     *
     * @param value raw workspace slug.
     * @return the normalized {@code WorkspaceId}.
     */
    @JsonCreator
    public static WorkspaceId of(String value) {
        return new WorkspaceId(value);
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

    /**
     * Shared slug rule for the workspace/project coordinates: trim, reject blanks and anything that
     * looks like a path (a slug is one directory name), lower-case ASCII for case-insensitive
     * identity. Kept here (package-private) so {@link ProjectId} reuses exactly the same rule.
     */
    static String normalizeSlug(String raw, String label) {
        if (raw == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        if (trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0 || trimmed.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    label + " must be a single segment (no '/', '\\' or NUL): " + raw);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
