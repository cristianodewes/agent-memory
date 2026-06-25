package com.agentmemory.security;

import com.agentmemory.core.CaptureSessionResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Captures the per-request capture session id from the {@code X-Agent-Memory-Session} header into a
 * request-scoped {@link SessionContextHolder} for {@code auto_scope=session_aware} (issue #87). The
 * native-hook client sends this header on its MCP requests (its value is the same {@code SessionId} the
 * hooks report on every captured event), so a no-scope MCP tool call can default to <em>this session's</em>
 * most-recent project. Installed in front of the MCP servlet so the value is bound before any tool runs.
 *
 * <p>The header value is accepted only if it parses as a {@link UUID} (the {@code observations.session_id}
 * type); a missing or malformed header binds nothing, leaving {@code session_aware} to fail-fast rather
 * than guess. The binding is always {@link SessionContextHolder#clear() cleared} in a {@code finally} so
 * it cannot leak onto a pooled request thread.
 */
public final class CaptureSessionHeaderFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            SessionContextHolder.set(
                    canonicalSessionId(request.getHeader(CaptureSessionResolver.SESSION_HEADER)));
            chain.doFilter(request, response);
        } finally {
            SessionContextHolder.clear();
        }
    }

    /**
     * Return the canonical (lower-case) session id if {@code raw} is a well-formed UUID, else
     * {@code null}. Validating here keeps a garbage header from reaching the {@code uuid}-typed query and
     * turning into a 500 — an unparseable id is simply treated as "no session present".
     */
    private static String canonicalSessionId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim()).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
