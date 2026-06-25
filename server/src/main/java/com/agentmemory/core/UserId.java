package com.agentmemory.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * A user identity on a shared agent-memory server (issue #39): the actor recorded in
 * {@code audit_log.actor} so a team server can attribute each observation/mutation to who produced it.
 * Single-tenant — a {@code UserId} is a server-global account name, not scoped per workspace (there is
 * no per-page RBAC in v1, Survey §2.13).
 *
 * <p>Like {@link WorkspaceId}/{@link ProjectId} this is a typed wrapper (never a loose {@link String})
 * so an actor can never be mis-recorded as some other coordinate, and it serializes as a bare JSON
 * string. Normal form mirrors the other slugs: trimmed, lower-cased ASCII, non-blank, single segment
 * (no {@code /}, {@code \} or NUL) — a username is one token, not a path.
 *
 * @param value the normalized username; never null or blank.
 */
public record UserId(String value) {

    public UserId {
        value = WorkspaceId.normalizeSlug(value, "username");
    }

    /**
     * Jackson / call-site factory from the wire string.
     *
     * @param value raw username.
     * @return the normalized {@code UserId}.
     */
    @JsonCreator
    public static UserId of(String value) {
        return new UserId(value);
    }

    /** @return the normalized username, serialized as a bare JSON string. */
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
