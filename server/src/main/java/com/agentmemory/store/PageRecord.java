package com.agentmemory.store;

import com.agentmemory.core.Identity;
import com.agentmemory.core.Page;
import com.agentmemory.core.PageId;
import java.time.Instant;

/**
 * The store-side view of one persisted {@code pages} row: the domain {@link Page} plus the
 * decay-reinforcement access columns the {@code core} record deliberately omits ({@code access_count}
 * / {@code last_accessed_at} — see {@link Page}'s class doc and ARCHITECTURE §4.2). #24 reads and
 * bumps those columns; #12 only initializes them (to {@code 0} / {@code null}) and reports them.
 *
 * <p>Keeping the domain {@link Page} embedded (rather than re-flattening its fields) means callers
 * that only care about identity/content/version use {@link #page()} and the wire contract stays the
 * single source of truth for those fields; the two extra columns live alongside it here.
 *
 * @param page          the domain page (identity, title, body, version chain, timestamps).
 * @param accessCount   recall hit counter; {@code 0} for a freshly created version.
 * @param lastAccessedAt last recall access, or {@code null} until first accessed (#24).
 */
public record PageRecord(Page page, long accessCount, Instant lastAccessedAt) {

    public PageRecord {
        if (page == null) {
            throw new IllegalArgumentException("pageRecord.page must not be null");
        }
        if (accessCount < 0) {
            throw new IllegalArgumentException("pageRecord.accessCount must not be negative");
        }
    }

    /** @return this page version's id (UUIDv7). */
    public PageId id() {
        return page.id();
    }

    /** @return the page-scoped 3-tuple identity. */
    public Identity identity() {
        return page.identity();
    }

    /** @return {@code true} if this is the current (non-superseded) version. */
    public boolean isLatest() {
        return page.isLatest();
    }
}
