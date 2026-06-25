package com.agentmemory.llm;

/**
 * A JSON Schema (draft-2020-12 subset) that constrains an {@link LlmProvider} response to a single,
 * machine-parseable JSON object — the enforcement side of cross-cutting invariant #7
 * ("structured JSON outputs only from the LLM").
 *
 * <p>The schema is carried as a raw JSON string rather than a typed tree so callers can hand-write
 * or template it, and so providers can forward it verbatim into their native structured-output mode
 * (Anthropic {@code output_config.format = {type: "json_schema", schema: …}}; OpenAI-compatible
 * {@code response_format = {type: "json_schema", json_schema: …}}). The {@code name} labels the
 * schema for providers that require one and aids the 24h server-side schema cache.
 *
 * <p>Providers that lack a native structured-output mode fall back to instructing the model to emit
 * JSON and then validating the reply against this schema (the "tolerant fallback" the issue calls
 * for); a reply that does not parse/validate is surfaced as a non-retryable {@link LlmException}.
 *
 * @param name       a short identifier for the schema (e.g. {@code "handoff"}, {@code "page"}).
 * @param schemaJson the JSON-Schema document, as a JSON string.
 */
public record JsonSchema(String name, String schemaJson) {

    public JsonSchema {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("JsonSchema.name must not be blank");
        }
        if (schemaJson == null || schemaJson.isBlank()) {
            throw new IllegalArgumentException("JsonSchema.schemaJson must not be blank");
        }
    }
}
