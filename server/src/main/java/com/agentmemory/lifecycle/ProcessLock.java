package com.agentmemory.lifecycle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The data-dir liveness marker behind the destructive-op guard (invariant #9; issue #33). The running
 * server {@link #acquire()}s an exclusive {@link FileLock} on {@code <dataDir>/agent-memory.pid} at
 * startup and stamps the file with its PID; the lock is held for the process lifetime and released on
 * {@link #close()}. A second server starting against the same data dir fails fast on the lock.
 *
 * <p>The destructive ops ({@code reset}, #33) consult {@link #detectLiveHolder(Path, long)} before
 * wiping anything: if the pid file names a <em>live</em> process (its PID is still running) other than
 * the caller, the op is refused. PID liveness is checked via {@link ProcessHandle}, which is portable
 * (Windows included) and does not rely on cross-process advisory-lock probing.
 *
 * <p>Why a stamped PID file rather than only a lock: the {@code reset} caller may be the running server
 * itself (a {@code POST /reset} to a live instance), and a process cannot meaningfully test its own
 * advisory lock; reading the recorded PID and comparing liveness/identity answers "is a holder alive,
 * and is it me" cleanly.
 */
public final class ProcessLock implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ProcessLock.class);

    /** The marker file name under the data dir. */
    public static final String PID_FILE_NAME = "agent-memory.pid";

    /**
     * The byte offset of the advisory-lock sentinel. We lock a single byte <em>past</em> any content
     * (rather than the whole file) so the lock is a pure cross-process mutex that does NOT block reads
     * of the pid content — on Windows a {@link FileLock} is mandatory, so an exclusive whole-file lock
     * would make {@link #detectAnyLiveHolder} unable to read the pid even from the same process.
     */
    private static final long LOCK_SENTINEL_OFFSET = Long.MAX_VALUE - 1;

    private final Path pidFile;
    private FileChannel channel;
    private FileLock lock;

    public ProcessLock(Path dataDir) {
        this.pidFile = dataDir.toAbsolutePath().normalize().resolve(PID_FILE_NAME);
    }

    /** @return the absolute pid-file path this lock guards. */
    public Path pidFile() {
        return pidFile;
    }

    /**
     * Acquire the exclusive lock and stamp this process's PID. Held until {@link #close()}.
     *
     * @throws LifecycleException if another live process already holds the data dir (the lock is
     *                            unavailable), so two servers never share one data dir.
     */
    public synchronized void acquire() {
        try {
            Files.createDirectories(pidFile.getParent());
            channel = FileChannel.open(pidFile,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            try {
                // Lock a single sentinel byte past EOF (shared=false → exclusive) as a pure mutex; the
                // content region stays readable so the pid can be read back even while locked.
                lock = channel.tryLock(LOCK_SENTINEL_OFFSET, 1, false);
            } catch (OverlappingFileLockException e) {
                // This JVM already holds the lock (e.g. another Spring context in the same process —
                // common in tests where multiple @SpringBootTest contexts share one data dir). That is
                // OUR process, not a foreign one: the data dir is already safely held, so treat it as
                // acquired (no double-lock, no refusal). Leave the existing pid stamp in place.
                closeQuietly();
                log.debug("data-dir lock {} already held by this process; reusing", pidFile);
                return;
            }
            if (lock == null) {
                long holder = readPid(pidFile).orElse(-1L);
                closeQuietly();
                throw new LifecycleException(
                        "another agent-memory process is using this data dir (pid "
                                + (holder < 0 ? "unknown" : holder) + "); refusing to start a second one. "
                                + "Stop it first or point agent-memory.data.dir elsewhere.");
            }
            // Truncate + write our PID so a later reader sees the current holder.
            channel.truncate(0);
            byte[] bytes = (Long.toString(ProcessHandle.current().pid()) + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            channel.position(0);
            channel.write(java.nio.ByteBuffer.wrap(bytes));
            channel.force(true);
            log.info("acquired data-dir lock {} (pid {})", pidFile, ProcessHandle.current().pid());
        } catch (IOException e) {
            closeQuietly();
            throw new UncheckedIOException("could not acquire data-dir lock at " + pidFile, e);
        }
    }

    @Override
    public synchronized void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
            // best effort
        } finally {
            lock = null;
            try {
                if (channel != null && channel.isOpen()) {
                    channel.close();
                }
            } catch (IOException ignored) {
                // best effort
            }
            channel = null;
        }
    }

    /**
     * Determine whether a <em>live</em> process other than {@code ownPid} currently holds the data dir,
     * by reading the recorded PID and checking it is still running. Returns the live holder's PID, or
     * empty when the data dir is free to be destructively reset (no pid file, a dead/stale PID, or the
     * caller's own PID).
     *
     * @param dataDir the data dir to inspect.
     * @param ownPid  the caller's PID; a holder equal to this is not treated as a foreign live holder.
     * @return the foreign live holder's PID, or empty if none.
     */
    public static Optional<Long> detectLiveHolder(Path dataDir, long ownPid) {
        Path pidFile = dataDir.toAbsolutePath().normalize().resolve(PID_FILE_NAME);
        Optional<Long> recorded = readPid(pidFile);
        if (recorded.isEmpty()) {
            return Optional.empty(); // no marker → nothing alive holding it
        }
        long pid = recorded.get();
        if (pid == ownPid) {
            return Optional.empty(); // it's us; the caller decides what to do about itself
        }
        boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        return alive ? Optional.of(pid) : Optional.empty(); // stale/dead PID does not block
    }

    /**
     * Like {@link #detectLiveHolder(Path, long)} but treats the caller's own PID as a holder too — the
     * stricter check {@code reset} uses, so resetting <em>under a running server</em> (the server that
     * is serving the request is itself a live holder) is refused unless forced. Returns the live
     * holder's PID (possibly the caller's own), or empty when the data dir has no live holder.
     *
     * @param dataDir the data dir to inspect.
     * @return the live holder's PID, or empty if none is alive.
     */
    public static Optional<Long> detectAnyLiveHolder(Path dataDir) {
        Path pidFile = dataDir.toAbsolutePath().normalize().resolve(PID_FILE_NAME);
        Optional<Long> recorded = readPid(pidFile);
        if (recorded.isEmpty()) {
            return Optional.empty();
        }
        long pid = recorded.get();
        boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        return alive ? Optional.of(pid) : Optional.empty();
    }

    private static Optional<Long> readPid(Path pidFile) {
        try {
            if (!Files.exists(pidFile)) {
                return Optional.empty();
            }
            String text = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                return Optional.empty();
            }
            // First whitespace-delimited token is the PID (the file may grow more fields later).
            String first = text.split("\\s+", 2)[0];
            return Optional.of(Long.parseLong(first));
        } catch (IOException | NumberFormatException e) {
            return Optional.empty(); // an unreadable/garbage marker must not wedge startup or reset
        }
    }
}
