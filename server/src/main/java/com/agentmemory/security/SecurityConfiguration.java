package com.agentmemory.security;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.core.ActorResolver;
import com.agentmemory.core.CaptureSessionResolver;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * Centralizes auth (issue #38 / DD-007) as one Spring Security chain so the loopback fast-path stays
 * uncluttered and the exposed path is deliberate. Issue #39 adds multi-user mode on top.
 *
 * <h2>Modes</h2>
 * <ul>
 *   <li><strong>Disabled (default)</strong> — loopback-only, no credentials: every route permitted.
 *       The {@link AllowedHostsFilter} is installed but inert on a loopback bind.</li>
 *   <li><strong>Enabled, single-user</strong> — the shared root token guards everything except
 *       {@code /healthz} + {@code /actuator/health}; {@code /web} also accepts the token as an HTTP
 *       Basic password. A non-GET browser request must be same-origin ({@link BrowserWriteGuardFilter}).</li>
 *   <li><strong>Enabled, multi-user (issue #39)</strong> — when {@code agent-memory.auth.token-pepper}
 *       is set, per-user tokens ({@link UserService}) authenticate as their own identity for normal
 *       routes, while the {@link #ADMIN_PATHS admin routes} require the <em>root</em> token
 *       ({@link TokenAuthenticationFilter#ROLE_ROOT}). The authenticated principal is the actor recorded
 *       in the audit log.</li>
 * </ul>
 *
 * <p>The chain is always stateless (credentials presented per request; no session, no Spring CSRF token
 * — the browser-write guard handles cross-origin instead). Filter order: {@link AllowedHostsFilter}
 * first (reject a rebinding probe), then — when enabled — the token filter (authenticate) and the
 * browser-write guard (authorize the method). The admin gate ({@code ROLE_ROOT}) is applied whenever
 * auth is enabled; in single-user mode the lone root token satisfies it, so behavior is unchanged.
 *
 * <p>Registered as an {@link AutoConfiguration} (after the JDBC auto-config so the per-user beans see a
 * {@link JdbcTemplate}); the filter chain itself needs no {@link DataSource}, so auth is enforced even
 * before any project exists. The per-user beans are gated on a {@link DataSource} <em>and</em> a
 * non-blank token pepper (multi-user mode), so single-user and DB-less contexts skip them.
 */
@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    /** Routes always open even when auth is enabled: liveness/health probes. */
    static final String[] PUBLIC_PATHS = {"/healthz", "/actuator/health", "/actuator/health/**"};

    /**
     * Mutating / sensitive "admin" routes that require the root token (issue #39). The issue's nominal
     * {@code /admin/*} maps to the real top-level operational routes: project lifecycle, reset, reindex,
     * the time-travel mutations, user management, and the llm probe. Reads ({@code /api/v1},
     * {@code /mcp}, {@code /web}, {@code /checkpoints}) and normal capture ({@code /hook},
     * {@code /handoff}) are not admin and stay available to any authenticated user.
     */
    static final String[] ADMIN_PATHS = {
        "/projects/**", "/reset", "/reindex",
        "/restore-page", "/backup", "/restore", "/bootstrap",
        "/users/**", "/llm-test"
    };

    /** The {@code /web} browser UI — protected, but also offered an HTTP Basic challenge. */
    static final String WEB_PATTERN = "/web/**";

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AgentMemoryConfig config,
            ObjectProvider<UserService> userService,
            ObjectProvider<TokenAuthenticationFilter.OidcAuthenticator> oidcAuthenticator)
            throws Exception {
        var auth = config.auth();
        String bindAddress = config.server().address();

        // Common hardening for every mode: stateless, no form login, no servlet CSRF token (we present
        // credentials per-request and add our own cross-origin write guard).
        http.csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .anonymous(Customizer.withDefaults());

        // The anti-DNS-rebinding guard runs first in every mode (inert on a loopback bind).
        http.addFilterBefore(
                new AllowedHostsFilter(bindAddress, auth.allowedHosts()),
                UsernamePasswordAuthenticationFilter.class);

        if (!auth.enabled()) {
            log.info("auth disabled: loopback-only mode (bind {}), all routes permitted", bindAddress);
            http.authorizeHttpRequests(reg -> reg.anyRequest().permitAll());
            return http.build();
        }

        // Multi-user (issue #39): when a pepper is configured and the user store is available, resolve
        // non-root tokens to per-user identities. Otherwise only the root token authenticates.
        TokenAuthenticationFilter.UserResolver resolver = null;
        if (auth.multiUser()) {
            UserService svc = userService.getIfAvailable();
            if (svc != null) {
                resolver = svc::resolveToken;
            } else {
                log.warn("token pepper set (multi-user) but no user store (no datasource?); "
                        + "only the root token will authenticate");
            }
        }
        // OIDC (issue #39 PR2): when an issuer is configured, a JWT-shaped bearer is validated against
        // the IdP (JWKS / issuer / audience) and authenticates as its verified subject. Resolved via an
        // ObjectProvider so the filter chain wires whether or not the OIDC bean is present.
        TokenAuthenticationFilter.OidcAuthenticator oidc =
                auth.oidc().enabled() ? oidcAuthenticator.getIfAvailable() : null;
        if (auth.oidc().enabled() && oidc == null) {
            log.warn("oidc.issuer set but no OIDC authenticator bean; OIDC tokens will not authenticate");
        }
        log.info("auth enabled ({} mode{}): root token required on admin {}, bearer/Basic elsewhere; "
                        + "public={}, allowedHosts={}",
                auth.multiUser() ? "multi-user" : "single-user",
                oidc != null ? " + OIDC" : "",
                java.util.Arrays.toString(ADMIN_PATHS),
                java.util.Arrays.toString(PUBLIC_PATHS), auth.allowedHosts());

        // Authenticate the token (root, OIDC subject, or per-user), then gate cross-origin writes.
        http.addFilterBefore(
                new TokenAuthenticationFilter(auth.token(), resolver, oidc),
                UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(new BrowserWriteGuardFilter(), BasicAuthenticationFilter.class);

        http.authorizeHttpRequests(reg -> reg
                // Permit the servlet ERROR dispatch: an authorization 403 (or 401) is delivered via
                // sendError, which re-dispatches to /error; without this that re-dispatch re-enters the
                // chain as an anonymous GET and a forbidden (403) gets masked into a 401. Permitting the
                // ERROR dispatch lets the real status survive.
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .requestMatchers(ADMIN_PATHS).hasRole("ROOT")
                .anyRequest().hasRole("AGENT_MEMORY"));

        // 401 with a Basic challenge for /web (so a browser pops a login), a plain 401 elsewhere.
        http.exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint()));
        return http.build();
    }

    /**
     * The current-actor resolver (issue #39) used to attribute audit/observation rows to the
     * authenticated user. Reads the request thread's {@link org.springframework.security.core.context.SecurityContextHolder};
     * in single-user mode the principal is {@code "root"}, in multi-user mode the per-user slug, and
     * {@code null} when nothing authenticated. Always registered (even when auth is disabled — it then
     * simply resolves to {@code null}), so the audit writer and {@code /hook} controller can depend on
     * it unconditionally. {@link ConditionalOnMissingBean} lets a test substitute a fixed actor.
     */
    @Bean
    @ConditionalOnMissingBean(ActorResolver.class)
    public ActorResolver actorResolver() {
        return new SecurityContextActorResolver();
    }

    /**
     * The capture-session resolver (issue #87) used to isolate the {@code auto_scope=session_aware}
     * default scope to the current session. Reads the session id bound to the request thread by
     * {@link CaptureSessionHeaderFilter} (from the {@code X-Agent-Memory-Session} header). Always
     * registered (parallel to {@link #actorResolver()}); resolves to {@code null} when no session header
     * is present, which {@code ScopeResolver} treats as a hard error in session_aware mode rather than
     * widening to the global scope. {@link ConditionalOnMissingBean} lets a test substitute a fixed id.
     */
    @Bean
    @ConditionalOnMissingBean(CaptureSessionResolver.class)
    public CaptureSessionResolver captureSessionResolver() {
        return new RequestSessionResolver();
    }

    /**
     * The OIDC access-token validator (issue #39 PR2), present only when an issuer is configured
     * ({@code agent-memory.auth.oidc.issuer}). Validates a JWT bearer against the IdP's JWKS / issuer /
     * audience and yields the verified subject; the {@link TokenAuthenticationFilter} authenticates that
     * subject as a regular user (the audit actor). No {@link DataSource} is needed — OIDC subjects are
     * not provisioned in the {@code users} table.
     */
    @Bean
    @ConditionalOnExpression("'${agent-memory.auth.oidc.issuer:}'.length() > 0")
    public TokenAuthenticationFilter.OidcAuthenticator oidcJwtAuthenticator(AgentMemoryConfig config) {
        return new OidcJwtAuthenticator(config.auth().oidc());
    }

    // --- per-user beans (multi-user mode only) -----------------------------------------------------

    /**
     * The {@code users} store, present only in multi-user mode (a non-blank token pepper) and when a
     * {@link DataSource} exists. Gating on the pepper keeps single-user / DB-less contexts free of the
     * user machinery entirely.
     */
    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnExpression("'${agent-memory.auth.token-pepper:}'.length() > 0")
    public UserRepository userRepository(JdbcTemplate jdbcTemplate) {
        return new UserRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnSingleCandidate(DataSource.class)
    @ConditionalOnExpression("'${agent-memory.auth.token-pepper:}'.length() > 0")
    public UserService userService(UserRepository userRepository, AgentMemoryConfig config, Clock clock) {
        return new UserService(userRepository, config.auth().tokenPepper(), clock);
    }

    /**
     * Issue a {@code WWW-Authenticate: Basic} challenge for {@code /web} requests (a browser then shows
     * its login prompt and resends with HTTP Basic), and a bare {@code 401} for API/MCP routes (a
     * programmatic client reads the status, not a browser dialog).
     */
    private static AuthenticationEntryPoint entryPoint() {
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException authEx) -> {
            String uri = request.getRequestURI();
            String contextPath = request.getContextPath();
            String pathInApp = (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath))
                    ? uri.substring(contextPath.length())
                    : uri;
            if (pathInApp.startsWith("/web")) {
                response.setHeader("WWW-Authenticate", "Basic realm=\"agent-memory\", charset=\"UTF-8\"");
            }
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
        };
    }
}
