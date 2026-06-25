package com.agentmemory.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The anti-DNS-rebinding guard (issue #38 / DD-007): on a <strong>non-loopback</strong> bind, every
 * request's {@code Host} header must name an allowed host, otherwise it is rejected with {@code 403}.
 * This stops a malicious page on another origin from rebinding its DNS to the server's IP and driving
 * the (browser-trusted, same-origin) API/MCP — the classic attack against a localhost dev server that
 * has been exposed.
 *
 * <p>Allowed hosts = the configured allow-list ({@code agent-memory.auth.allowed-hosts}) plus the
 * always-safe loopback names ({@code localhost}, {@code 127.0.0.1}, {@code [::1]}). When the server is
 * bound to a loopback address (the single-user default) the guard is inert — a loopback bind is not
 * reachable off-box, so there is nothing to rebind — keeping the fast-path uncluttered.
 *
 * <p>Runs before authentication so a rebinding probe is rejected without even reaching the token check.
 */
public final class AllowedHostsFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AllowedHostsFilter.class);

    /** Loopback host names always accepted (the host portion, lower-cased, port stripped). */
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final boolean active;
    private final Set<String> allowed;

    /**
     * @param bindAddress  the server's bind address (loopback ⇒ guard inert).
     * @param allowedHosts the configured allow-list (already normalized, may be empty).
     */
    public AllowedHostsFilter(String bindAddress, java.util.List<String> allowedHosts) {
        this.active = !isLoopbackBind(bindAddress);
        Set<String> set = new LinkedHashSet<>(LOOPBACK_HOSTS);
        if (allowedHosts != null) {
            set.addAll(allowedHosts);
        }
        this.allowed = Set.copyOf(set);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (active) {
            String host = hostOnly(request.getHeader("Host"));
            if (host == null || !allowed.contains(host)) {
                log.warn("rejected request with disallowed Host header '{}' (allowed={})",
                        request.getHeader("Host"), allowed);
                // Write the 403 directly (not sendError) so the servlet ERROR dispatch does not
                // re-enter the security chain and turn this into a 401 (see BrowserWriteGuardFilter).
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("Host not allowed");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** The host portion of a {@code Host} header, lower-cased, port removed; {@code null} if blank. */
    static String hostOnly(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return null;
        }
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        // IPv6 literal like [::1]:8080 → keep the bracketed host, drop the port after the bracket.
        if (h.startsWith("[")) {
            int close = h.indexOf(']');
            return close >= 0 ? h.substring(0, close + 1) : h;
        }
        int colon = h.indexOf(':');
        return colon >= 0 ? h.substring(0, colon) : h;
    }

    /** Whether a bind address is a loopback address (so the guard is unnecessary). */
    static boolean isLoopbackBind(String bindAddress) {
        if (bindAddress == null || bindAddress.isBlank()) {
            return false; // unknown ⇒ treat as exposed, guard active (safe default)
        }
        String a = bindAddress.trim().toLowerCase(Locale.ROOT);
        return a.equals("127.0.0.1") || a.equals("::1") || a.equals("[::1]")
                || a.equals("localhost") || a.startsWith("127.");
    }
}
