package com.agentmemory.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * The <strong>canonical</strong> set of agent lifecycle event kinds an {@link Observation} can
 * record. This is the server-side vocabulary the wire contract is written against; the Go client
 * canonicalizes its agent-native hook names down to these (ARCHITECTURE §2.1, §5.4).
 *
 * <p>Issue #7 layers <em>client-alias mapping</em> (e.g. an agent's {@code "PreToolUse"} or
 * {@code "user-prompt-submit"} spelling) on top of this enum — that mapping is intentionally
 * <em>not</em> here. {@link #OTHER} is the catch-all for any kind outside this set; combined with
 * the {@code extension} namespace field (§5.4) it lets third parties record events without growing
 * the canonical enum.
 *
 * <p>Wire form is kebab-case (e.g. {@code "session-start"}, {@code "pre-tool-use"}). Each constant
 * carries its wire token; {@link #wire()} is the {@link JsonValue} and {@link #fromWire(String)} the
 * {@link JsonCreator}. Parsing is lenient — unknown tokens map to {@link #OTHER} rather than
 * throwing — so a newer client emitting a kind this server build does not know never breaks
 * ingest.
 */
public enum ObservationKind {

    /** A new agent session has begun; the boundary that triggers a backlog drain + handoff fetch. */
    SESSION_START("session-start"),

    /** The user submitted a prompt to the agent. */
    USER_PROMPT("user-prompt"),

    /** The agent is about to invoke a tool (before execution). */
    PRE_TOOL_USE("pre-tool-use"),

    /** The agent has finished invoking a tool (after execution, with its result). */
    POST_TOOL_USE("post-tool-use"),

    /** The agent is about to compact / summarize its context window. */
    PRE_COMPACT("pre-compact"),

    /** An out-of-band notification event from the agent. */
    NOTIFICATION("notification"),

    /** The agent stopped/halted the current turn. */
    STOP("stop"),

    /** The agent session is ending; the boundary that triggers consolidation + handoff. */
    SESSION_END("session-end"),

    /** Any kind outside the canonical set; pair with {@code extension} for third-party events. */
    OTHER("other");

    private final String wire;

    ObservationKind(String wire) {
        this.wire = wire;
    }

    /** @return the canonical kebab-case wire token for this kind. */
    @JsonValue
    public String wire() {
        return wire;
    }

    /**
     * Lenient parse from the wire token. Matching is case-insensitive and tolerant of underscores
     * vs. hyphens so {@code "post_tool_use"} and {@code "POST-TOOL-USE"} both resolve to
     * {@link #POST_TOOL_USE}.
     *
     * @param token the wire kind ({@code null}/blank → {@link #OTHER}).
     * @return the matching kind, or {@link #OTHER} if unrecognized (never throws).
     */
    @JsonCreator
    public static ObservationKind fromWire(String token) {
        if (token == null || token.isBlank()) {
            return OTHER;
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (ObservationKind kind : values()) {
            if (kind.wire.equals(normalized)) {
                return kind;
            }
        }
        return OTHER;
    }
}
