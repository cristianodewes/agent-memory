package com.agentmemory.wiki;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A full wiki-page file: a {@link PageFrontmatter} header block plus the markdown {@code body}.
 * Renders to / parses from the on-disk form
 *
 * <pre>
 * ---
 * title: "Hybrid recall"
 * kind: concepts
 * ...
 * ---
 *
 * &lt;body markdown&gt;
 * </pre>
 *
 * <p>The frontmatter is a small, strict {@code key: value} subset of YAML (one line per key, values
 * double-quoted with {@code \\}/{@code \"} escaping) — deliberately not a full YAML dependency,
 * since pages are machine-written. {@link #parse(String)} is lenient about a missing trailing
 * newline and CRLF (Obsidian/vim on any OS) but strict about the {@code ---} fences and required
 * keys, so a genuinely malformed external edit surfaces as an error rather than a corrupt index row.
 */
public record MarkdownDocument(PageFrontmatter frontmatter, String body) {

    private static final String FENCE = "---";

    public MarkdownDocument {
        if (frontmatter == null) {
            throw new IllegalArgumentException("document.frontmatter must not be null");
        }
        if (body == null) {
            throw new IllegalArgumentException("document.body must not be null");
        }
    }

    /** @return the full file text (frontmatter block + blank line + body), LF line endings. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(FENCE).append('\n');
        for (Map.Entry<String, String> e : frontmatter.toOrderedMap().entrySet()) {
            sb.append(e.getKey()).append(": ").append(quote(e.getValue())).append('\n');
        }
        sb.append(FENCE).append('\n').append('\n');
        sb.append(body);
        if (!body.endsWith("\n")) {
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Parse the on-disk file form back into a document. Accepts CRLF or LF and an optional leading
     * BOM; requires the leading {@code ---} fence, a closing {@code ---} fence, and the frontmatter
     * keys {@link PageFrontmatter} needs.
     *
     * @param text the raw file contents.
     * @return the parsed document.
     * @throws WikiFormatException if the fences or required keys are missing/malformed.
     */
    public static MarkdownDocument parse(String text) {
        if (text == null) {
            throw new WikiFormatException("page file is null");
        }
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        if (normalized.startsWith("﻿")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.startsWith(FENCE + "\n")) {
            throw new WikiFormatException("page must start with a '---' frontmatter fence");
        }
        int closeIdx = normalized.indexOf("\n" + FENCE, FENCE.length());
        if (closeIdx < 0) {
            throw new WikiFormatException("page frontmatter is not closed with a '---' fence");
        }
        String fmBlock = normalized.substring(FENCE.length() + 1, closeIdx + 1);
        // Body starts after the closing fence line.
        int afterClose = closeIdx + 1 + FENCE.length();
        String body = afterClose >= normalized.length() ? "" : normalized.substring(afterClose);
        // Drop exactly one leading newline pair between the fence and the body.
        if (body.startsWith("\n")) {
            body = body.substring(1);
        }
        if (body.startsWith("\n")) {
            body = body.substring(1);
        }

        Map<String, String> fm = parseFrontmatter(fmBlock);
        PageFrontmatter frontmatter = new PageFrontmatter(
                require(fm, "title"),
                PageKind.fromWire(require(fm, "kind")),
                Boolean.parseBoolean(fm.getOrDefault("pinned", "false")),
                fm.get("slot_kind"),
                WorkspaceId.of(require(fm, "workspace")),
                ProjectId.of(require(fm, "project")),
                PagePath.of(require(fm, "path")),
                Instant.parse(require(fm, "created_at")),
                Instant.parse(require(fm, "updated_at")));
        return new MarkdownDocument(frontmatter, body);
    }

    /** @return the identity declared by this document's frontmatter. */
    public Identity identity() {
        return frontmatter.identity();
    }

    // --- frontmatter mini-serializer -----------------------------------------------------------

    private static Map<String, String> parseFrontmatter(String block) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : block.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                throw new WikiFormatException("malformed frontmatter line (no ':'): " + line);
            }
            String key = line.substring(0, colon).trim();
            String raw = line.substring(colon + 1).trim();
            out.put(key, unquote(raw));
        }
        return out;
    }

    private static String require(Map<String, String> fm, String key) {
        String v = fm.get(key);
        if (v == null) {
            throw new WikiFormatException("frontmatter missing required key: " + key);
        }
        return v;
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String unquote(String raw) {
        if (raw.length() < 2 || raw.charAt(0) != '"' || raw.charAt(raw.length() - 1) != '"') {
            // Bare (unquoted) value — accept as-is for human-friendly external edits.
            return raw;
        }
        String inner = raw.substring(1, raw.length() - 1);
        StringBuilder sb = new StringBuilder(inner.length());
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '\\' && i + 1 < inner.length()) {
                char next = inner.charAt(++i);
                switch (next) {
                    case '\\' -> sb.append('\\');
                    case '"' -> sb.append('"');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
