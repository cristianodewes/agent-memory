package com.agentmemory.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link ProcessLock} — the live-process detection behind the {@code reset} guard
 * (invariant #9, issue #33). Pure filesystem; no Spring/DB. Covers the acquire/stamp behaviour and the
 * "is a live holder present" logic the reset guard relies on.
 */
class ProcessLockTest {

    @TempDir
    Path dataDir;

    private static final long OWN_PID = ProcessHandle.current().pid();

    @Test
    void noPidFileMeansNoLiveHolder() {
        assertThat(ProcessLock.detectLiveHolder(dataDir, OWN_PID)).isEmpty();
        assertThat(ProcessLock.detectAnyLiveHolder(dataDir)).isEmpty();
    }

    @Test
    void acquireStampsThePidFileWithOurPid() throws Exception {
        try (ProcessLock lock = new ProcessLock(dataDir)) {
            lock.acquire();
            Path pidFile = dataDir.resolve(ProcessLock.PID_FILE_NAME);
            assertThat(Files.exists(pidFile)).isTrue();
            String content = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
            assertThat(Long.parseLong(content)).isEqualTo(OWN_PID);
        }
    }

    @Test
    void ourOwnLivePidIsNotAForeignHolderButIsAnyHolder() throws Exception {
        // Stamp the file with our own (live) pid.
        Files.writeString(dataDir.resolve(ProcessLock.PID_FILE_NAME), Long.toString(OWN_PID));

        // detectLiveHolder excludes our own pid (no FOREIGN holder)...
        assertThat(ProcessLock.detectLiveHolder(dataDir, OWN_PID)).isEmpty();
        // ...but detectAnyLiveHolder counts it (the reset-under-running-server case).
        assertThat(ProcessLock.detectAnyLiveHolder(dataDir)).contains(OWN_PID);
    }

    @Test
    void aDeadPidDoesNotBlock() throws Exception {
        long deadPid = findDeadPid();
        Files.writeString(dataDir.resolve(ProcessLock.PID_FILE_NAME), Long.toString(deadPid));

        assertThat(ProcessLock.detectLiveHolder(dataDir, OWN_PID)).isEmpty();
        assertThat(ProcessLock.detectAnyLiveHolder(dataDir)).isEmpty();
    }

    @Test
    void aLiveForeignPidIsDetected() throws Exception {
        // Our own pid is live and (from detectLiveHolder's view of "foreign") only foreign if we pass a
        // different ownPid. Use a deliberately different ownPid so OWN_PID reads as a foreign live holder.
        Files.writeString(dataDir.resolve(ProcessLock.PID_FILE_NAME), Long.toString(OWN_PID));
        long pretendOwn = OWN_PID == 1L ? 2L : 1L;
        Optional<Long> holder = ProcessLock.detectLiveHolder(dataDir, pretendOwn);
        assertThat(holder).contains(OWN_PID);
    }

    @Test
    void garbagePidFileIsIgnored() throws Exception {
        Files.writeString(dataDir.resolve(ProcessLock.PID_FILE_NAME), "not-a-number\n");
        assertThat(ProcessLock.detectLiveHolder(dataDir, OWN_PID)).isEmpty();
        assertThat(ProcessLock.detectAnyLiveHolder(dataDir)).isEmpty();
    }

    @Test
    void reAcquiringWithinTheSameProcessIsBenign() throws Exception {
        try (ProcessLock first = new ProcessLock(dataDir)) {
            first.acquire();
            // A second lock object in the SAME JVM/process must not throw (overlapping lock = us).
            try (ProcessLock second = new ProcessLock(dataDir)) {
                assertThatCode(second::acquire).doesNotThrowAnyException();
            }
        }
    }

    /** Find a PID that is (almost certainly) not a live process, to simulate a stale marker. */
    private static long findDeadPid() {
        for (long candidate = 999_999L; candidate > 100_000L; candidate -= 7919L) {
            if (ProcessHandle.of(candidate).map(ProcessHandle::isAlive).orElse(false)) {
                continue;
            }
            return candidate;
        }
        return 2_000_000_111L; // fallback: an implausibly high pid
    }
}
