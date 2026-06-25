package com.agentmemory.config;

/**
 * How an MCP tool call's <em>default</em> {@code (workspace, project)} scope is resolved when the call
 * gives no explicit scope (issue #39 multi-user isolation). It modulates the "most recently active
 * project" fallback (DD-003) so users on a shared server do not default into each other's activity.
 *
 * <ul>
 *   <li>{@link #SINGLE_SLOT} — the server's globally most-recent project. The default and the
 *       single-user behavior: one shared "current project" slot.</li>
 *   <li>{@link #PER_ACTOR} — the most-recent project <em>of the authenticated user</em> (the
 *       {@code observations.actor}). On a shared server each user's no-scope calls stay in their own
 *       lane. Falls back to {@link #SINGLE_SLOT} when there is no authenticated actor.</li>
 *   <li>{@link #SESSION_AWARE} — the most-recent project <em>of this capture session</em> (issue #87).
 *       The finest isolation: two sessions of the same user — even concurrent ones in different
 *       projects — keep their no-scope calls in their own lane.</li>
 * </ul>
 *
 * <p>Bound from {@code agent-memory.scope.auto} via Spring's relaxed enum binding, so
 * {@code single_slot} / {@code single-slot} / {@code SINGLE_SLOT} all resolve.
 */
public enum AutoScope {
    SINGLE_SLOT,
    PER_ACTOR,

    /**
     * Isolation by capture session (issue #87): the default no-scope project is the most-recent
     * activity of <em>this</em> session, keyed on the {@code observations.session_id} the native hook
     * reports. The session id reaches the MCP boundary via the {@code X-Agent-Memory-Session} request
     * header (sent by the client's MCP config); when it is absent the {@code ScopeResolver}
     * <strong>fail-fasts</strong> rather than silently widening to the global scope — on a shared server
     * a silent fall-back would leak activity across sessions. This is the per-session counterpart to
     * {@link #PER_ACTOR}'s per-user isolation.
     */
    SESSION_AWARE
}
