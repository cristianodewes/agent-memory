package com.agentmemory.consolidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses and validates the LLM's structured-JSON consolidation reply into a {@link ConsolidatedPages}
 * (issue #19). The provider constrains the reply to {@link ConsolidatedPages#SCHEMA_JSON} server-side
 * (invariant #7); this is the consumer-side guard — re-parse, enforce the required fields, the allowed
 * folder set, and a path-safe slug — so a malformed reply surfaces as a clear
 * {@link ConsolidationException} rather than a corrupt page or a path-traversal write. Uses Spring's
 * bundled Jackson 3 ({@code tools.jackson}).
 */
public final class ConsolidatedPagesParser {

    private final ObjectMapper json = JsonMapper.builder().build();

    /**
     * Parse a structured-JSON consolidation reply.
     *
     * @param replyJson the model's JSON reply text (schema-constrained by the provider).
     * @return the typed, validated set of pages.
     * @throws ConsolidationException if the reply is not valid JSON or fails validation.
     */
    public ConsolidatedPages parse(String replyJson) {
        if (replyJson == null || replyJson.isBlank()) {
            throw new ConsolidationException("LLM consolidation reply was empty");
        }
        JsonNode root;
        try {
            root = json.readTree(replyJson);
        } catch (JacksonException e) {
            throw new ConsolidationException(
                    "LLM consolidation reply was not valid JSON: " + e.getMessage(), e);
        }
        if (!root.isObject()) {
            throw new ConsolidationException("LLM consolidation reply was not a JSON object");
        }
        JsonNode pages = root.get("pages");
        if (pages == null || !pages.isArray() || pages.isEmpty()) {
            throw new ConsolidationException("LLM consolidation reply had no 'pages' array");
        }
        List<ConsolidatedPages.Page> out = new ArrayList<>(pages.size());
        for (JsonNode p : pages) {
            if (p == null || !p.isObject()) {
                throw new ConsolidationException("a consolidation 'pages' element was not an object");
            }
            String folder = requireString(p, "folder");
            String slug = requireString(p, "slug");
            String title = requireString(p, "title");
            String body = requireString(p, "body");
            validateFolder(folder);
            validateSlug(slug);
            try {
                out.add(new ConsolidatedPages.Page(folder, slug, title, body));
            } catch (IllegalArgumentException e) {
                throw new ConsolidationException(
                        "consolidation page failed validation: " + e.getMessage(), e);
            }
        }
        try {
            return new ConsolidatedPages(out);
        } catch (IllegalArgumentException e) {
            throw new ConsolidationException("consolidation reply failed validation: " + e.getMessage(), e);
        }
    }

    private static void validateFolder(String folder) {
        String f = folder.trim().toLowerCase(Locale.ROOT);
        if (!ConsolidatedPages.ALLOWED_FOLDERS.contains(f)) {
            throw new ConsolidationException(
                    "consolidation page folder '" + folder + "' is not one of " + ConsolidatedPages.ALLOWED_FOLDERS);
        }
    }

    /**
     * Reject a slug that is not a safe filename stem: no path separators, no parent refs, no extension
     * — so a model reply can never escape its folder ({@code ../}) or nest ({@code a/b}). Allowed
     * characters are letters, digits, {@code -} and {@code _}.
     */
    private static void validateSlug(String slug) {
        String s = slug.trim();
        if (s.contains("/") || s.contains("\\") || s.contains("..")) {
            throw new ConsolidationException("consolidation page slug must not contain a path: " + slug);
        }
        if (s.endsWith(".md")) {
            throw new ConsolidationException("consolidation page slug must not include the .md extension: " + slug);
        }
        if (!s.matches("[A-Za-z0-9._-]+")) {
            throw new ConsolidationException(
                    "consolidation page slug has invalid characters (allowed: letters, digits, . _ -): " + slug);
        }
    }

    private static String requireString(JsonNode obj, String field) {
        JsonNode n = obj.get(field);
        if (n == null || n.isNull()) {
            throw new ConsolidationException("consolidation page missing required field '" + field + "'");
        }
        if (!n.isString()) {
            throw new ConsolidationException("consolidation page field '" + field + "' was not a string");
        }
        if (n.stringValue().isBlank()) {
            throw new ConsolidationException("consolidation page required field '" + field + "' was blank");
        }
        return n.stringValue();
    }
}
