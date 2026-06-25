package com.agentmemory.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;

/**
 * One agent run — the unit that groups {@link Observation}s between a {@code session-start} and a
 * {@code session-end} (ARCHITECTURE §4.2 {@code sessions}; §3 data flow). A session is the scope at
 * which consolidation and handoffs operate (§3.2, §3.4). Primary (not derived) state.
 *
 * <p><strong>Project-scoped identity</strong> (no page): a session belongs to a project, not a
 * page. {@code agent} records which agent produced the run (e.g. {@code "claude-code"}), useful for
 * multi-agent setups; nullable. {@code endedAt} is null while the session is still open and set
 * once {@code session-end} is processed.
 *
 * @param id        session id (UUIDv7); never null.
 * @param identity  project-scoped identity (path must be null); never null.
 * @param agent     agent identifier that produced this session, or {@code null} if unknown.
 * @param startedAt when the session began (UTC instant); never null.
 * @param endedAt   when the session ended, or {@code null} while still open.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "identity", "agent", "startedAt", "endedAt"})
public record Session(
        @JsonProperty("id") SessionId id,
        @JsonProperty("identity") Identity identity,
        @JsonProperty("agent") String agent,
        @JsonProperty("startedAt") Instant startedAt,
        @JsonProperty("endedAt") Instant endedAt) {

    @JsonCreator
    public Session {
        if (id == null) {
            throw new IllegalArgumentException("session.id must not be null");
        }
        if (identity == null || identity.isPageScoped()) {
            throw new IllegalArgumentException(
                    "session.identity must be project-scoped (no page path)");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("session.startedAt must not be null");
        }
    }

    /** @return {@code true} while the session has no recorded end. */
    @JsonIgnore
    public boolean isOpen() {
        return endedAt == null;
    }
}
