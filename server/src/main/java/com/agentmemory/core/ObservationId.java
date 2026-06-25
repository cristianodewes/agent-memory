package com.agentmemory.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.UUID;

/**
 * Identifier for a single {@code Observation} — one captured agent lifecycle event (a prompt, a
 * tool call, a notification; ARCHITECTURE §4.2 {@code observations}). Backed by a {@link UUID},
 * minted as <strong>UUIDv7</strong> ({@link Uuid7}) so the observations log is naturally
 * time-ordered by primary key.
 *
 * <p>Distinct type from {@link SessionId} so the two cannot be confused at a call site. Serializes
 * as the canonical lowercase 8-4-4-4-12 UUID string.
 *
 * @param value the underlying UUID; never null.
 */
public record ObservationId(UUID value) {

    public ObservationId {
        if (value == null) {
            throw new IllegalArgumentException("observation id must not be null");
        }
    }

    /** @return a fresh, time-ordered observation id (UUIDv7). */
    public static ObservationId newId() {
        return new ObservationId(Uuid7.randomUuid());
    }

    /**
     * Jackson / call-site factory parsing the canonical UUID string.
     *
     * @param value the 8-4-4-4-12 UUID text.
     * @return the parsed {@code ObservationId}.
     * @throws IllegalArgumentException if {@code value} is not a valid UUID.
     */
    @JsonCreator
    public static ObservationId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("observation id must not be null");
        }
        return new ObservationId(UUID.fromString(value));
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
