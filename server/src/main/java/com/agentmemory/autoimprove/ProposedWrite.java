package com.agentmemory.autoimprove;

/**
 * One proposed durable-knowledge edit produced by reviewing a session (issue #30) — the unit the
 * approval gate records in {@code pending_writes} and (when approved) applies through the normal write
 * path. Intentionally minimal and self-describing; the proposal source (#29 curator / #19 consolidation,
 * wired later) maps its own output onto this.
 *
 * @param path      target page path (e.g. {@code concepts/recall.md}); required.
 * @param title     proposed page title; required.
 * @param body      proposed page body (markdown); required (may be empty).
 * @param kind      change kind recorded for the audit, e.g. {@code page.edit}; required.
 * @param rationale why the change was proposed (human-readable); may be null/blank.
 */
public record ProposedWrite(String path, String title, String body, String kind, String rationale) {

    public ProposedWrite {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("proposed write path must not be null or blank");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("proposed write title must not be null or blank");
        }
        path = path.strip();
        title = title.strip();
        body = body == null ? "" : body;
        kind = (kind == null || kind.isBlank()) ? "page.edit" : kind.strip();
        rationale = (rationale == null || rationale.isBlank()) ? null : rationale.strip();
    }
}
