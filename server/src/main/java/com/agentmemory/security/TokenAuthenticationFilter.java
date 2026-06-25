package com.agentmemory.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request against the single shared agent-memory bearer token (issue #38 / DD-007).
 * Accepts the token two ways, so one filter covers both the API/MCP clients and the {@code /web}
 * browser:
 *
 * <ul>
 *   <li><strong>Bearer</strong> — {@code Authorization: Bearer <token>} (the API, {@code /mcp},
 *       {@code /hook}, {@code /handoff}, admin routes).</li>
 *   <li><strong>HTTP Basic</strong> — {@code Authorization: Basic base64(user:token)} with the token
 *       as the <em>password</em> (any username); this is what a browser sends to {@code /web} after the
 *       {@code WWW-Authenticate: Basic} challenge.</li>
 * </ul>
 *
 * <p>On a match the request is authenticated with a single {@code ROLE_AGENT_MEMORY} authority; on a
 * present-but-wrong credential the context is left anonymous and Spring Security's entry point issues
 * the 401. A request with no {@code Authorization} header passes through untouched (anonymous) so
 * permit-all routes still work. The comparison is constant-time ({@link MessageDigest#isEqual}) to not
 * leak the token by timing.
 *
 * <p>Only installed when auth is enabled (see {@link SecurityConfiguration}); in the default
 * loopback-only mode this filter is absent and nothing inspects credentials.
 */
public final class TokenAuthenticationFilter extends OncePerRequestFilter {

    /** The authority granted to a request bearing the valid token. */
    static final String ROLE = "ROLE_AGENT_MEMORY";

    /** Extra authority recording that the token arrived via HTTP Basic — i.e. a browser request. */
    static final String MECH_BASIC = "MECH_BASIC";
    /** Extra authority recording that the token arrived as a Bearer header — a programmatic client. */
    static final String MECH_BEARER = "MECH_BEARER";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String BASIC_PREFIX = "Basic ";

    private final byte[] expected;

    public TokenAuthenticationFilter(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank when auth is enabled");
        }
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Don't re-authenticate if an earlier filter already did.
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            Presented presented = extractToken(request.getHeader("Authorization"));
            if (presented != null && matches(presented.token())) {
                var auth = UsernamePasswordAuthenticationToken.authenticated(
                        "agent-memory", null,
                        List.of(new SimpleGrantedAuthority(ROLE),
                                new SimpleGrantedAuthority(presented.mechanism())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }

    /** A token presented on a request, tagged with the mechanism it arrived by. */
    private record Presented(String token, String mechanism) {}

    /**
     * Pull the candidate token from an {@code Authorization} header — the bearer value directly, or the
     * password half of HTTP Basic — tagged with its mechanism. {@code null} when absent or malformed.
     */
    private static Presented extractToken(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        if (header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            String v = header.substring(BEARER_PREFIX.length()).trim();
            return v.isEmpty() ? null : new Presented(v, MECH_BEARER);
        }
        if (header.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length())) {
            String pwd = basicPassword(header.substring(BASIC_PREFIX.length()).trim());
            return pwd == null ? null : new Presented(pwd, MECH_BASIC);
        }
        return null;
    }

    /** Decode {@code base64(user:password)} and return the password (the token); {@code null} if malformed. */
    private static String basicPassword(String b64) {
        try {
            String decoded = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                return null;
            }
            String pwd = decoded.substring(colon + 1);
            return pwd.isEmpty() ? null : pwd;
        } catch (IllegalArgumentException e) {
            return null; // not valid base64
        }
    }

    /** Constant-time comparison of the presented token against the configured one. */
    private boolean matches(String presented) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected);
    }
}
