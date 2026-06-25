package com.agentmemory.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;

/**
 * Bridges the single config knob {@code agent-memory.server.base-path} (issue #2's
 * {@code AgentMemoryProperties.Server#basePath}) onto Spring Boot's servlet context path so that
 * <strong>all</strong> HTTP surfaces move together behind a reverse proxy (issue #35 acceptance:
 * "{@code --base-path} relocates {@code /api/v1}, {@code /mcp}, {@code /web} consistently").
 *
 * <p>Setting {@code server.servlet.context-path} prefixes every servlet-mapped route uniformly — the
 * {@code /api/v1} controllers, the {@code /mcp} streamable-HTTP servlet, the {@code /web} UI (#36),
 * and {@code /healthz} — so there is exactly one place that defines the mount point and no surface can
 * drift. Doing it in an {@link EnvironmentPostProcessor} (rather than a {@code @Bean}) means the value
 * is in the {@link org.springframework.core.env.Environment} before the servlet container is
 * configured, which is when the context path must be known.
 *
 * <p>The base path is normalized to the form Spring expects: a leading slash, no trailing slash, and
 * the root value {@code "/"} (the default) contributes nothing — leaving the container at its own
 * default empty context path. The Go client is unaffected: it targets a server URL that already
 * includes any proxy prefix, so no client change is required for relocation (the client's full config
 * surface is #2/#32).
 *
 * <p>This runs even in the DB-less smoke context (it only reads/writes the environment), so the
 * relocation is testable without a database.
 */
public class BasePathEnvironmentPostProcessor implements EnvironmentPostProcessor {

    /** The config key carrying the desired mount point (issue #2). */
    static final String BASE_PATH_PROPERTY = "agent-memory.server.base-path";

    /** The Spring Boot property that actually relocates every servlet surface. */
    static final String CONTEXT_PATH_PROPERTY = "server.servlet.context-path";

    /** Name of the synthetic property source this processor contributes. */
    static final String SOURCE_NAME = "agentMemoryBasePath";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
        String configured = environment.getProperty(BASE_PATH_PROPERTY);
        String normalized = normalize(configured);
        if (normalized == null) {
            return; // default "/" (or unset): leave the container's own context path untouched.
        }
        // Do not override an explicitly-set server.servlet.context-path (operator's direct override
        // wins); only contribute when the bridged value is the sole source of truth.
        if (environment.containsProperty(CONTEXT_PATH_PROPERTY)) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource(
                SOURCE_NAME, Map.of(CONTEXT_PATH_PROPERTY, normalized)));
    }

    /**
     * Normalize a configured base path to a servlet context path, or {@code null} when it should not
     * be applied (blank, or the root {@code "/"}).
     *
     * @param raw the configured value (may be null/blank).
     * @return a context path like {@code /agent-memory}, or {@code null} to leave the default.
     */
    static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.equals("/")) {
            return null;
        }
        // Collapse to a single leading slash and strip any trailing slash(es).
        String withLead = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        int end = withLead.length();
        while (end > 1 && withLead.charAt(end - 1) == '/') {
            end--;
        }
        return withLead.substring(0, end);
    }
}
