package com.agentmemory.eval;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the executable eval gate (issue #31), bound from {@code agent-memory.auto-improve.eval}
 * (the {@code [auto_improve.eval]} block). A project-supplied external validator that runs over a
 * high-stakes proposal <em>after</em> LLM validation and <em>before</em> the auto-improve loop (#30)
 * stages/approves it — defense in depth, off by default, never on a hook path.
 *
 * @param enabled        master switch; {@code false} (the default) means the gate never runs and every
 *                       proposal is {@link EvalVerdict.Decision#SKIPPED skipped}.
 * @param prefixes       page-path prefixes the gate applies to (default {@code _rules/}, {@code procedures/});
 *                       a proposal whose path matches none is skipped.
 * @param command        the external command's argv (e.g. {@code [/usr/bin/python3, /opt/gate.py]});
 *                       empty (the default) disables the gate even when {@code enabled}. {@code command[0]}
 *                       should be an absolute path — the gate runs with a scrubbed environment (see
 *                       {@link EvalGate}).
 * @param timeout        hard wall-clock budget for one gate run; on expiry the process is killed and the
 *                       proposal is blocked (fail-closed). Must be {@code > 0}; default 10s.
 * @param maxOutputBytes cap on captured stdout/stderr to bound memory against a runaway gate; default 64 KiB.
 * @param workingDir     optional working directory for the gate process; {@code null}/blank inherits the
 *                       server's.
 */
@ConfigurationProperties("agent-memory.auto-improve.eval")
public record EvalGateProperties(
        boolean enabled,
        List<String> prefixes,
        List<String> command,
        Duration timeout,
        int maxOutputBytes,
        String workingDir) {

    private static final List<String> DEFAULT_PREFIXES = List.of("_rules/", "procedures/");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;

    public EvalGateProperties {
        prefixes = (prefixes == null || prefixes.isEmpty())
                ? DEFAULT_PREFIXES
                : List.copyOf(prefixes);
        command = (command == null) ? List.of() : List.copyOf(command);
        timeout = (timeout == null) ? DEFAULT_TIMEOUT : timeout;
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(
                    "agent-memory.auto-improve.eval.timeout must be > 0, was " + timeout);
        }
        if (maxOutputBytes <= 0) {
            maxOutputBytes = DEFAULT_MAX_OUTPUT_BYTES;
        }
        workingDir = (workingDir == null || workingDir.isBlank()) ? null : workingDir.strip();
    }

    /** @return {@code true} only when the gate is switched on and a command is configured to run. */
    public boolean active() {
        return enabled && !command.isEmpty();
    }

    /**
     * @param path a proposal's page path (e.g. {@code _rules/security.md}).
     * @return {@code true} when the path starts with one of the configured {@link #prefixes}.
     */
    public boolean appliesTo(String path) {
        if (path == null) {
            return false;
        }
        String p = path.strip().toLowerCase(Locale.ROOT);
        for (String prefix : prefixes) {
            if (p.startsWith(prefix.strip().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** @return the configured working directory, or empty to inherit the server's. */
    public Optional<Path> workingDirectory() {
        return workingDir == null ? Optional.empty() : Optional.of(Path.of(workingDir));
    }
}
