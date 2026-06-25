package com.agentmemory.hooks;

import com.agentmemory.core.Identity;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.SessionId;
import java.time.Instant;

/**
 * The <strong>unsanitized</strong> request to record one observation — the value the ingest layer
 * (#8) assembles from a {@link HookPayload} before privacy stripping. Its {@link #payload} is the
 * raw, untrusted captured text (a prompt, a flattened tool input/response, a notification body);
 * it has <em>not</em> been scrubbed.
 *
 * <p>This type is intentionally <em>not storable</em>. The store ({@code com.agentmemory.store})
 * accepts only a {@link Sanitized}{@code <NewObservation>}, and the only way to obtain one is
 * {@link Sanitizer#sanitize(NewObservation)} (DD-010 / invariant #6). So a {@code NewObservation}
 * can be built freely, but it cannot reach persistence without passing through the sanitizer — the
 * compile-time guarantee this design exists to provide.
 *
 * <p>Everything except {@code payload} is already-typed, already-trusted structure (ids, the
 * canonical {@link ObservationKind}, the project-scoped {@link Identity}); the sanitizer only ever
 * rewrites the free-text {@code payload}. {@code sourceEvent}, {@code extension} and
 * {@code clientEventId} are short machine tokens (the agent-native event name, a third-party
 * namespace, and the client's stable per-event dedupe id) and are validated, not scrubbed.
 *
 * <p>{@code clientEventId} is the idempotency key the ingest layer (#8) threads through to the
 * store: a retried spool drain re-sends the same id, and the writer dedupes on
 * {@code (sessionId, clientEventId)} so a replay creates no duplicate row. It is optional — a
 * payload that omits it is always inserted (no dedupe).
 *
 * @param sessionId     the session this event belongs to; never null.
 * @param identity      project-scoped identity (path must be null); never null.
 * @param kind          canonical event kind; never null.
 * @param sourceEvent   the raw agent-native event name pre-canonicalization, or {@code null}.
 * @param extension     third-party namespace for non-canonical events (§5.4), or {@code null}.
 * @param clientEventId client-supplied stable per-event id for idempotent replay (#8), or
 *     {@code null} (no dedupe).
 * @param payload       the raw, <strong>unsanitized</strong> captured text; never null (may be empty).
 * @param createdAt     when the event occurred (UTC instant); never null.
 */
public record NewObservation(
        SessionId sessionId,
        Identity identity,
        ObservationKind kind,
        String sourceEvent,
        String extension,
        String clientEventId,
        String payload,
        Instant createdAt) {

    public NewObservation {
        if (sessionId == null) {
            throw new IllegalArgumentException("newObservation.sessionId must not be null");
        }
        if (identity == null || identity.isPageScoped()) {
            throw new IllegalArgumentException(
                    "newObservation.identity must be project-scoped (no page path)");
        }
        if (kind == null) {
            throw new IllegalArgumentException("newObservation.kind must not be null");
        }
        if (payload == null) {
            throw new IllegalArgumentException("newObservation.payload must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("newObservation.createdAt must not be null");
        }
    }

    /**
     * Returns a copy with {@code payload} replaced by {@code scrubbed}. Package-private on purpose:
     * only {@link Sanitizer} swaps in a cleaned payload, immediately before wrapping the result in a
     * {@link Sanitized}. Keeping this out of the public surface means callers cannot quietly mutate
     * the text after (or instead of) sanitization.
     *
     * @param scrubbed the redacted, size-capped payload.
     * @return a new {@code NewObservation} identical except for its payload.
     */
    NewObservation withPayload(String scrubbed) {
        return new NewObservation(
                sessionId, identity, kind, sourceEvent, extension, clientEventId, scrubbed, createdAt);
    }
}
