package com.agentmemory.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.UUID;

/**
 * Surrogate identifier for a {@link Handoff} row (ARCHITECTURE §4.2 {@code handoffs}). Backed by a
 * {@link UUID}, minted as <strong>UUIDv7</strong> ({@link Uuid7}). Distinct type from the other id
 * wrappers. Serializes as the canonical lowercase UUID string.
 *
 * @param value the underlying UUID; never null.
 */
public record HandoffId(UUID value) {

    public HandoffId {
        if (value == null) {
            throw new IllegalArgumentException("handoff id must not be null");
        }
    }

    /** @return a fresh, time-ordered handoff id (UUIDv7). */
    public static HandoffId newId() {
        return new HandoffId(Uuid7.randomUuid());
    }

    /**
     * Jackson / call-site factory parsing the canonical UUID string.
     *
     * @param value the 8-4-4-4-12 UUID text.
     * @return the parsed {@code HandoffId}.
     * @throws IllegalArgumentException if {@code value} is not a valid UUID.
     */
    @JsonCreator
    public static HandoffId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("handoff id must not be null");
        }
        return new HandoffId(UUID.fromString(value));
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
