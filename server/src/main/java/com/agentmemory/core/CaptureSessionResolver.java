package com.agentmemory.core;

/**
 * Resolves the <em>capture session</em> id for the current request thread — the native-hook session
 * (issue #87, {@code auto_scope=session_aware}). It is the same {@link SessionId} the hooks report on
 * every captured event ({@code observations.session_id}), conveyed to the MCP boundary so a no-scope
 * tool call can default to <em>this session's</em> most-recent project rather than the actor's global
 * or the server's global one.
 *
 * <p>Unlike the {@link ActorResolver} (which reads Spring Security's {@code SecurityContextHolder}),
 * the capture session id is not an authentication principal: it rides a request header
 * ({@code X-Agent-Memory-Session}) populated by the client's MCP config, captured into a request-scoped
 * holder by a servlet filter. {@code null} when no session id reached this request — which
 * {@code session_aware} treats as a hard error (never a silent fall-back to the global scope, which on
 * a shared server would leak activity across sessions).
 *
 * <p>Kept in {@code core} (no Spring dependency) so the {@code mcp} resolver can depend on the
 * interface; the request-scoped implementation lives in {@code security}.
 */
@FunctionalInterface
public interface CaptureSessionResolver {

    /**
     * @return the current request's capture session id (a {@link SessionId} string), or {@code null}
     *     when no {@code X-Agent-Memory-Session} header was present on this request.
     */
    String currentSessionId();

    /**
     * The request header that carries the capture session id from the client's MCP config to the
     * server. Defined here (dependency-free {@code core}) so the security-layer filter that reads it and
     * the {@code mcp} resolver that reports its absence share one source of truth without cross-package
     * coupling.
     */
    String SESSION_HEADER = "X-Agent-Memory-Session";

    /** A resolver that never carries a session — the default for non-MCP / pure-unit contexts. */
    CaptureSessionResolver NONE = () -> null;
}
