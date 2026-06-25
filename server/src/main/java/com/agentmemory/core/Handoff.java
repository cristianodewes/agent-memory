package com.agentmemory.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.util.List;

/**
 * A typed, LLM-written "where you left off" record, produced at session end and injected at the
 * next session start (ARCHITECTURE §3.4; §4.2 {@code handoffs}; DD-005). Single-use: see
 * {@link HandoffStatus}.
 *
 * <p><strong>Project-scoped identity</strong> (no page): a handoff summarizes a project's session,
 * not a page. {@code fromSession} links back to the {@link Session} it was written from. The three
 * content fields mirror the handoff shape the LLM emits as structured JSON (invariant #7):
 * {@code summary} prose plus {@code openQuestions} and {@code nextSteps} lists. The two lists are
 * never null — an empty handoff carries {@code []}, not {@code null} (see the contract under
 * {@code docs/contracts/}); defensively copied to keep the record immutable.
 *
 * @param id            handoff id (UUIDv7); never null.
 * @param identity      project-scoped identity (path must be null); never null.
 * @param fromSession   the session this handoff summarizes; never null.
 * @param status        lifecycle state; never null.
 * @param summary       prose "where you left off"; never null (may be empty).
 * @param openQuestions unresolved questions for the next session; never null (may be empty).
 * @param nextSteps     suggested next actions; never null (may be empty).
 * @param createdAt     when the handoff was written (UTC instant); never null.
 * @param acceptedAt    when it was consumed, or {@code null} while {@link HandoffStatus#OPEN}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "id", "identity", "fromSession", "status", "summary",
    "openQuestions", "nextSteps", "createdAt", "acceptedAt"
})
public record Handoff(
        @JsonProperty("id") HandoffId id,
        @JsonProperty("identity") Identity identity,
        @JsonProperty("fromSession") SessionId fromSession,
        @JsonProperty("status") HandoffStatus status,
        @JsonProperty("summary") String summary,
        @JsonProperty("openQuestions") List<String> openQuestions,
        @JsonProperty("nextSteps") List<String> nextSteps,
        @JsonProperty("createdAt") Instant createdAt,
        @JsonProperty("acceptedAt") Instant acceptedAt) {

    @JsonCreator
    public Handoff(
            @JsonProperty("id") HandoffId id,
            @JsonProperty("identity") Identity identity,
            @JsonProperty("fromSession") SessionId fromSession,
            @JsonProperty("status") HandoffStatus status,
            @JsonProperty("summary") String summary,
            @JsonProperty("openQuestions") List<String> openQuestions,
            @JsonProperty("nextSteps") List<String> nextSteps,
            @JsonProperty("createdAt") Instant createdAt,
            @JsonProperty("acceptedAt") Instant acceptedAt) {
        if (id == null) {
            throw new IllegalArgumentException("handoff.id must not be null");
        }
        if (identity == null || identity.isPageScoped()) {
            throw new IllegalArgumentException(
                    "handoff.identity must be project-scoped (no page path)");
        }
        if (fromSession == null) {
            throw new IllegalArgumentException("handoff.fromSession must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("handoff.status must not be null");
        }
        if (summary == null) {
            throw new IllegalArgumentException("handoff.summary must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("handoff.createdAt must not be null");
        }
        this.id = id;
        this.identity = identity;
        this.fromSession = fromSession;
        this.status = status;
        this.summary = summary;
        // Null-coalesce to empty + defensive immutable copy: lists are always present, never null.
        this.openQuestions = openQuestions == null ? List.of() : List.copyOf(openQuestions);
        this.nextSteps = nextSteps == null ? List.of() : List.copyOf(nextSteps);
        this.createdAt = createdAt;
        this.acceptedAt = acceptedAt;
    }
}
