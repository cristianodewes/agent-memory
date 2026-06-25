package com.agentmemory.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.UUID;

/**
 * Identifier for a capture {@code Session} — one agent run, the unit hooks group observations
 * under (ARCHITECTURE §4.2 {@code sessions}). Backed by a {@link UUID}, minted as
 * <strong>UUIDv7</strong> ({@link Uuid7}) so ids sort by creation time and keep the sessions index
 * append-friendly.
 *
 * <p>A typed wrapper rather than a bare {@link UUID}/{@link String} so a session id can never be
 * passed where an {@link ObservationId} is expected. Serializes as the canonical lowercase
 * 8-4-4-4-12 UUID string (Jackson renders {@link UUID} that way), e.g.
 * {@code "018f9c2a-7b3e-7c00-8a1d-2b6f4e9c1a55"}.
 *
 * @param value the underlying UUID; never null.
 */
public record SessionId(UUID value) {

    public SessionId {
        if (value == null) {
            throw new IllegalArgumentException("session id must not be null");
        }
    }

    /** @return a fresh, time-ordered session id (UUIDv7). */
    public static SessionId newId() {
        return new SessionId(Uuid7.randomUuid());
    }

    /**
     * Jackson / call-site factory parsing the canonical UUID string.
     *
     * @param value the 8-4-4-4-12 UUID text.
     * @return the parsed {@code SessionId}.
     * @throws IllegalArgumentException if {@code value} is not a valid UUID.
     */
    @JsonCreator
    public static SessionId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("session id must not be null");
        }
        return new SessionId(UUID.fromString(value));
    }

    /** @return the canonical UUID string, serialized as a bare JSON string. */
    @JsonValue
    public String asString() {
        return value.toString();
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
