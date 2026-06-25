package com.agentmemory.hooks;

import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.WorkspaceId;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import tools.jackson.databind.JsonNode;

/**
 * The wire envelope a Go client {@code POST}s to {@code /hook} (and batches to {@code /hook/batch})
 * for a single captured lifecycle event (ARCHITECTURE §2.1, §3.1, §5.4; issue #7). It is the
 * <em>input</em> shape of the capture pipeline — distinct from the {@link com.agentmemory.core.Observation}
 * domain record, which is the post-ingest value the server mints (server-assigned id, sanitized
 * payload). This record deliberately carries only what a hook can know at capture time.
 *
 * <h2>Canonicalization</h2>
 * The client sends both the raw agent-native {@link #event} (e.g. {@code "PostToolUse"},
 * {@code "user-prompt-submit"}) <em>and</em> the {@link #kind} it canonicalized that event to via
 * {@link HookEvent#parse(String)}. Keeping the raw {@code event} on the wire (it becomes the
 * observation's {@code sourceEvent}) means an {@link ObservationKind#OTHER} or aliased event stays
 * auditable and the alias mapping can be revisited without re-capturing. The server re-derives the
 * canonical kind from {@code event} as the source of truth and does not trust a mismatched
 * {@code kind} blindly (defense in depth; see #8 ingest).
 *
 * <h2>Identity</h2>
 * Hooks are <strong>project-scoped</strong>: the client reports {@link #workspace} / {@link #project}
 * (resolved from the git root or a {@code .agent-memory.toml} marker, §2.1) plus the raw {@link #cwd}
 * it resolved them from. There is no page coordinate at capture time. The server pairs these with the
 * minted ids to build the project-scoped {@code Observation} identity.
 *
 * <h2>Tool fields</h2>
 * For {@code pre-tool-use} / {@code post-tool-use} events the client includes {@link #toolName},
 * {@link #toolInput} and {@link #toolResponse}. These are modeled as raw {@link JsonNode} — not
 * {@code String} — because an agent's tool input/response is arbitrary JSON whose shape we must not
 * flatten. In particular {@code toolResponse} is frequently an <em>array</em> of content blocks
 * (the documented prior-art "Bug A": an array {@code tool_response} that an over-strict object-only
 * parser dropped as "no output captured"). Modeling it as {@code JsonNode} preserves objects,
 * arrays and scalars identically so nothing is lost before sanitization (#9).
 *
 * <h2>Extension seam</h2>
 * A third party emits an event outside the canonical set by sending its own {@link #event} (which
 * canonicalizes to {@link ObservationKind#OTHER}) together with an {@link #extension} namespace.
 * The pair {@code (kind=other, extension=<ns>, event=<their name>)} records the event without
 * growing the enum (§5.4); {@code event} is preserved as the observation's {@code sourceEvent}.
 *
 * <p>Serialization mirrors the {@code core} conventions (docs/contracts/serialization.md):
 * lowerCamelCase fields, {@code @JsonInclude(NON_NULL)} (absent ⇔ null), value types as bare
 * scalars, timestamps as RFC-3339 {@code Z} instants. Unknown fields are ignored on read for
 * forward compatibility.
 *
 * @param event        the raw agent-native event name as the hook fired it; never null/blank.
 * @param kind         the canonical kind the client resolved {@code event} to; never null.
 * @param sessionId    the agent session this event belongs to; never null.
 * @param workspace    resolved workspace coordinate; never null.
 * @param project      resolved project coordinate; never null.
 * @param cwd          the working directory the client resolved identity from, or {@code null}.
 * @param agent        the agent that produced the event (e.g. {@code "claude-code"}), or {@code null}.
 * @param title        a short human label for the event, or {@code null}.
 * @param body         the main captured text (prompt text, notification body, …), or {@code null}.
 * @param toolName     the tool being/just invoked for tool events, or {@code null}.
 * @param toolInput    arbitrary-JSON tool input for tool events, or {@code null}.
 * @param toolResponse arbitrary-JSON tool output (object OR array — Bug A) for post-tool events,
 *     or {@code null}.
 * @param extension    third-party namespace for non-canonical events (§5.4), or {@code null}.
 * @param timestamp    when the event occurred (UTC instant); never null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({
    "event",
    "kind",
    "sessionId",
    "workspace",
    "project",
    "cwd",
    "agent",
    "title",
    "body",
    "toolName",
    "toolInput",
    "toolResponse",
    "extension",
    "timestamp"
})
public record HookPayload(
        @JsonProperty("event") String event,
        @JsonProperty("kind") ObservationKind kind,
        @JsonProperty("sessionId") SessionId sessionId,
        @JsonProperty("workspace") WorkspaceId workspace,
        @JsonProperty("project") ProjectId project,
        @JsonProperty("cwd") String cwd,
        @JsonProperty("agent") String agent,
        @JsonProperty("title") String title,
        @JsonProperty("body") String body,
        @JsonProperty("toolName") String toolName,
        @JsonProperty("toolInput") JsonNode toolInput,
        @JsonProperty("toolResponse") JsonNode toolResponse,
        @JsonProperty("extension") String extension,
        @JsonProperty("timestamp") Instant timestamp) {

    @JsonCreator
    public HookPayload {
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("hook.event must not be null or blank");
        }
        if (sessionId == null) {
            throw new IllegalArgumentException("hook.sessionId must not be null");
        }
        if (workspace == null) {
            throw new IllegalArgumentException("hook.workspace must not be null");
        }
        if (project == null) {
            throw new IllegalArgumentException("hook.project must not be null");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("hook.timestamp must not be null");
        }
        // A null kind on the wire would otherwise persist as a null observation kind; coerce it from
        // the raw event so the record is always canonically classified. (Deserialization normally
        // supplies a kind, but a hand-built or partial payload might omit it.)
        if (kind == null) {
            kind = HookEvent.parse(event);
        }
    }

    /**
     * Builds a payload, canonicalizing {@code event} to its {@link ObservationKind} via
     * {@link HookEvent#parse(String)} — the normal client-side path, so callers never have to
     * resolve the kind themselves.
     *
     * @param event     the raw agent-native event name.
     * @param sessionId the session id.
     * @param workspace the workspace coordinate.
     * @param project   the project coordinate.
     * @param timestamp the event instant.
     * @return a payload whose {@code kind} is the canonical mapping of {@code event}.
     */
    public static HookPayload of(
            String event,
            SessionId sessionId,
            WorkspaceId workspace,
            ProjectId project,
            Instant timestamp) {
        return new HookPayload(
                event,
                HookEvent.parse(event),
                sessionId,
                workspace,
                project,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                timestamp);
    }

    /**
     * @return {@code true} when this event canonicalizes to {@link ObservationKind#OTHER} but the
     *     client tagged it with an {@code extension} namespace — i.e. a deliberate third-party
     *     event rather than an unrecognized spelling.
     */
    @JsonIgnore
    public boolean isExtensionEvent() {
        return kind == ObservationKind.OTHER && extension != null && !extension.isBlank();
    }
}
