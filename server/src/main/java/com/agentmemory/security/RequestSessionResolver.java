package com.agentmemory.security;

import com.agentmemory.core.CaptureSessionResolver;

/**
 * The default {@link CaptureSessionResolver}: reads the capture session id bound to the current request
 * thread by {@link CaptureSessionHeaderFilter} (from the {@code X-Agent-Memory-Session} header). Returns
 * {@code null} when no session header was present, which {@code ScopeResolver} treats as a hard error in
 * {@code session_aware} mode rather than silently widening to the global scope (issue #87).
 */
public final class RequestSessionResolver implements CaptureSessionResolver {

    @Override
    public String currentSessionId() {
        return SessionContextHolder.get();
    }
}
