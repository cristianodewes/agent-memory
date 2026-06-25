package com.agentmemory.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;

/**
 * One captured agent lifecycle event — a prompt, a tool call, a notification — recorded under a
 * {@link Session} (ARCHITECTURE §4.2 {@code observations}; §3.1 capture). Observations are the raw
 * audit log the LLM later compiles into {@link Page}s; they are primary (not derived) state.
 *
 * <p><strong>Identity is project-scoped</strong> (no page): an observation belongs to a project and
 * a session, not to one wiki page. The {@code kind} is the canonical {@link ObservationKind};
 * {@code sourceEvent} preserves the agent-native event name the client canonicalized from (so #7's
 * alias mapping is auditable and {@link ObservationKind#OTHER} events remain distinguishable), and
 * {@code extension} carries a third-party namespace per §5.4. Both are nullable.
 *
 * <p>{@code payload} is the captured text. In {@code core} it is just a string — this package does
 * <em>not</em> sanitize (DD-010 / invariant #6: sanitization is a typed boundary in {@code hooks},
 * not here). Persisting an observation still goes through {@code sanitize()} downstream; this record
 * is the post-parse, pre-store value shape.
 *
 * @param id          observation id (UUIDv7); never null.
 * @param sessionId   the session this event belongs to; never null.
 * @param identity    project-scoped identity (path must be null); never null.
 * @param kind        canonical event kind; never null.
 * @param sourceEvent the raw agent-native event name pre-canonicalization, or {@code null}.
 * @param extension   third-party namespace for non-canonical events (§5.4), or {@code null}.
 * @param payload     captured event text (unsanitized at this layer); never null (may be empty).
 * @param createdAt   when the event occurred (UTC instant); never null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "id", "sessionId", "identity", "kind", "sourceEvent", "extension", "payload", "createdAt"
})
public record Observation(
        @JsonProperty("id") ObservationId id,
        @JsonProperty("sessionId") SessionId sessionId,
        @JsonProperty("identity") Identity identity,
        @JsonProperty("kind") ObservationKind kind,
        @JsonProperty("sourceEvent") String sourceEvent,
        @JsonProperty("extension") String extension,
        @JsonProperty("payload") String payload,
        @JsonProperty("createdAt") Instant createdAt) {

    @JsonCreator
    public Observation {
        if (id == null) {
            throw new IllegalArgumentException("observation.id must not be null");
        }
        if (sessionId == null) {
            throw new IllegalArgumentException("observation.sessionId must not be null");
        }
        if (identity == null || identity.isPageScoped()) {
            throw new IllegalArgumentException(
                    "observation.identity must be project-scoped (no page path)");
        }
        if (kind == null) {
            throw new IllegalArgumentException("observation.kind must not be null");
        }
        if (payload == null) {
            throw new IllegalArgumentException("observation.payload must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("observation.createdAt must not be null");
        }
    }
}
