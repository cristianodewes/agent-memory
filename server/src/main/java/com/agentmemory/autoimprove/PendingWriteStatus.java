package com.agentmemory.autoimprove;

import java.util.Locale;

/**
 * The approval-gate lifecycle of a {@code pending_writes} row (V8 / issue #30): a proposal is
 * {@link #PROPOSED}, then either {@link #APPLIED} (written through the normal write path — directly when
 * approval isn't required, or after a human approves) or {@link #REJECTED}. {@link #APPROVED} is the
 * intermediate "approved but not yet applied" state for the held path. The string values match the V8
 * {@code pending_writes_status_valid} CHECK constraint.
 */
public enum PendingWriteStatus {
    PROPOSED("proposed"),
    APPROVED("approved"),
    REJECTED("rejected"),
    APPLIED("applied");

    private final String db;

    PendingWriteStatus(String db) {
        this.db = db;
    }

    /** @return the lowercase token stored in {@code pending_writes.status}. */
    public String db() {
        return db;
    }

    /**
     * @param value a {@code pending_writes.status} token.
     * @return the matching status.
     * @throws IllegalArgumentException if no status matches.
     */
    public static PendingWriteStatus fromDb(String value) {
        if (value != null) {
            String v = value.strip().toLowerCase(Locale.ROOT);
            for (PendingWriteStatus s : values()) {
                if (s.db.equals(v)) {
                    return s;
                }
            }
        }
        throw new IllegalArgumentException("unknown pending_writes status: " + value);
    }
}
