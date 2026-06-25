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
 * @param decay        layered-memory decay/retention tuning (λ, σ, μ + cold threshold) — #24.
 * @param scope        multi-user scope-isolation tuning (the {@code auto_scope} mode) — #39.
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
        @DefaultValue Ingest ingest,
        @DefaultValue Decay decay,
        @DefaultValue Scope scope) {

    /**
     * HTTP server surface.
     *
     * @param address   bind address; loopback by default (DD-007: loopback-only unless opened up).
     * @param port      TCP port; {@code 0} lets the OS pick an ephemeral port.
     * @param basePath  path prefix all routes mount under (e.g. {@code /} or {@code /agent-memory}).
     * @param webUiDir  optional filesystem directory for a custom {@code /web} SPA build (#36); blank
     *     serves the bundled reference UI from the classpath. The {@code --web-ui-dir} knob.
     */
    public record Server(
            @DefaultValue("127.0.0.1") String address,
            @DefaultValue("8080") int port,
            @DefaultValue("/") String basePath,
            @DefaultValue("") String webUiDir) {
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
     * Server auth (issue #38 / DD-007). Secure-by-default: the single-user laptop runs loopback-only
     * with no auth ({@code enabled=false}); exposing the server is a deliberate opt-in that turns on a
     * bearer token (and HTTP Basic on {@code /web} with the token as the password) plus, on a non-loopback
     * bind, an allowed-hosts guard against DNS rebinding.
     *
     * @param enabled      whether bearer auth is required; {@code false} = loopback-only mode (DD-007).
     * @param token        bearer token expected on protected routes; redacted in {@link #toString()}.
     *     Required when {@code enabled} is true (validated in {@link AgentMemoryConfig}).
     * @param allowedHosts Host-header values permitted on a non-loopback bind (the anti-DNS-rebinding
     *     allow-list, {@code AGENT_MEMORY_AUTH_ALLOWED_HOSTS}). Empty ⇒ only loopback host names are
     *     accepted; a non-loopback bind with an empty list rejects browser requests by Host (the guard).
     *     Compared case-insensitively against the host portion of the {@code Host} header (port ignored).
     * @param tokenPepper Secret mixed into every per-user token hash (issue #39). Setting it to a
     *     non-blank value turns on <strong>multi-user mode</strong>: the {@link #token} becomes the
     *     <em>root</em> token (required for {@code /admin/*}), and per-user tokens (hashed with this
     *     pepper) authenticate as their own identity for everything else. Blank ⇒ single-user mode
     *     ({@link #token} alone). {@code AGENT_MEMORY_AUTH_TOKEN_PEPPER}; redacted in {@link #toString()}.
     */
    public record Auth(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String token,
            @DefaultValue List<String> allowedHosts,
            @DefaultValue("") String tokenPepper) {

        public Auth {
            // Normalize the allow-list to a defensive, lower-cased copy with blanks dropped so the
            // guard's membership test is a simple contains() and never NPEs on an unset list.
            allowedHosts = allowedHosts == null ? List.of()
                    : allowedHosts.stream()
                            .filter(h -> h != null && !h.isBlank())
                            .map(h -> h.trim().toLowerCase(java.util.Locale.ROOT))
                            .toList();
        }

        public boolean hasToken() {
            return token != null && !token.isBlank();
        }

        /** @return whether multi-user mode is on — i.e. a non-blank {@link #tokenPepper} was configured (#39). */
        public boolean multiUser() {
            return tokenPepper != null && !tokenPepper.isBlank();
        }

        @Override
        public String toString() {
            return "Auth[enabled=" + enabled + ", token=" + (hasToken() ? "***" : "<none>")
                    + ", allowedHosts=" + allowedHosts
                    + ", tokenPepper=" + (multiUser() ? "***" : "<none>") + "]";
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

    /**
     * Layered-memory decay / retention tuning (issue #24; ARCHITECTURE §3.3). These are the
     * <strong>single source</strong> of the constants in the retention formula
     * <pre>  score = salience·exp(−λ·Δt_days) + σ·log(1+access_count)·exp(−μ·days_since_access)</pre>
     * shared by recall ranking (#15) and the forget sweep (#25). Per-layer regimes
     * ({@link com.agentmemory.core.MemoryLayer}) decide <em>which</em> terms apply; these decide how
     * steeply they fall. All rates are per-day and must be {@code >= 0} (0 disables that term's
     * decay); {@code coldThreshold} is the retention score at or below which a page is a sweep
     * candidate (#25 owns the sweep action itself — this is only the threshold).
     *
     * @param lambda          age-decay rate λ per day for the salience term ({@code exp(−λ·Δt)});
     *     defaults to {@code 0.02} (≈35-day half-life). Applies only to age-decaying layers.
     * @param sigma           weight σ of the access-reinforcement term; defaults to {@code 1.0}.
     * @param mu              decay rate μ per day on time since last access ({@code exp(−μ·days)});
     *     defaults to {@code 0.01} (≈70-day half-life of a reinforcement bump).
     * @param defaultSalience baseline salience for a page with no explicit salience signal; defaults
     *     to {@code 1.0}. Must be {@code > 0}.
     * @param coldThreshold   retention score at/below which a latest page is "cold" (a sweep
     *     candidate, #25). Defaults to {@code 0.05}. Must be {@code >= 0}.
     * @param hardDeleteAfterDays days a soft-deleted page (sweep stage one) survives before it is
     *     <em>purged</em> (hard-deleted) if still untouched — the recovery window (#25). Defaults to
     *     {@code 30}. Must be {@code >= 0} ({@code 0} = purge as soon as the next sweep runs).
     * @param recentlyAccessedDays a page accessed within this many days is exempt from the sweep even
     *     if its score is cold — the "recently touched survives" guard (#25). Defaults to {@code 7}.
     *     Must be {@code >= 0} ({@code 0} disables the recency guard).
     */
    public record Decay(
            @DefaultValue("0.02") double lambda,
            @DefaultValue("1.0") double sigma,
            @DefaultValue("0.01") double mu,
            @DefaultValue("1.0") double defaultSalience,
            @DefaultValue("0.05") double coldThreshold,
            @DefaultValue("30") int hardDeleteAfterDays,
            @DefaultValue("7") int recentlyAccessedDays) {

        public Decay {
            requireNonNegative("lambda", lambda);
            requireNonNegative("sigma", sigma);
            requireNonNegative("mu", mu);
            requireNonNegative("cold-threshold", coldThreshold);
            if (defaultSalience <= 0 || !Double.isFinite(defaultSalience)) {
                throw new IllegalArgumentException(
                        "agent-memory.decay.default-salience must be a finite value > 0, was "
                                + defaultSalience);
            }
            if (hardDeleteAfterDays < 0) {
                throw new IllegalArgumentException(
                        "agent-memory.decay.hard-delete-after-days must be >= 0, was "
                                + hardDeleteAfterDays);
            }
            if (recentlyAccessedDays < 0) {
                throw new IllegalArgumentException(
                        "agent-memory.decay.recently-accessed-days must be >= 0, was "
                                + recentlyAccessedDays);
            }
        }

        private static void requireNonNegative(String key, double value) {
            if (!(value >= 0) || !Double.isFinite(value)) {
                throw new IllegalArgumentException(
                        "agent-memory.decay." + key + " must be a finite value >= 0, was " + value);
            }
        }
    }

    /**
     * Multi-user scope isolation (issue #39). Governs the <em>default</em> {@code (workspace, project)}
     * an MCP tool resolves to when a call gives no explicit scope (DD-003). Independent of the decay
     * and auth groups so single-user setups can ignore it entirely (the default is the prior behavior).
     *
     * @param auto the {@link AutoScope} mode; defaults to {@link AutoScope#SINGLE_SLOT} (the server's
     *     globally most-recent project — unchanged single-user behavior). {@link AutoScope#PER_ACTOR}
     *     scopes the default to the authenticated user's own most-recent activity. Bound from
     *     {@code agent-memory.scope.auto}.
     */
    public record Scope(@DefaultValue("single_slot") AutoScope auto) {

        public Scope {
            if (auto == null) {
                auto = AutoScope.SINGLE_SLOT;
            }
        }
    }
}
