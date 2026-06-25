package com.agentmemory.hooks;

import tools.jackson.databind.JsonNode;

/**
 * Flattens a {@link HookPayload} into the single free-text {@code payload} an observation stores
 * (issue #8). The capture log keeps one text body per event — the thing the {@code observations}
 * tsvector indexes and the LLM later compiles — so the structured payload fields (title, body, tool
 * name/input/response) are rendered into a deterministic, human-readable block here, before
 * sanitization.
 *
 * <p>The rendering is intentionally simple and stable (same input ⇒ same output): labeled lines in a
 * fixed order. Crucially, the raw tool {@code toolInput}/{@code toolResponse} JSON is emitted
 * <em>verbatim</em> via {@link JsonNode#toString()} — an <strong>array</strong>-shaped
 * {@code toolResponse} is preserved as a JSON array, not flattened or dropped (the documented
 * prior-art "Bug A"). Nothing is lost before the privacy strip; the {@link Sanitizer} then redacts
 * and size-caps this whole block.
 */
final class HookPayloadText {

    private HookPayloadText() {}

    /**
     * Render {@code payload} to its stored text form.
     *
     * @param p the parsed hook payload; never null.
     * @return a deterministic text block; may be empty (e.g. a bare {@code stop} with no content).
     */
    static String flatten(HookPayload p) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "title", p.title());
        appendLine(sb, "body", p.body());
        appendLine(sb, "tool", p.toolName());
        appendJson(sb, "toolInput", p.toolInput());
        appendJson(sb, "toolResponse", p.toolResponse());
        return sb.toString().strip();
    }

    private static void appendLine(StringBuilder sb, String label, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(label).append(": ").append(value);
    }

    private static void appendJson(StringBuilder sb, String label, JsonNode value) {
        if (value == null || value.isNull()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        // toString() is compact JSON; an array node renders as "[...]" (Bug A: kept intact).
        sb.append(label).append(": ").append(value.toString());
    }
}
