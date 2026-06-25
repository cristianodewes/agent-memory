package com.agentmemory.wiki;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets the {@link WikiFileWatcher} distinguish the app's own writes from genuine external edits, so
 * reconciling external edits never loops back into the app's own commits (invariant #10: "the
 * watcher ignores its own writes").
 *
 * <p>Mechanism (the "expected-hash set" the issue calls for): right before the {@link WikiWriter}
 * atomically writes a file, it records the SHA-256 of the bytes it is about to write, keyed by the
 * absolute file path. When the watcher later observes a change to that path, it hashes the current
 * file contents; if the hash matches a recorded expectation, the change is the app's own write —
 * the expectation is consumed and the event ignored. Any other content is an external edit and is
 * reconciled. Matching by content hash (not just a path flag) is robust to event coalescing and to
 * an external edit that races in right after a self-write.
 *
 * <p>Thread-safe: writes happen on the store's write path, observations on the watcher thread.
 */
public final class SelfWriteTracker {

    // path -> the hash this app most recently wrote there and has not yet seen echoed back.
    private final Map<Path, String> expected = new ConcurrentHashMap<>();

    /**
     * Record that the app just wrote {@code contentHash} to {@code file}. A subsequent watcher event
     * whose current file hash equals this is recognized as a self-write.
     *
     * @param file        the absolute file path written.
     * @param contentHash the SHA-256 of the bytes written.
     */
    public void recordWrite(Path file, String contentHash) {
        expected.put(normalize(file), contentHash);
    }

    /**
     * Decide whether an observed change is the app's own write. If {@code currentHash} matches the
     * recorded expectation for {@code file}, the expectation is <strong>consumed</strong> and this
     * returns {@code true} (skip it); otherwise returns {@code false} (an external edit to reconcile).
     *
     * @param file        the changed file (absolute).
     * @param currentHash the SHA-256 of the file's current contents.
     * @return {@code true} if this change is a self-write and should be ignored.
     */
    public boolean isSelfWrite(Path file, String currentHash) {
        Path key = normalize(file);
        String want = expected.get(key);
        if (want != null && want.equals(currentHash)) {
            expected.remove(key, want);
            return true;
        }
        return false;
    }

    /** Forget any pending expectation for a path (e.g. on delete). */
    public void forget(Path file) {
        expected.remove(normalize(file));
    }

    private static Path normalize(Path p) {
        return p.toAbsolutePath().normalize();
    }
}
