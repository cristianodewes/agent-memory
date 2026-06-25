package com.agentmemory.security;

import com.agentmemory.config.AgentMemoryConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
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
 * uncluttered and the exposed path is deliberate.
 *
 * <h2>Two modes, by {@code agent-memory.auth.enabled}</h2>
 * <ul>
 *   <li><strong>Disabled (default)</strong> — loopback-only, no credentials: every route is permitted.
 *       The single-user laptop needs no ceremony. The {@link AllowedHostsFilter} is still installed but
 *       is inert on a loopback bind.</li>
 *   <li><strong>Enabled</strong> — a shared bearer token guards the API/MCP/hook/handoff/admin routes
 *       and {@code /web}; {@code /web} additionally accepts the token as an HTTP Basic password (so a
 *       browser can log in). Only {@code /healthz} and the actuator health endpoint stay open (for
 *       liveness probes). A non-GET browser (Basic-authenticated) request must be same-origin
 *       ({@link BrowserWriteGuardFilter}).</li>
 * </ul>
 *
 * <p>The chain is always stateless (no HTTP session, no Spring CSRF token store): credentials are
 * presented per request, so there is no session-riding to protect with a CSRF token — the browser
 * write guard handles the cross-origin concern instead. The {@link AllowedHostsFilter} runs first
 * (reject a rebinding probe before anything else), then — when enabled — the token filter (authenticate)
 * and the browser-write guard (authorize the method).
 *
 * <p>Registered as an {@link AutoConfiguration} listed in {@code AutoConfiguration.imports} so it loads
 * without component scanning, like the other modules; it depends only on the resolved
 * {@link AgentMemoryConfig} (no DataSource), so auth is enforced even before any project exists.
 */
@AutoConfiguration
public class SecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfiguration.class);

    /** Routes always open even when auth is enabled: liveness/health probes. */
    static final String[] PUBLIC_PATHS = {"/healthz", "/actuator/health", "/actuator/health/**"};

    /** The {@code /web} browser UI — protected, but also offered an HTTP Basic challenge. */
    static final String WEB_PATTERN = "/web/**";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AgentMemoryConfig config)
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

        log.info("auth enabled: bearer token required (Basic accepted on {}); public={}, allowedHosts={}",
                WEB_PATTERN, java.util.Arrays.toString(PUBLIC_PATHS), auth.allowedHosts());

        // Authenticate the shared token (Bearer or Basic), then gate cross-origin browser writes.
        http.addFilterBefore(
                new TokenAuthenticationFilter(auth.token()),
                UsernamePasswordAuthenticationFilter.class);
        http.addFilterAfter(new BrowserWriteGuardFilter(), BasicAuthenticationFilter.class);

        http.authorizeHttpRequests(reg -> reg
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .anyRequest().hasRole("AGENT_MEMORY"));

        // 401 with a Basic challenge for /web (so a browser pops a login), a plain 401 elsewhere.
        http.exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint()));
        return http.build();
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
