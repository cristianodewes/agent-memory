package com.agentmemory.wiki;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Crash-safe file writer implementing invariant #10: <strong>tmp + rename + fsync</strong>. The
 * payload is written to a temporary file in the <em>same directory</em> as the target (so the rename
 * is a same-filesystem atomic operation), the temp file's contents are forced to disk, the temp is
 * atomically renamed onto the target, and finally the directory entry is forced. A crash at any
 * point leaves either the old file intact or the fully-written new file — never a partial file.
 *
 * <p>Returns the SHA-256 of the exact bytes written so the {@link SelfWriteTracker} can later
 * recognize the watcher's view of this write and skip re-ingesting it (no feedback loop).
 */
public final class AtomicFileWriter {

    /**
     * Atomically write {@code content} (UTF-8) to {@code target}, creating parent directories.
     *
     * @param target the destination file.
     * @param content the full file contents.
     * @return the lowercase hex SHA-256 of the bytes written.
     * @throws IOException if any filesystem step fails (the target is left untouched on failure
     *                     before the rename).
     */
    public String write(Path target, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Path dir = target.toAbsolutePath().getParent();
        Files.createDirectories(dir);

        Path tmp = Files.createTempFile(dir, ".wiki-", ".tmp");
        try {
            Files.write(tmp, bytes);
            fsyncFile(tmp);
            try {
                Files.move(tmp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Extremely rare (target dir on a different mount than the temp); fall back to a
                // best-effort replace. The temp was created in the same dir, so this should not occur.
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp); // no-op once the move succeeded
        }
        fsyncDir(dir); // make the rename itself durable
        return sha256Hex(bytes);
    }

    /** @return the lowercase hex SHA-256 of {@code content}'s UTF-8 bytes (no file IO). */
    public static String hashOf(String content) {
        return sha256Hex(content.getBytes(StandardCharsets.UTF_8));
    }

    private static void fsyncFile(Path file) throws IOException {
        try (var ch = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    }

    private static void fsyncDir(Path dir) {
        // Directory fsync is a no-op / unsupported on some platforms (notably Windows); best-effort.
        try (var ch = java.nio.channels.FileChannel.open(dir, java.nio.file.StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException ignored) {
            // Windows throws here; the ATOMIC_MOVE already gives crash-atomic visibility of the file.
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
