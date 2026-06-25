package com.agentmemory.store;

import com.agentmemory.core.Identity;
import com.agentmemory.core.MemoryLayer;
import com.agentmemory.core.Page;
import com.agentmemory.core.PageId;
import java.time.Instant;

/**
 * The store-side view of one persisted {@code pages} row: the domain {@link Page} plus the
 * decay-reinforcement access columns and the retention {@link MemoryLayer} the {@code core} record
 * deliberately omits ({@code access_count} / {@code last_accessed_at} / {@code layer} — see
 * {@link Page}'s class doc and ARCHITECTURE §4.2/§3.3). #24 reads and bumps the access columns and
 * carries the layer; #12 only initializes the access columns (to {@code 0} / {@code null}).
 *
 * <p>Keeping the domain {@link Page} embedded (rather than re-flattening its fields) means callers
 * that only care about identity/content/version use {@link #page()} and the wire contract stays the
 * single source of truth for those fields; the store-only columns live alongside it here.
 *
 * @param page          the domain page (identity, title, body, version chain, timestamps).
 * @param layer         the retention layer this page is classified into (#24); never null.
 * @param accessCount   recall hit counter; {@code 0} for a freshly created version.
 * @param lastAccessedAt last recall access, or {@code null} until first accessed (#24).
 */
public record PageRecord(Page page, MemoryLayer layer, long accessCount, Instant lastAccessedAt) {

    public PageRecord {
        if (page == null) {
            throw new IllegalArgumentException("pageRecord.page must not be null");
        }
        if (layer == null) {
            throw new IllegalArgumentException("pageRecord.layer must not be null");
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

    /**
     * Current retention score of this page under the given scorer — the value recall ranking (#15)
     * and the forget sweep (#25) sort/threshold on. Convenience over
     * {@link RetentionScorer#score(MemoryLayer, long, Instant, Instant)} that supplies this record's
     * layer, access count, and timestamps.
     *
     * @param scorer the shared decay scorer; never null.
     * @return the retention score ({@code >= 0}).
     */
    public double retentionScore(RetentionScorer scorer) {
        if (scorer == null) {
            throw new IllegalArgumentException("scorer must not be null");
        }
        return scorer.score(layer, accessCount, page.createdAt(), lastAccessedAt);
    }
}
