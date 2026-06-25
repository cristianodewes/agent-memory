package com.agentmemory.security;

import com.agentmemory.core.UserId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates a request against the agent-memory bearer token (issue #38 / DD-007), and — in
 * multi-user mode (issue #39) — against per-user tokens. Accepts the token two ways, so one filter
 * covers both the API/MCP clients and the {@code /web} browser:
 *
 * <ul>
 *   <li><strong>Bearer</strong> — {@code Authorization: Bearer <token>} (the API, {@code /mcp},
 *       {@code /hook}, {@code /handoff}, admin routes).</li>
 *   <li><strong>HTTP Basic</strong> — {@code Authorization: Basic base64(user:token)} with the token
 *       as the <em>password</em> (any username); this is what a browser sends to {@code /web} after the
 *       {@code WWW-Authenticate: Basic} challenge.</li>
 * </ul>
 *
 * <h2>Root vs per-user (issue #39)</h2>
 * The configured {@code agent-memory.auth.token} is the <strong>root</strong> token: it authenticates
 * as the {@code root} principal with {@link #ROLE_ROOT} (the only credential allowed on {@code /admin}
 * routes). When a {@link UserResolver} is supplied (multi-user mode), a token that is <em>not</em> the
 * root token is resolved to a per-user identity — authenticated as that username with
 * {@link #ROLE} only (no root). The authenticated principal name is the actor recorded in the audit log
 * ({@code root} or the username). The root comparison is constant-time
 * ({@link MessageDigest#isEqual}); a per-user token is matched by a hashed DB lookup in the resolver.
 *
 * <p>On a present-but-unrecognized credential the context is left anonymous and Spring Security's entry
 * point issues the 401. A request with no {@code Authorization} header passes through untouched. Only
 * installed when auth is enabled (see {@link SecurityConfiguration}).
 */
public final class TokenAuthenticationFilter extends OncePerRequestFilter {

    /** The authority every authenticated caller holds (root and per-user alike). */
    static final String ROLE = "ROLE_AGENT_MEMORY";
    /** The extra authority the root token grants — required on {@code /admin} routes (#39). */
    static final String ROLE_ROOT = "ROLE_ROOT";
    /** Principal name for the root token (the actor recorded when root acts). */
    static final String ROOT_PRINCIPAL = "root";

    /** Extra authority recording that the token arrived via HTTP Basic — i.e. a browser request. */
    static final String MECH_BASIC = "MECH_BASIC";
    /** Extra authority recording that the token arrived as a Bearer header — a programmatic client. */
    static final String MECH_BEARER = "MECH_BEARER";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String BASIC_PREFIX = "Basic ";

    /** Resolves a non-root bearer token to a per-user identity (multi-user mode); see {@link UserService}. */
    @FunctionalInterface
    public interface UserResolver {
        Optional<UserId> resolve(String rawToken);
    }

    private final byte[] expectedRoot;
    private final UserResolver users; // nullable — single-user mode resolves only the root token

    public TokenAuthenticationFilter(String rootToken, UserResolver users) {
        if (rootToken == null || rootToken.isBlank()) {
            throw new IllegalArgumentException("token must not be blank when auth is enabled");
        }
        this.expectedRoot = rootToken.getBytes(StandardCharsets.UTF_8);
        this.users = users;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Don't re-authenticate if an earlier filter already did.
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            Presented presented = extractToken(request.getHeader("Authorization"));
            if (presented != null) {
                authenticate(presented).ifPresent(
                        a -> SecurityContextHolder.getContext().setAuthentication(a));
            }
        }
        chain.doFilter(request, response);
    }

    /** Decide who (if anyone) a presented token authenticates as: root, a per-user identity, or nobody. */
    private Optional<UsernamePasswordAuthenticationToken> authenticate(Presented presented) {
        if (matchesRoot(presented.token())) {
            return Optional.of(principal(ROOT_PRINCIPAL, presented.mechanism(), true));
        }
        if (users != null) {
            Optional<UserId> user = users.resolve(presented.token());
            if (user.isPresent()) {
                return Optional.of(principal(user.get().value(), presented.mechanism(), false));
            }
        }
        return Optional.empty();
    }

    /** Build an authenticated token with the base role, the mechanism tag, and (for root) {@link #ROLE_ROOT}. */
    private static UsernamePasswordAuthenticationToken principal(
            String name, String mechanism, boolean root) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>(3);
        authorities.add(new SimpleGrantedAuthority(ROLE));
        authorities.add(new SimpleGrantedAuthority(mechanism));
        if (root) {
            authorities.add(new SimpleGrantedAuthority(ROLE_ROOT));
        }
        return UsernamePasswordAuthenticationToken.authenticated(name, null, authorities);
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

    /** Constant-time comparison of the presented token against the configured root token. */
    private boolean matchesRoot(String presented) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expectedRoot);
    }
}
