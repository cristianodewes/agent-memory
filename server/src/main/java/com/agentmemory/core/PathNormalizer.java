package com.agentmemory.core;

/**
 * Canonicalization rules for wiki page paths. A {@link PagePath} is part of the 3-tuple identity
 * (invariant #4), so two spellings of "the same page" must collapse to one byte-identical key on
 * both the Go and Java sides — otherwise the same logical page would index twice. These rules are
 * the normative definition; the cross-language contract under {@code docs/contracts/} restates
 * them and the golden fixtures pin them.
 *
 * <p>The normal form of a page path is:
 * <ol>
 *   <li>Backslashes ({@code \}) become forward slashes — Windows clients spell paths natively.</li>
 *   <li>Runs of slashes collapse to a single {@code /}.</li>
 *   <li>A leading {@code /} or {@code ./} is stripped: paths are relative to the project root.</li>
 *   <li>Each segment is trimmed of surrounding whitespace; empty and {@code .} segments drop out.</li>
 *   <li>{@code ..} segments are rejected — paths never escape the project root (no traversal).</li>
 *   <li>ASCII letters are lower-cased. The wiki is case-insensitive so that {@code Concepts/Foo.md}
 *       and {@code concepts/foo.md} are one page on case-preserving filesystems; non-ASCII is left
 *       untouched (we do not attempt Unicode case folding).</li>
 *   <li>A {@code .md} extension is ensured — exactly one, case-insensitively (a trailing
 *       {@code .MD} is treated as already-present and lower-cased).</li>
 * </ol>
 *
 * <p>The result never starts or ends with {@code /}, contains no {@code .} / {@code ..} / empty
 * segments, and always ends in {@code .md}. A path that normalizes to nothing (e.g. {@code "/"} or
 * {@code "   "}) is rejected, as is one containing a NUL character.
 */
final class PathNormalizer {

    private PathNormalizer() {
    }

    /**
     * @param raw the path as supplied by a client / hook / API call.
     * @return the canonical, byte-stable page path.
     * @throws IllegalArgumentException if {@code raw} is null, blank, contains a {@code ..}
     *                                  segment or a NUL, or normalizes to an empty path.
     */
    static String normalize(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("page path must not be null");
        }
        if (raw.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("page path must not contain NUL");
        }

        String unified = raw.replace('\\', '/');
        String[] rawSegments = unified.split("/");

        StringBuilder out = new StringBuilder(unified.length());
        for (String segment : rawSegments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty() || trimmed.equals(".")) {
                continue; // drops leading "/", "./", "//" and stray whitespace-only segments
            }
            if (trimmed.equals("..")) {
                throw new IllegalArgumentException("page path must not contain '..': " + raw);
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(trimmed.toLowerCase(java.util.Locale.ROOT));
        }

        if (out.length() == 0) {
            throw new IllegalArgumentException("page path is empty after normalization: " + raw);
        }

        // Ensure exactly one ".md" suffix (case-insensitive on the existing extension).
        String normalized = out.toString();
        if (!normalized.regionMatches(true, normalized.length() - 3, ".md", 0, 3)) {
            normalized = normalized + ".md";
        }
        return normalized;
    }
}
