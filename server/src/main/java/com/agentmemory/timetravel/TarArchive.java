package com.agentmemory.timetravel;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A minimal, dependency-free POSIX ustar tar reader/writer — just enough to bundle the named text
 * entries of a {@code backup} into a single, standard {@code .tar} stream (gzip is layered on top by
 * {@link DefaultBackupService}). Implemented in-tree to honour the issue's "tarball" format without
 * pulling in a compression library (the server's only third-party libs are Spring, JGit, pgvector).
 *
 * <p>Scope is intentionally tiny: regular files only, names within the 100-byte ustar limit (the
 * backup uses short names like {@code manifest.json}, {@code observations.jsonl}), UTF-8 payloads,
 * and the standard 512-byte header + 512-padded data + two zero blocks at the end. It is not a
 * general tar implementation; it round-trips exactly what this feature writes.
 */
final class TarArchive {

    private static final int BLOCK = 512;
    private static final int NAME_LEN = 100;

    private TarArchive() {
    }

    /** Write {@code entries} (name → UTF-8 content) as a ustar stream to {@code out}. */
    static void write(OutputStream out, Map<String, String> entries) throws IOException {
        for (Map.Entry<String, String> e : entries.entrySet()) {
            byte[] data = e.getValue().getBytes(StandardCharsets.UTF_8);
            out.write(header(e.getKey(), data.length));
            out.write(data);
            int pad = (BLOCK - (data.length % BLOCK)) % BLOCK;
            if (pad > 0) {
                out.write(new byte[pad]);
            }
        }
        // Two consecutive zero blocks mark end-of-archive.
        out.write(new byte[BLOCK * 2]);
    }

    /** Read a ustar stream into an ordered map of name → UTF-8 content. */
    static Map<String, String> read(InputStream in) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        byte[] header = new byte[BLOCK];
        while (true) {
            readFully(in, header, 0, BLOCK);
            if (isZeroBlock(header)) {
                break; // first zero block ends the archive
            }
            String name = cString(header, 0, NAME_LEN);
            long size = parseOctal(header, 124, 12);
            byte[] data = new byte[(int) size];
            readFully(in, data, 0, data.length);
            int pad = (int) ((BLOCK - (size % BLOCK)) % BLOCK);
            if (pad > 0) {
                skipFully(in, pad);
            }
            entries.put(name, new String(data, StandardCharsets.UTF_8));
        }
        return entries;
    }

    // --- ustar header ----------------------------------------------------------------------------

    private static byte[] header(String name, int size) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > NAME_LEN) {
            throw new IllegalArgumentException("tar entry name too long for ustar: " + name);
        }
        byte[] h = new byte[BLOCK];
        System.arraycopy(bytes, 0, h, 0, bytes.length);
        // mode 0644 (Java octal literal), uid/gid 0
        putOctal(h, 100, 8, 0644);
        putOctal(h, 108, 8, 0);
        putOctal(h, 116, 8, 0);
        putOctal(h, 124, 12, size);
        putOctal(h, 136, 12, 0); // mtime 0 (deterministic archives)
        h[156] = '0'; // typeflag: regular file
        // ustar magic + version
        byte[] magic = "ustar\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, h, 257, magic.length);
        h[263] = '0';
        h[264] = '0';
        // checksum: computed with the checksum field filled with spaces, then written octal + NUL + space
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        int checksum = 0;
        for (byte b : h) {
            checksum += (b & 0xff);
        }
        putOctal(h, 148, 7, checksum); // 6 octal digits + trailing NUL written by putOctal at index 154
        h[154] = 0;
        h[155] = ' ';
        return h;
    }

    /** Write {@code value} as zero-padded octal into {@code [off, off+len)}, NUL-terminated. */
    private static void putOctal(byte[] buf, int off, int len, long value) {
        // len includes the trailing NUL slot; digits occupy len-1.
        int digits = len - 1;
        String s = Long.toOctalString(value);
        if (s.length() > digits) {
            throw new IllegalArgumentException("octal value " + value + " too large for field " + len);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < digits; i++) {
            sb.append('0');
        }
        sb.append(s);
        byte[] b = sb.toString().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, buf, off, b.length);
        buf[off + digits] = 0;
    }

    private static long parseOctal(byte[] buf, int off, int len) {
        long value = 0;
        for (int i = off; i < off + len; i++) {
            int c = buf[i] & 0xff;
            if (c == 0 || c == ' ') {
                if (value != 0 || c == 0) {
                    break;
                }
                continue; // leading spaces
            }
            value = (value << 3) + (c - '0');
        }
        return value;
    }

    private static String cString(byte[] buf, int off, int len) {
        int end = off;
        while (end < off + len && buf[end] != 0) {
            end++;
        }
        return new String(buf, off, end - off, StandardCharsets.UTF_8);
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    // --- stream helpers --------------------------------------------------------------------------

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, off + read, len - read);
            if (n < 0) {
                throw new EOFException("unexpected end of tar stream");
            }
            read += n;
        }
    }

    private static void skipFully(InputStream in, int len) throws IOException {
        // read-and-discard (gzip streams don't always honour skip())
        byte[] scratch = new byte[Math.min(len, BLOCK)];
        int remaining = len;
        while (remaining > 0) {
            int n = in.read(scratch, 0, Math.min(remaining, scratch.length));
            if (n < 0) {
                throw new EOFException("unexpected end of tar stream while padding");
            }
            remaining -= n;
        }
    }

    /** Small helper used by callers building an archive entirely in memory. */
    static byte[] toBytes(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        write(bos, entries);
        return bos.toByteArray();
    }
}
