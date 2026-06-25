package com.agentmemory.hooks;

/**
 * The disposition of a single hook event offered to {@link IngestService} (issue #8). A batch
 * reports one of these <em>per item</em> so one bad or throttled event never fails the whole drain
 * (the documented prior-art "batch drain stalls on one bad event" bug is designed out: the batch
 * loop classifies each item independently).
 */
public enum IngestStatus {

    /** The event was validated, sanitized, and enqueued for the single writer. Maps to HTTP 202. */
    ACCEPTED,

    /**
     * The event was well-formed but the bounded ingest queue was saturated, so it was rejected
     * rather than enqueued (backpressure — invariant #5; never grow unbounded). Maps to HTTP 429;
     * the client should retry later (its spool still holds the event).
     */
    THROTTLED,

    /**
     * The event was malformed (failed payload validation / assembly) and was skipped. Maps to HTTP
     * 400 for a single post; inside a batch it is recorded against that item only while the rest are
     * still processed.
     */
    INVALID
}
