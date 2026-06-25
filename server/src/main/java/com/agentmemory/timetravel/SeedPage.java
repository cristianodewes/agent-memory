package com.agentmemory.timetravel;

/**
 * One seed page proposed by the {@code bootstrap} LLM pass (issue #34): a path, a title and the
 * markdown body to write under the target project. The structured-output schema the model is held to
 * is {@link #SCHEMA_JSON} (invariant #7).
 *
 * @param path  the page path under {@code wiki/<workspace>/<project>/} (e.g. {@code concepts/architecture.md}).
 * @param title the page title (frontmatter {@code title}).
 * @param body  the markdown body (no frontmatter — the writer adds it).
 */
public record SeedPage(String path, String title, String body) {

    /** The schema name surfaced to the provider. */
    public static final String SCHEMA_NAME = "bootstrap_seed_pages";

    /**
     * JSON-Schema (draft-2020-12 subset) the bootstrap reply must satisfy: an object with a
     * {@code pages} array of {@code {path, title, body}} objects. A single structured pass returns the
     * whole seed set (the issue's "one-shot" requirement).
     */
    public static final String SCHEMA_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["pages"],
              "properties": {
                "pages": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["path", "title", "body"],
                    "properties": {
                      "path":  { "type": "string", "minLength": 1 },
                      "title": { "type": "string", "minLength": 1 },
                      "body":  { "type": "string" }
                    }
                  }
                }
              }
            }
            """;
}
