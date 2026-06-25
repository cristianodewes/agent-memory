package com.agentmemory.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a project-supplied <strong>executable eval gate</strong> over a self-improvement proposal
 * (issue #31; ARCHITECTURE §2.2 self-improvement, Survey §2.3). The auto-improve loop (#30) calls this
 * after its LLM validation and before staging/approval; a {@link EvalVerdict.Decision#BLOCKED} verdict
 * stops the proposal. Off by default, configurable per prefix, and never invoked on a hook path.
 *
 * <h2>Contract (JSON in / JSON out)</h2>
 * The gate command receives the proposal as one JSON object on <strong>stdin</strong>:
 * <pre>{"version":"eval-gate/v1","action":"upsert","path":"_rules/x.md","title":"…","body":"…"}</pre>
 * and must print its verdict as one JSON object on <strong>stdout</strong> and exit {@code 0}:
 * <pre>{"pass":true|false,"reasons":["…"]}</pre>
 * Documented in {@code docs/contracts/eval-gate.md}.
 *
 * <h2>Untrusted-command discipline</h2>
 * The command is treated as untrusted (acceptance criteria + DD): it runs with a <strong>scrubbed
 * environment</strong> — a clean slate plus only a minimal infrastructure allowlist (PATH and the OS
 * loader vars), so none of the server's secrets (API keys, tokens) are inherited — its stdout/stderr
 * are captured under a byte cap, and it is bounded by a hard {@link EvalGateProperties#timeout()}.
 *
 * <h2>Fail-closed</h2>
 * Anything that prevents a trustworthy {@code pass:true} — a timeout (the process is force-killed), a
 * non-zero exit, empty/unparseable output, a missing {@code pass} field, or a start/IO failure — blocks
 * the proposal with a clear reason. Only an explicit {@code pass:true} lets it through.
 *
 * <p>Stateless and thread-safe given its immutable {@link EvalGateProperties}.
 */
public final class EvalGate {

    /** The stdin contract version sent to the gate. */
    static final String CONTRACT_VERSION = "eval-gate/v1";

    private static final Logger log = LoggerFactory.getLogger(EvalGate.class);

    /**
     * Environment variables passed through to the (untrusted) gate process; everything else the server
     * inherited — including all secrets — is dropped. Kept minimal: just what an OS needs to spawn a
     * process and what an interpreter typically needs to resolve its tools. Compared case-insensitively.
     */
    private static final Set<String> ENV_ALLOWLIST = Set.of(
            "PATH", "PATHEXT", "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "COMSPEC",
            "TEMP", "TMP", "TMPDIR", "HOME", "LANG", "LC_ALL", "TZ", "NUMBER_OF_PROCESSORS", "OS");

    /** Grace period to reap a force-killed process and to collect its already-EOF output streams. */
    private static final long REAP_SECONDS = 2;

    private final ObjectMapper json = JsonMapper.builder().build();
    private final EvalGateProperties props;

    public EvalGate(EvalGateProperties props) {
        if (props == null) {
            throw new IllegalArgumentException("eval gate properties must not be null");
        }
        this.props = props;
    }

    /**
     * Evaluate one proposal through the gate.
     *
     * @param proposal the proposal to validate; never null.
     * @return the verdict — {@link EvalVerdict.Decision#SKIPPED} when the gate does not apply,
     *     otherwise {@code PASSED}/{@code BLOCKED}. Never null; blocks rather than throws on failure.
     */
    public EvalVerdict evaluate(EvalProposal proposal) {
        if (proposal == null) {
            throw new IllegalArgumentException("proposal must not be null");
        }
        if (!props.active() || !props.appliesTo(proposal.path())) {
            return EvalVerdict.skipped();
        }
        String requestJson;
        try {
            requestJson = toRequestJson(proposal);
        } catch (RuntimeException e) {
            log.warn("eval gate: could not serialize proposal '{}': {}", proposal.path(), e.toString());
            return EvalVerdict.blocked("eval gate could not serialize the proposal");
        }
        return runGate(proposal, requestJson);
    }

    private EvalVerdict runGate(EvalProposal proposal, String requestJson) {
        ProcessBuilder pb = new ProcessBuilder(props.command());
        scrubEnvironment(pb.environment());
        pb.redirectErrorStream(false);
        props.workingDirectory().ifPresent(dir -> pb.directory(dir.toFile()));

        Process process;
        try {
            process = pb.start();
        } catch (IOException | RuntimeException e) {
            log.warn("eval gate: could not start command {} for '{}': {}",
                    props.command(), proposal.path(), e.toString());
            return EvalVerdict.blocked("eval gate command could not be started");
        }

        ExecutorService io = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "eval-gate-io");
            t.setDaemon(true);
            return t;
        });
        try {
            // Drain stdout/stderr concurrently so a chatty gate can never deadlock on a full pipe buffer.
            Future<byte[]> stdout = io.submit(() -> readCapped(process.getInputStream(), props.maxOutputBytes()));
            Future<byte[]> stderr = io.submit(() -> readCapped(process.getErrorStream(), props.maxOutputBytes()));

            // Hand the proposal to the gate on stdin, then close it (EOF) so a reading gate can proceed.
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(requestJson.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // The gate may have exited before reading stdin; let waitFor/exit code decide the verdict.
                log.debug("eval gate: writing stdin failed for '{}': {}", proposal.path(), e.toString());
            }

            boolean finished = process.waitFor(props.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(REAP_SECONDS, TimeUnit.SECONDS);
                log.warn("eval gate: '{}' timed out after {} - blocking (fail-closed)",
                        proposal.path(), props.timeout());
                return EvalVerdict.blocked("eval gate timed out after " + props.timeout());
            }

            int exit = process.exitValue();
            if (exit != 0) {
                log.warn("eval gate: '{}' exited {} - blocking (fail-closed). stderr: {}",
                        proposal.path(), exit, snippet(text(stderr)));
                return EvalVerdict.blocked("eval gate exited with status " + exit);
            }
            return parseVerdict(proposal, text(stdout));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return EvalVerdict.blocked("eval gate run was interrupted");
        } finally {
            io.shutdownNow();
        }
    }

    private EvalVerdict parseVerdict(EvalProposal proposal, String stdout) {
        if (stdout == null || stdout.isBlank()) {
            log.warn("eval gate: '{}' produced no output - blocking (fail-closed)", proposal.path());
            return EvalVerdict.blocked("eval gate produced no verdict");
        }
        JsonNode root;
        try {
            root = json.readTree(stdout);
        } catch (JacksonException e) {
            log.warn("eval gate: '{}' verdict was not valid JSON - blocking (fail-closed): {}",
                    proposal.path(), e.getMessage());
            return EvalVerdict.blocked("eval gate verdict was not valid JSON");
        }
        if (!root.isObject() || !root.has("pass") || !root.get("pass").isBoolean()) {
            log.warn("eval gate: '{}' verdict missing boolean 'pass' - blocking (fail-closed)",
                    proposal.path());
            return EvalVerdict.blocked("eval gate verdict missing boolean 'pass'");
        }
        List<String> reasons = readReasons(root.get("reasons"));
        if (root.get("pass").booleanValue()) {
            return EvalVerdict.passed(reasons);
        }
        return EvalVerdict.blocked(reasons.isEmpty() ? List.of("eval gate rejected the proposal") : reasons);
    }

    private String toRequestJson(EvalProposal p) {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("version", CONTRACT_VERSION);
        req.put("action", p.action());
        req.put("path", p.path());
        req.put("title", p.title());
        req.put("body", p.body());
        return json.writeValueAsString(req);
    }

    /**
     * Replace the inherited (secret-bearing) environment with a clean slate plus only the
     * {@link #ENV_ALLOWLIST} infrastructure vars actually present — so the untrusted gate inherits no
     * server secrets but can still be spawned and run an interpreter.
     */
    private static void scrubEnvironment(Map<String, String> env) {
        Map<String, String> inherited = new HashMap<>(env);
        env.clear();
        for (Map.Entry<String, String> e : inherited.entrySet()) {
            if (e.getKey() != null && ENV_ALLOWLIST.contains(e.getKey().toUpperCase(Locale.ROOT))) {
                env.put(e.getKey(), e.getValue());
            }
        }
    }

    private static List<String> readReasons(JsonNode reasons) {
        if (reasons == null || !reasons.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode r : reasons) {
            if (r != null && r.isString() && !r.stringValue().isBlank()) {
                out.add(r.stringValue());
            }
        }
        return out;
    }

    /** Read a stream into a byte[] capped at {@code maxBytes}, but keep draining (discarding the excess)
     * so the child never blocks on a full pipe. Returns what was read; swallows IO errors (e.g. the
     * process was killed) returning the partial bytes. */
    private static byte[] readCapped(InputStream in, int maxBytes) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int kept = 0;
        try {
            int n;
            while ((n = in.read(chunk)) != -1) {
                if (kept < maxBytes) {
                    int room = Math.min(n, maxBytes - kept);
                    buf.write(chunk, 0, room);
                    kept += room;
                }
            }
        } catch (IOException e) {
            // Stream closed early (process destroyed/exited); return the bytes gathered so far.
        }
        return buf.toByteArray();
    }

    /** Resolve a drain future to text, bounded by a short reap window (the stream is at EOF once the
     * process has finished, so this returns promptly). */
    private static String text(Future<byte[]> future) {
        try {
            return new String(future.get(REAP_SECONDS, TimeUnit.SECONDS), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException | TimeoutException e) {
            return "";
        }
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return t.length() <= 300 ? t : t.substring(0, 300) + "...";
    }
}
