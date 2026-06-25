package com.agentmemory.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Treats a <strong>non-GET browser request differently from read browsing</strong> (issue #38 project
 * note): a CSRF-style guard that lets a programmatic client mutate freely but requires a browser to
 * prove same-origin intent before a state-changing call.
 *
 * <p>The distinction is the auth mechanism the request authenticated with (tagged by
 * {@link TokenAuthenticationFilter}):
 * <ul>
 *   <li><strong>Bearer</strong> ({@code MECH_BEARER}) — the Go client / {@code /mcp} / scripts. Not a
 *       browser; a forged cross-site page cannot set an {@code Authorization: Bearer} header it does
 *       not know, so any method is allowed.</li>
 *   <li><strong>HTTP Basic</strong> ({@code MECH_BASIC}) — a browser, whose stored credentials the
 *       browser <em>auto-attaches</em> to a cross-site form/fetch. For a <em>safe</em> method
 *       (GET/HEAD/OPTIONS) that is just read browsing and is allowed; for an <em>unsafe</em> method
 *       (POST/PUT/PATCH/DELETE) we require an {@code Origin} (or {@code Referer}) header whose host
 *       matches the request host — present and same-origin on a genuine same-site fetch, absent or
 *       cross-origin on a CSRF attempt — otherwise {@code 403}.</li>
 * </ul>
 *
 * <p>Anonymous requests are not the concern here (the authorization rules already 401 them on
 * protected routes); this only adds the extra cross-origin check on top of an authenticated Basic
 * write. Installed only when auth is enabled.
 */
public final class BrowserWriteGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BrowserWriteGuardFilter.class);

    /** Methods that do not change state — always allowed for a browser (read browsing). */
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isGuardedBrowserWrite(request) && !isSameOrigin(request)) {
            log.warn("blocked cross-origin browser write: {} {} (origin={}, referer={})",
                    request.getMethod(), request.getRequestURI(),
                    request.getHeader("Origin"), request.getHeader("Referer"));
            // Write the 403 directly rather than sendError(): sendError triggers a servlet ERROR
            // dispatch to /error, which re-enters the security chain as an anonymous GET and would be
            // turned into a 401 by the entry point, masking this decision.
            writeForbidden(response, "Cross-origin browser write rejected; same-origin Origin header required");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Write a {@code 403} body directly (no {@code sendError}, so no ERROR re-dispatch). */
    static void writeForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/plain;charset=UTF-8");
        response.getWriter().write(message);
    }

    /** An authenticated-via-Basic request using an unsafe method — the only case we gate. */
    private static boolean isGuardedBrowserWrite(HttpServletRequest request) {
        if (SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (TokenAuthenticationFilter.MECH_BASIC.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the request carries a same-origin signal: an {@code Origin} (preferred) or {@code Referer}
     * header whose host equals the request's host. A missing/opaque/cross-host value fails the check.
     */
    private static boolean isSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        String candidate = (origin != null && !origin.isBlank()) ? origin : request.getHeader("Referer");
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String sourceHost = hostOf(candidate);
        if (sourceHost == null) {
            return false;
        }
        String targetHost = AllowedHostsFilter.hostOnly(request.getHeader("Host"));
        return sourceHost.equals(targetHost);
    }

    /** The lower-cased host of an Origin/Referer URL value, or {@code null} if unparseable. */
    private static String hostOf(String url) {
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
