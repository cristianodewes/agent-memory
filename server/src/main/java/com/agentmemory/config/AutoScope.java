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
 * </ul>
 *
 * <p>Bound from {@code agent-memory.scope.auto} via Spring's relaxed enum binding, so
 * {@code single_slot} / {@code single-slot} / {@code SINGLE_SLOT} all resolve.
 */
public enum AutoScope {
    SINGLE_SLOT,
    PER_ACTOR,

    /**
     * Isolation by capture session — declared but <strong>not yet supported</strong>. The MCP tool
     * boundary carries no capture-session id to key on (only the actor, via the security context), so
     * it cannot be wired end-to-end yet. Selecting it is <em>rejected at startup</em> with a clear
     * error rather than silently degrading to {@link #SINGLE_SLOT}: on a shared server a silent
     * fall-back to the global scope would leak activity across sessions. Tracked as a follow-up with
     * the native-hook session-bound identity work.
     */
    SESSION_AWARE
}
