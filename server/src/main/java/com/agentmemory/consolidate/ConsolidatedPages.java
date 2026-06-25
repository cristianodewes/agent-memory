package com.agentmemory.consolidate;

import java.util.List;
import java.util.Locale;

/**
 * The structured result of an LLM <em>consolidation</em> (issue #19): the set of durable pages the
 * model distilled from the session material, to be written atomically as a multi-page fan-out. Unlike
 * the session synthesis ({@link SynthesizedSession}, one {@code sessions/} page), consolidation
 * promotes reusable knowledge into the durable folders — {@code concepts/}, {@code decisions/},
 * {@code gotchas/}, {@code procedures/} — and may emit several pages at once (the {@code multi_page}
 * fan-out), each superseding any prior version at its path via the version chain (#12).
 *
 * <p>Structured-JSON only (invariant #7): the model reply is constrained to {@link #SCHEMA_JSON} and
 * re-validated by {@link ConsolidatedPagesParser}. Each {@link Page} carries a {@code folder} (one of
 * the allowed durable folders) and a {@code slug} (the filename without folder or extension); the
 * concrete page path is {@code folder/slug.md}. Forward {@code [[links]]} between the emitted pages
 * are allowed (#27 deferred resolution), so the model may cross-reference pages in the same fan-out.
 *
 * @param pages the durable pages to write; never null, at least one after validation.
 */
public record ConsolidatedPages(List<Page> pages) {

    public ConsolidatedPages {
        if (pages == null || pages.isEmpty()) {
            throw new IllegalArgumentException("consolidation produced no pages");
        }
        pages = List.copyOf(pages);
    }

    /**
     * One durable page in a consolidation: the target {@code folder} + {@code slug} (path is
     * {@code folder/slug.md}), a {@code title}, and the markdown {@code body}.
     *
     * @param folder one of the allowed durable folders ({@link #ALLOWED_FOLDERS}); never blank.
     * @param slug   the filename stem (no folder, no {@code .md}); never blank.
     * @param title  the page title; never blank.
     * @param body   the markdown body; never blank.
     */
    public record Page(String folder, String slug, String title, String body) {
        public Page {
            if (folder == null || folder.isBlank()) {
                throw new IllegalArgumentException("consolidated page folder must not be blank");
            }
            if (slug == null || slug.isBlank()) {
                throw new IllegalArgumentException("consolidated page slug must not be blank");
            }
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("consolidated page title must not be blank");
            }
            if (body == null || body.isBlank()) {
                throw new IllegalArgumentException("consolidated page body must not be blank");
            }
        }

        /** The page path {@code folder/slug.md}, with the folder normalized to lowercase. */
        public String path() {
            return folder.trim().toLowerCase(Locale.ROOT) + "/" + slug.trim() + ".md";
        }
    }

    /**
     * The durable folders consolidation may write into (ARCHITECTURE §2.2). Deliberately excludes
     * {@code sessions/} (that is session synthesis, #18), {@code _rules/} and {@code _slots/} (pinned,
     * user/curated surfaces) — consolidation promotes reusable knowledge, not session logs or pins.
     */
    public static final List<String> ALLOWED_FOLDERS =
            List.of("concepts", "decisions", "gotchas", "procedures");

    /** The schema name handed to the provider (labels the request + server-side schema cache). */
    public static final String SCHEMA_NAME = "consolidated_pages";

    /**
     * The JSON-Schema (draft-2020-12 subset) the reply is constrained to (invariant #7): an object with
     * a non-empty {@code pages} array; each page requires {@code folder} (enum of the allowed durable
     * folders), {@code slug}, {@code title}, {@code body}. {@code additionalProperties:false} keeps the
     * reply tight and parseable. Kept next to the record it parses into.
     */
    public static final String SCHEMA_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["pages"],
              "properties": {
                "pages": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["folder", "slug", "title", "body"],
                    "properties": {
                      "folder": { "type": "string", "enum": ["concepts", "decisions", "gotchas", "procedures"] },
                      "slug":   { "type": "string", "minLength": 1, "maxLength": 80 },
                      "title":  { "type": "string", "minLength": 1, "maxLength": 120 },
                      "body":   { "type": "string", "minLength": 1 }
                    }
                  }
                }
              }
            }
            """;
}
