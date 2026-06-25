package com.agentmemory.consolidate;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses and validates the LLM's structured-JSON reply into a {@link SynthesizedSession} (issue #18).
 * The provider already constrains the reply to {@link SynthesizedSession#SCHEMA_JSON} server-side
 * (invariant #7), but this is the consumer-side guard: it re-parses the JSON, enforces the required
 * fields, and coerces the arrays, so a malformed or partial reply surfaces as a clear
 * {@link ConsolidationException} rather than a downstream {@code NullPointerException} or a corrupt
 * page. Uses Spring's bundled Jackson 3 ({@code tools.jackson}) — no new dependency.
 */
public final class SessionSynthesisParser {

    private final ObjectMapper json = JsonMapper.builder().build();

    /**
     * Parse a structured-JSON synthesis reply.
     *
     * @param replyJson the model's JSON reply text (already schema-constrained by the provider).
     * @return the typed, validated synthesis.
     * @throws ConsolidationException if the reply is not valid JSON or is missing required fields.
     */
    public SynthesizedSession parse(String replyJson) {
        if (replyJson == null || replyJson.isBlank()) {
            throw new ConsolidationException("LLM synthesis reply was empty");
        }
        JsonNode root;
        try {
            root = json.readTree(replyJson);
        } catch (JacksonException e) {
            throw new ConsolidationException("LLM synthesis reply was not valid JSON: " + e.getMessage(), e);
        }
        if (!root.isObject()) {
            throw new ConsolidationException("LLM synthesis reply was not a JSON object");
        }
        try {
            return new SynthesizedSession(
                    requireString(root, "title"),
                    requireString(root, "summary"),
                    stringArray(root, "decisions"),
                    stringArray(root, "follow_ups"),
                    stringArray(root, "open_questions"),
                    stringArray(root, "highlights"));
        } catch (IllegalArgumentException e) {
            // SynthesizedSession's own invariants (blank title/summary) → a consolidation failure.
            throw new ConsolidationException("LLM synthesis reply failed validation: " + e.getMessage(), e);
        }
    }

    private static String requireString(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            throw new ConsolidationException(
                    "LLM synthesis reply missing required field '" + field + "'");
        }
        if (!n.isString()) {
            throw new ConsolidationException("LLM synthesis field '" + field + "' was not a string");
        }
        if (n.stringValue().isBlank()) {
            throw new ConsolidationException("LLM synthesis required field '" + field + "' was blank");
        }
        return n.stringValue();
    }

    private static List<String> stringArray(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) {
            return List.of();
        }
        if (!n.isArray()) {
            throw new ConsolidationException("LLM synthesis field '" + field + "' was not an array");
        }
        List<String> out = new ArrayList<>(n.size());
        for (JsonNode item : n) {
            // A non-string array element means the model emitted a malformed reply; fail loudly rather
            // than silently dropping it (which would render a clean page with content quietly missing).
            if (item == null || !item.isString()) {
                throw new ConsolidationException(
                        "LLM synthesis field '" + field + "' contained a non-string element");
            }
            out.add(item.stringValue());
        }
        return out;
    }
}
