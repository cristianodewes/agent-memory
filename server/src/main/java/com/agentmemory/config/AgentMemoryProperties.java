package com.agentmemory.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The single, typed, immutable binding of all server settings, loaded exactly once at startup
 * (invariant #1) under the {@code agent-memory} prefix.
 *
 * <p>Constructor (record) binding gives us immutability for free and lets every group carry its
 * own built-in defaults via {@link DefaultValue}. Precedence is Spring Boot's relaxed binding:
 * environment variables (e.g. {@code AGENT_MEMORY_SERVER_PORT}) override the external
 * {@code agent-memory.yml} file, which overrides these defaults.
 *
 * <p>This raw binding is deliberately thin: it does no IO and no cross-field validation. The
 * resolved, validated, side-effecting view lives in {@link AgentMemoryConfig}, which canonicalizes
 * and creates the data directory and fails fast on invalid input. Keeping the two apart honors the
 * §6 rule that {@code core}/binding types stay IO-free.
 *
 * @param server     HTTP bind + routing settings.
 * @param data       on-disk data directory root.
 * @param db         Postgres connection settings (the derived index).
 * @param llm          chat/consolidation provider stub — full wiring in #6.
 * @param embeddings   embeddings provider stub (separate axis from {@link #llm}) — full wiring in #6.
 * @param auth         server auth stub — enforcement in #38.
 * @param sanitization privacy-strip tuning (size cap + custom patterns) — boundary in #9.
 * @param ingest       /hook ingest backpressure tuning (bounded queue + worker) — #8.
 */
@ConfigurationProperties(prefix = "agent-memory")
public record AgentMemoryProperties(
        @DefaultValue Server server,
        @DefaultValue Data data,
        @DefaultValue Db db,
        @DefaultValue Llm llm,
        @DefaultValue Embeddings embeddings,
        @DefaultValue Auth auth,
        @DefaultValue Sanitization sanitization,
        @DefaultValue Ingest ingest) {

    /**
     * HTTP server surface.
     *
     * @param address   bind address; loopback by default (DD-007: loopback-only unless opened up).
     * @param port      TCP port; {@code 0} lets the OS pick an ephemeral port.
     * @param basePath  path prefix all routes mount under (e.g. {@code /} or {@code /agent-memory}).
     */
    public record Server(
            @DefaultValue("127.0.0.1") String address,
            @DefaultValue("8080") int port,
            @DefaultValue("/") String basePath) {
    }

    /**
     * Data directory root. Resolution + creation happen in {@link AgentMemoryConfig}; this only
     * carries the (possibly relative, possibly {@code ~}-prefixed) configured value.
     *
     * @param dir configured data dir; defaults to {@code ~/.agent-memory} expanded at resolve time.
     */
    public record Data(@DefaultValue("~/.agent-memory") String dir) {
    }

    /**
     * Postgres connection — the derived FTS + pgvector index (DD-004). Connectivity is not
     * validated here (schema/wiring is #4); this is the typed surface other layers read.
     *
     * @param url      JDBC URL.
     * @param username database user.
     * @param password database password (redacted in {@link #toString()}).
     */
    public record Db(
            @DefaultValue("jdbc:postgresql://127.0.0.1:5432/agent_memory") String url,
            @DefaultValue("agent_memory") String username,
            @DefaultValue("") String password) {

        @Override
        public String toString() {
            return "Db[url=" + url + ", username=" + username
                    + ", password=" + (password == null || password.isBlank() ? "<none>" : "***") + "]";
        }
    }

    /**
     * Chat / consolidation LLM provider (required at runtime per DD-005, but <em>validated</em> in
     * #6 — not here). The nested {@link ProviderAuth} is the placeholder #6 fills.
     *
     * @param auth typed provider credentials, resolved before client construction (invariant #14).
     */
    public record Llm(@DefaultValue ProviderAuth auth) {
        public Llm {
            if (auth == null) {
                auth = ProviderAuth.NONE;
            }
        }
    }

    /**
     * Embeddings provider — a separate, default-on axis from {@link Llm} (DD-005). If unavailable
     * at runtime, recall degrades to FTS + graph; that degradation logic is #6, not #2.
     *
     * @param auth typed provider credentials, resolved before client construction (invariant #14).
     */
    public record Embeddings(@DefaultValue ProviderAuth auth) {
        public Embeddings {
            if (auth == null) {
                auth = ProviderAuth.NONE;
            }
        }
    }

    /**
     * Server auth stub. Enforcement (bearer token on {@code /mcp} etc., allowed-hosts guard) is
     * #38; here we only carry the toggle + token so call sites compile against a typed value.
     *
     * @param enabled whether bearer auth is required; {@code false} = loopback-only mode (DD-007).
     * @param token   bearer token expected on protected routes; redacted in {@link #toString()}.
     */
    public record Auth(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String token) {

        public boolean hasToken() {
            return token != null && !token.isBlank();
        }

        @Override
        public String toString() {
            return "Auth[enabled=" + enabled + ", token=" + (hasToken() ? "***" : "<none>") + "]";
        }
    }

    /**
     * Privacy sanitization tuning (issue #9 / DD-010 / invariant #6). The built-in redactors
     * (keys/tokens, emails, home-dir paths) are always on; these settings add a size cap and any
     * site-specific patterns on top.
     *
     * @param maxPayloadChars hard cap on a stored observation payload; longer payloads are truncated
     *     deterministically with a marker. Must be {@code > 0}; defaults to 64 KiB.
     * @param customPatterns  extra Java regexes whose matches are redacted (e.g. an internal
     *     employee-id or ticket format). Each must compile; applied after the built-ins.
     */
    public record Sanitization(
            @DefaultValue("65536") int maxPayloadChars,
            @DefaultValue List<CustomPattern> customPatterns) {

        public Sanitization {
            if (maxPayloadChars <= 0) {
                throw new IllegalArgumentException(
                        "agent-memory.sanitization.max-payload-chars must be > 0, was " + maxPayloadChars);
            }
            customPatterns = customPatterns == null ? List.of() : List.copyOf(customPatterns);
        }

        /**
         * One configurable redaction pattern.
         *
         * @param regex a valid Java regex; every match is replaced. Never null/blank.
         * @param label short marker label, e.g. {@code "ticket"} → {@code [REDACTED:ticket]};
         *     defaults to {@code custom}.
         */
        public record CustomPattern(String regex, @DefaultValue("custom") String label) {
            public CustomPattern {
                if (regex == null || regex.isBlank()) {
                    throw new IllegalArgumentException(
                            "agent-memory.sanitization.custom-patterns[].regex must not be blank");
                }
                if (label == null || label.isBlank()) {
                    label = "custom";
                }
            }
        }
    }

    /**
     * {@code /hook} ingest backpressure (issue #8 / invariant #5). Hooks are fire-and-forget: the
     * server accepts an event into a <strong>bounded</strong> queue and a single worker drains it to
     * the writer. When the queue is full the server replies {@code 429} rather than enqueuing
     * unbounded work or blocking the caller.
     *
     * @param queueCapacity max events buffered between the HTTP handler and the single writer. Must
     *     be {@code > 0}; defaults to 1024. A full queue ⇒ {@code 429}.
     * @param offerTimeoutMillis how long the handler may wait to enqueue before giving up with
     *     {@code 429}. {@code 0} = never block (pure non-blocking offer); the default. Kept small so a
     *     slow store can never stall the caller (the "hard request budget").
     */
    public record Ingest(
            @DefaultValue("1024") int queueCapacity,
            @DefaultValue("0") long offerTimeoutMillis) {

        public Ingest {
            if (queueCapacity <= 0) {
                throw new IllegalArgumentException(
                        "agent-memory.ingest.queue-capacity must be > 0, was " + queueCapacity);
            }
            if (offerTimeoutMillis < 0) {
                throw new IllegalArgumentException(
                        "agent-memory.ingest.offer-timeout-millis must be >= 0, was " + offerTimeoutMillis);
            }
        }
    }
}
