package com.agentmemory.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Lifecycle state of a {@link Handoff} (ARCHITECTURE §4.2 {@code handoffs}; §3.4). A handoff is
 * <strong>single-use</strong>: written {@link #OPEN} at session end, fetched-and-acked to
 * {@link #ACCEPTED} at the next session start, or {@link #EXPIRED} if cancelled / superseded before
 * it is consumed.
 *
 * <p>Wire form is the lowercase token ({@code "open"}, {@code "accepted"}, {@code "expired"}). Parse
 * is strict — unlike {@link ObservationKind}, an unknown handoff status is a data error worth
 * surfacing — so {@link #fromWire(String)} throws on an unrecognized token.
 */
public enum HandoffStatus {

    /** Written and awaiting consumption by the next session. */
    OPEN("open"),

    /** Fetched and acknowledged by a session start; cannot be consumed again. */
    ACCEPTED("accepted"),

    /** Cancelled or superseded before consumption. */
    EXPIRED("expired");

    private final String wire;

    HandoffStatus(String wire) {
        this.wire = wire;
    }

    /** @return the lowercase wire token. */
    @JsonValue
    public String wire() {
        return wire;
    }

    /**
     * Strict parse from the wire token (case-insensitive).
     *
     * @param token the wire status.
     * @return the matching status.
     * @throws IllegalArgumentException if {@code token} is null/blank or unrecognized.
     */
    @JsonCreator
    public static HandoffStatus fromWire(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("handoff status must not be blank");
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        for (HandoffStatus status : values()) {
            if (status.wire.equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("unknown handoff status: " + token);
    }
}
