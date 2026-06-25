package com.agentmemory.wiki;

import com.agentmemory.core.Identity;
import com.agentmemory.core.Observation;
import com.agentmemory.store.ObservationSideEffect;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;

/**
 * The per-session human-readable ledger and immutable raw archive (issue #11; ARCHITECTURE §4.1).
 * For every <em>accepted</em> observation the single writer (#8) calls {@link #record}, which does
 * two side effects alongside the DB row, inside the writer's transaction:
 *
 * <ol>
 *   <li><b>Append one line</b> {@code ## [<timestamp>] <event> | <title>} to
 *       {@code wiki/<workspace>/<project>/log.md} — an append-only ledger, never re-rendered
 *       (invariant #10; the issue's "keep log.md cheap"). The append is atomic and ordered: it is
 *       written through a {@link FileChannel} opened in {@link StandardOpenOption#APPEND APPEND} mode
 *       under an exclusive {@link FileLock}, so two concurrent sessions writing the <em>same</em>
 *       project's {@code log.md} never interleave a half-line.</li>
 *   <li><b>Write the full sanitized event</b> once to the immutable archive
 *       {@code raw/<workspace>/<project>/<sessionId>/<observationId>.json}. The file is created with
 *       {@link StandardOpenOption#CREATE_NEW CREATE_NEW} so the application can never mutate or
 *       overwrite an existing raw entry in place — write-once by construction.</li>
 * </ol>
 *
 * <p><b>Commit coordination (DD-002, #13).</b> This type does <em>not</em> git-commit per event —
 * that would contradict "keep log.md cheap" and duplicate the commit-on-write cadence that #13 owns
 * (issue #11 lists git commit cadence as out of scope). {@code log.md} lives under {@code wiki/}, so
 * it is carried into git history by the next wiki commit (#13's commit-on-write at
 * consolidation/session-end). The {@code raw/} archive lives <em>outside</em> the wiki git tree (an
 * immutable forensic archive, not versioned source) and is never committed.
 *
 * <p>Because {@link #record} runs on the single-writer path (one ingest worker, serialized by the
 * writer's lock) and inside the DB transaction <em>before</em> it commits, a failure here rolls the
 * observation row back — the row, the ledger line and the raw entry are one logical operation; the DB
 * index never claims an event whose ledger/archive write failed.
 */
public final class SessionLog implements ObservationSideEffect {

    /** The session ledger file name under each {@code wiki/<ws>/<project>/} directory. */
    public static final String LOG_FILE_NAME = "log.md";

    /** Timestamps render as RFC-3339 / ISO-8601 instant (UTC {@code Z}), stable and sortable. */
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_INSTANT;

    /** Hard cap on the derived title so a long payload cannot bloat a ledger line. */
    private static final int MAX_TITLE_CHARS = 120;

    private final WikiPaths wikiPaths;
    private final Path rawDir;

    /**
     * Serializes appends within this JVM. In production every append already arrives on the single
     * ingest writer thread, but this makes {@link SessionLog} self-safe: a JVM may not hold two
     * overlapping {@link FileLock}s at once ({@code OverlappingFileLockException}), so concurrent
     * callers must be serialized here before the OS-level lock is taken. The OS {@link FileLock}
     * remains the cross-process guard.
     */
    private final java.util.concurrent.locks.ReentrantLock appendLock =
            new java.util.concurrent.locks.ReentrantLock();

    /**
     * @param wikiPaths resolves {@code wiki/<ws>/<project>} directories (shares the #13 layout).
     * @param rawDir    the immutable {@code <data_dir>/raw} archive root.
     */
    public SessionLog(WikiPaths wikiPaths, Path rawDir) {
        this.wikiPaths = wikiPaths;
        this.rawDir = rawDir.toAbsolutePath().normalize();
    }

    /**
     * {@link ObservationSideEffect} entry point — invoked by the single writer inside its transaction
     * after a new row is inserted. Delegates to {@link #record}.
     */
    @Override
    public void afterObservationWritten(Observation persisted) throws IOException {
        record(persisted);
    }

    /**
     * Append the ledger line and write the raw archive entry for one freshly-persisted observation.
     * Call exactly once per genuinely-inserted observation (never on an idempotent replay, or the
     * ledger/archive would duplicate).
     *
     * @param observation the stored observation (server-assigned id, project-scoped identity,
     *     sanitized payload).
     * @throws IOException if a filesystem write fails (the caller rolls the DB row back).
     */
    public void record(Observation observation) throws IOException {
        Identity identity = observation.identity();
        appendLogLine(identity, observation);
        writeRawEntry(identity, observation);
    }

    // --- log.md ledger -------------------------------------------------------------------------

    private void appendLogLine(Identity identity, Observation obs) throws IOException {
        Path projectDir = projectDir(identity);
        Files.createDirectories(projectDir);
        Path logFile = projectDir.resolve(LOG_FILE_NAME);

        String line = renderLine(obs);
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);

        // Serialize within the JVM first (a JVM may hold only one overlapping FileLock at a time),
        // then take the OS lock for cross-process safety. APPEND mode makes each write land at the
        // current end-of-file, so lines never interleave (invariant #10).
        appendLock.lock();
        try (FileChannel ch = FileChannel.open(
                logFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                FileLock ignored = ch.lock()) {
            ch.write(java.nio.ByteBuffer.wrap(bytes));
            ch.force(true); // durable before the row commits, so a crash cannot lose a committed line
        } finally {
            appendLock.unlock();
        }
        // No git commit here: log.md rides into history on the next wiki commit-on-write (#13).
    }

    /** Render {@code ## [<timestamp>] <event> | <title>}, omitting the title when there is none. */
    private String renderLine(Observation obs) {
        String event = eventLabel(obs);
        String title = deriveTitle(obs.payload());
        StringBuilder sb = new StringBuilder()
                .append("## [").append(TS.format(obs.createdAt())).append("] ")
                .append(event);
        if (!title.isEmpty()) {
            sb.append(" | ").append(title);
        }
        return sb.append('\n').toString();
    }

    /** The raw agent-native event name if present, else the canonical kind's wire token. */
    private static String eventLabel(Observation obs) {
        String src = obs.sourceEvent();
        if (src != null && !src.isBlank()) {
            return sanitizeForLine(src);
        }
        return obs.kind().wire();
    }

    /**
     * Derive a one-line title from the sanitized payload: take the first non-blank line, strip a
     * leading {@code "title: "} label (the flattened payload puts the client title there), collapse
     * inner whitespace, and cap the length. Returns empty when the payload has no usable text — the
     * ledger line then carries just the timestamp + event.
     */
    private static String deriveTitle(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        String firstLine = "";
        for (String l : payload.split("\n", -1)) {
            if (!l.isBlank()) {
                firstLine = l.strip();
                break;
            }
        }
        if (firstLine.isEmpty()) {
            return "";
        }
        if (firstLine.regionMatches(true, 0, "title:", 0, "title:".length())) {
            firstLine = firstLine.substring("title:".length()).strip();
        }
        String oneLine = sanitizeForLine(firstLine);
        if (oneLine.length() > MAX_TITLE_CHARS) {
            oneLine = oneLine.substring(0, MAX_TITLE_CHARS - 1).stripTrailing() + "…";
        }
        return oneLine;
    }

    /** Flatten any CR/LF/tab to single spaces so a value can never break the one-line-per-event ledger. */
    private static String sanitizeForLine(String s) {
        return s.replaceAll("[\\r\\n\\t]+", " ").strip();
    }

    // --- raw/ immutable archive ----------------------------------------------------------------

    private void writeRawEntry(Identity identity, Observation obs) throws IOException {
        Path dir = rawDir
                .resolve(identity.workspace().value())
                .resolve(identity.project().value())
                .resolve(obs.sessionId().value().toString());
        Files.createDirectories(dir);
        Path file = dir.resolve(obs.id().value().toString() + ".json");

        byte[] bytes = renderRawJson(obs).getBytes(StandardCharsets.UTF_8);

        // CREATE_NEW: refuse to overwrite an existing entry — raw/ is write-once (the app never edits
        // in place). One genuinely-new observation has a fresh UUIDv7 id, so this never collides.
        try (FileChannel ch = FileChannel.open(
                file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ch.write(java.nio.ByteBuffer.wrap(bytes));
            ch.force(true);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            // Defensive: a raw entry for this id already exists. Never mutate it; surface for rollback.
            throw new IOException("raw archive entry already exists (write-once violation): " + file, e);
        }
    }

    /**
     * Serialize the full sanitized event to JSON for the archive. Hand-rolled (no Jackson dependency
     * needed here) and stable: the structural fields plus the already-sanitized payload — the
     * faithful raw event as it crossed the privacy boundary.
     */
    private static String renderRawJson(Observation obs) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        field(sb, "id", obs.id().value().toString()).append(',');
        field(sb, "sessionId", obs.sessionId().value().toString()).append(',');
        field(sb, "workspace", obs.identity().workspace().value()).append(',');
        field(sb, "project", obs.identity().project().value()).append(',');
        field(sb, "kind", obs.kind().wire()).append(',');
        nullableField(sb, "sourceEvent", obs.sourceEvent()).append(',');
        nullableField(sb, "extension", obs.extension()).append(',');
        field(sb, "payload", obs.payload()).append(',');
        field(sb, "createdAt", TS.format(obs.createdAt()));
        return sb.append('}').toString();
    }

    private static StringBuilder field(StringBuilder sb, String key, String value) {
        return sb.append(jsonString(key)).append(':').append(jsonString(value));
    }

    private static StringBuilder nullableField(StringBuilder sb, String key, String value) {
        sb.append(jsonString(key)).append(':');
        return value == null ? sb.append("null") : sb.append(jsonString(value));
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Resolve {@code wiki/<ws>/<project>} for a project-scoped observation identity. */
    private Path projectDir(Identity identity) {
        return wikiPaths.wikiDir()
                .resolve(identity.workspace().value())
                .resolve(identity.project().value());
    }

    /** Minimal RFC-8259 JSON string encoder (quotes + escapes control chars). */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
