package com.agentmemory.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.UUID;

/**
 * Surrogate identifier for a single <em>version</em> of a {@link Page}. The page's human identity
 * is its {@link PagePath} within a project, but the schema's {@code is_latest}/{@code supersedes}
 * version chain needs a stable per-version key (ARCHITECTURE §4.2 {@code pages}); that is this id.
 * Backed by a {@link UUID}, minted as <strong>UUIDv7</strong> ({@link Uuid7}).
 *
 * <p>Not in the issue's headline value-type list, but a direct consequence of modeling the
 * versioned {@code pages} table faithfully: {@code supersedes} must point at <em>a version</em>,
 * not a path. Distinct type from {@link SessionId}/{@link ObservationId}. Serializes as the
 * canonical lowercase UUID string.
 *
 * @param value the underlying UUID; never null.
 */
public record PageId(UUID value) {

    public PageId {
        if (value == null) {
            throw new IllegalArgumentException("page id must not be null");
        }
    }

    /** @return a fresh, time-ordered page-version id (UUIDv7). */
    public static PageId newId() {
        return new PageId(Uuid7.randomUuid());
    }

    /**
     * Jackson / call-site factory parsing the canonical UUID string.
     *
     * @param value the 8-4-4-4-12 UUID text.
     * @return the parsed {@code PageId}.
     * @throws IllegalArgumentException if {@code value} is not a valid UUID.
     */
    @JsonCreator
    public static PageId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("page id must not be null");
        }
        return new PageId(UUID.fromString(value));
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
