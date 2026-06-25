package com.agentmemory.security;

/**
 * A request-scoped {@link ThreadLocal} carrying the capture session id for the current request thread
 * (issue #87). Set by {@link CaptureSessionHeaderFilter} from the {@code X-Agent-Memory-Session} header
 * and read by {@link RequestSessionResolver}, mirroring how Spring Security's {@code SecurityContextHolder}
 * carries the actor — the MCP tool handler runs on the request thread, so a value placed here by the
 * servlet filter is visible to {@code ScopeResolver} (the same thread-visibility {@code per_actor} relies on).
 *
 * <p>The filter MUST {@link #clear()} in a {@code finally} so the value never leaks onto a pooled thread.
 */
final class SessionContextHolder {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private SessionContextHolder() {}

    /** Bind the capture session id for the current thread (a blank/{@code null} value clears it). */
    static void set(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(sessionId);
        }
    }

    /** @return the current thread's capture session id, or {@code null} if none is bound. */
    static String get() {
        return CURRENT.get();
    }

    /** Remove the binding — MUST be called in a {@code finally} at the end of the request. */
    static void clear() {
        CURRENT.remove();
    }
}
