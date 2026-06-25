package com.agentmemory.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The resolved, validated server configuration — the single object every subsystem reads.
 *
 * <p>Constructed exactly once at startup (invariant #1) by {@link ConfigModule} from the raw
 * {@link AgentMemoryProperties} binding. Unlike that binding, this type performs the IO and
 * cross-field checks the issue requires:
 *
 * <ul>
 *   <li>Resolves {@link AgentMemoryProperties.Data#dir()} to a <strong>canonical absolute</strong>
 *       path (expanding a leading {@code ~}, normalizing {@code .}/{@code ..}), invariant #11.</li>
 *   <li><strong>Creates</strong> the data dir (and its {@code wiki/ raw/ db/ logs/} children) if
 *       missing, and verifies it is a writable directory.</li>
 *   <li><strong>Fails fast</strong> with an actionable {@link ConfigException} when the path is
 *       unusable (a file in the way, not creatable, or not writable).</li>
 * </ul>
 *
 * <p>The instance is immutable and free of Spring annotations so it can be unit-tested directly
 * (see {@code AgentMemoryConfigTest}) without booting a context. Helpers {@link #wikiDir()} etc.
 * give later issues a single place to learn the layout instead of re-deriving it.
 */
public final class AgentMemoryConfig {

    private final AgentMemoryProperties.Server server;
    private final AgentMemoryProperties.Db db;
    private final AgentMemoryProperties.Llm llm;
    private final AgentMemoryProperties.Embeddings embeddings;
    private final AgentMemoryProperties.Auth auth;
    private final AgentMemoryProperties.Sanitization sanitization;
    private final AgentMemoryProperties.Ingest ingest;
    private final AgentMemoryProperties.Decay decay;
    private final Path dataDir;

    private AgentMemoryConfig(AgentMemoryProperties props, Path dataDir) {
        this.server = props.server();
        this.db = props.db();
        this.llm = props.llm();
        this.embeddings = props.embeddings();
        this.auth = props.auth();
        this.sanitization = props.sanitization();
        this.ingest = props.ingest();
        this.decay = props.decay();
        this.dataDir = dataDir;
    }

    /**
     * Resolve raw properties into a validated config, creating the data-dir tree on disk.
     *
     * @param props the once-bound raw properties.
     * @return an immutable, ready-to-use configuration.
     * @throws ConfigException if the configured data dir cannot be made into a writable directory.
     */
    public static AgentMemoryConfig resolve(AgentMemoryProperties props) {
        Path resolved = canonicalizeDataDir(props.data().dir());
        ensureWritableDirectory(resolved);
        ensureLayout(resolved);
        validateAuth(props.auth());
        return new AgentMemoryConfig(props, resolved);
    }

    /**
     * Fail fast on an auth misconfiguration (issue #38): enabling auth without a token would either
     * lock every caller out or accept an empty/blank token, so require a non-blank token whenever
     * {@code enabled} is true. Disabled auth (the loopback default) needs nothing.
     */
    static void validateAuth(AgentMemoryProperties.Auth auth) {
        if (auth.enabled() && !auth.hasToken()) {
            throw new ConfigException(
                    "agent-memory.auth.enabled is true but no token is set. Provide a bearer token "
                            + "(config key 'agent-memory.auth.token' or env AGENT_MEMORY_AUTH_TOKEN), or "
                            + "generate one with '--generate-auth-token'. To run loopback-only without "
                            + "auth, leave agent-memory.auth.enabled unset (false).");
        }
    }

    // --- data-dir resolution -------------------------------------------------------------------

    /**
     * Turn a configured data-dir string (possibly relative or {@code ~}-prefixed) into a canonical
     * absolute path. Existence is not required here — creation happens in {@link #resolve}.
     */
    static Path canonicalizeDataDir(String configured) {
        if (configured == null || configured.isBlank()) {
            throw new ConfigException(
                    "agent-memory.data.dir is empty. Set it to a writable directory "
                            + "(config file key 'agent-memory.data.dir' or env AGENT_MEMORY_DATA_DIR).");
        }
        String expanded = expandHome(configured.trim());
        // toAbsolutePath() anchors a relative path at the process CWD; normalize() folds . and ..
        // We deliberately do not use toRealPath(): the dir may not exist yet, and we do not want to
        // resolve symlinks (the configured path is the canonical identity used in logs/backups).
        return Paths.get(expanded).toAbsolutePath().normalize();
    }

    /** Expand a leading {@code ~} or {@code ~/...} to the OS user home. Other {@code ~} are literal. */
    private static String expandHome(String path) {
        if (path.equals("~")) {
            return System.getProperty("user.home");
        }
        if (path.startsWith("~/") || path.startsWith("~\\")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    private static void ensureWritableDirectory(Path dir) {
        if (Files.exists(dir) && !Files.isDirectory(dir)) {
            throw new ConfigException(
                    "Configured data dir '" + dir + "' exists but is not a directory. "
                            + "Point agent-memory.data.dir at a directory path.");
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new ConfigException(
                    "Could not create data dir '" + dir + "': " + e.getMessage()
                            + ". Check the path and filesystem permissions.", e);
        }
        if (!Files.isWritable(dir)) {
            throw new ConfigException(
                    "Data dir '" + dir + "' is not writable by this process. "
                            + "Grant write permission or choose another agent-memory.data.dir.");
        }
    }

    /** Create the {@code wiki/ raw/ db/ logs/} children (idempotent) so subsystems can assume them. */
    private static void ensureLayout(Path dataDir) {
        for (Path child : new Path[] {
                dataDir.resolve("wiki"),
                dataDir.resolve("raw"),
                dataDir.resolve("db"),
                dataDir.resolve("logs")}) {
            try {
                Files.createDirectories(child);
            } catch (IOException e) {
                throw new ConfigException(
                        "Could not create data-dir subdirectory '" + child + "': " + e.getMessage(), e);
            }
        }
    }

    // --- accessors -----------------------------------------------------------------------------

    /** @return the canonical absolute data dir (created, writable). */
    public Path dataDir() {
        return dataDir;
    }

    /** @return {@code <data_dir>/wiki} — markdown source of truth (one git repo). */
    public Path wikiDir() {
        return dataDir.resolve("wiki");
    }

    /** @return {@code <data_dir>/raw} — immutable raw session archive. */
    public Path rawDir() {
        return dataDir.resolve("raw");
    }

    /** @return {@code <data_dir>/db} — optional local DB artifacts. */
    public Path dbDir() {
        return dataDir.resolve("db");
    }

    /** @return {@code <data_dir>/logs} — rotating tracing output. */
    public Path logsDir() {
        return dataDir.resolve("logs");
    }

    public AgentMemoryProperties.Server server() {
        return server;
    }

    public AgentMemoryProperties.Db db() {
        return db;
    }

    public AgentMemoryProperties.Llm llm() {
        return llm;
    }

    public AgentMemoryProperties.Embeddings embeddings() {
        return embeddings;
    }

    public AgentMemoryProperties.Auth auth() {
        return auth;
    }

    public AgentMemoryProperties.Sanitization sanitization() {
        return sanitization;
    }

    public AgentMemoryProperties.Ingest ingest() {
        return ingest;
    }

    /** @return the layered-memory decay/retention tuning (λ, σ, μ + cold threshold) — #24. */
    public AgentMemoryProperties.Decay decay() {
        return decay;
    }

    @Override
    public String toString() {
        // Nested records redact their own secrets; safe to log in full at startup.
        return "AgentMemoryConfig[dataDir=" + dataDir
                + ", server=" + server
                + ", db=" + db
                + ", llm=" + llm
                + ", embeddings=" + embeddings
                + ", auth=" + auth + "]";
    }
}
