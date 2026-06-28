package com.agentmemory.llmrecall;

import com.agentmemory.llm.JsonSchema;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The static prompt text and {@link JsonSchema} documents for the LLM steps of recall — query
 * expansion, candidate rerank, and brief curation (issue #135, Fase 3). The system prompts are loaded
 * once from the classpath ({@code prompts/recall-*.system.md}) at construction; a missing resource
 * fails fast (a packaging error should surface at startup, not on the first query). The
 * structured-output schemas (invariant #7) are draft-2020-12 subsets with
 * {@code additionalProperties:false} so the reply is exactly the shape the parsers expect.
 *
 * <p>Stateless after construction. Held as a singleton bean by {@link LlmRecallConfiguration}.
 */
public final class RecallPrompts {

    private static final String EXPANSION_PROMPT_RESOURCE = "prompts/recall-expansion.system.md";
    private static final String RERANK_PROMPT_RESOURCE = "prompts/recall-rerank.system.md";
    private static final String CURATE_PROMPT_RESOURCE = "prompts/recall-curate.system.md";

    /** Schema name for the expansion reply (labels the provider-side schema cache). */
    public static final String EXPANSION_SCHEMA_NAME = "recall_expansion";

    /** Schema name for the rerank reply. */
    public static final String RERANK_SCHEMA_NAME = "recall_rerank";

    /** Schema name for the brief-curation reply (issue #135, Fase 3). */
    public static final String CURATE_SCHEMA_NAME = "recall_curate";

    /** {@code { "terms": ["…", …] }} — additional retrieval terms, all strings. */
    private static final String EXPANSION_SCHEMA_JSON =
            "{\"type\":\"object\","
                    + "\"properties\":{"
                    + "\"terms\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
                    + "\"required\":[\"terms\"],"
                    + "\"additionalProperties\":false}";

    /** {@code { "rankings": [ { "id": "…", "relevance": 0.0..1.0 }, … ] }}. */
    private static final String RERANK_SCHEMA_JSON =
            "{\"type\":\"object\","
                    + "\"properties\":{"
                    + "\"rankings\":{\"type\":\"array\",\"items\":{"
                    + "\"type\":\"object\","
                    + "\"properties\":{"
                    + "\"id\":{\"type\":\"string\"},"
                    + "\"relevance\":{\"type\":\"number\",\"minimum\":0,\"maximum\":1}},"
                    + "\"required\":[\"id\",\"relevance\"],"
                    + "\"additionalProperties\":false}}},"
                    + "\"required\":[\"rankings\"],"
                    + "\"additionalProperties\":false}";

    /**
     * {@code { "relevant": <bool>, "brief": "…", "cited_paths": ["…", …] }} (issue #135, Fase 3) — the
     * synthesized brief, the page paths it drew on, and whether anything was genuinely relevant.
     */
    private static final String CURATE_SCHEMA_JSON =
            "{\"type\":\"object\","
                    + "\"properties\":{"
                    + "\"relevant\":{\"type\":\"boolean\"},"
                    + "\"brief\":{\"type\":\"string\"},"
                    + "\"cited_paths\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},"
                    + "\"maxItems\":5}},"
                    + "\"required\":[\"relevant\",\"brief\",\"cited_paths\"],"
                    + "\"additionalProperties\":false}";

    private final String expansionSystemPrompt;
    private final String rerankSystemPrompt;
    private final String curateSystemPrompt;
    private final JsonSchema expansionSchema;
    private final JsonSchema rerankSchema;
    private final JsonSchema curateSchema;

    public RecallPrompts() {
        this.expansionSystemPrompt = loadResource(EXPANSION_PROMPT_RESOURCE);
        this.rerankSystemPrompt = loadResource(RERANK_PROMPT_RESOURCE);
        this.curateSystemPrompt = loadResource(CURATE_PROMPT_RESOURCE);
        this.expansionSchema = new JsonSchema(EXPANSION_SCHEMA_NAME, EXPANSION_SCHEMA_JSON);
        this.rerankSchema = new JsonSchema(RERANK_SCHEMA_NAME, RERANK_SCHEMA_JSON);
        this.curateSchema = new JsonSchema(CURATE_SCHEMA_NAME, CURATE_SCHEMA_JSON);
    }

    /** @return the system prompt for query expansion. */
    public String expansionSystemPrompt() {
        return expansionSystemPrompt;
    }

    /** @return the system prompt for candidate rerank. */
    public String rerankSystemPrompt() {
        return rerankSystemPrompt;
    }

    /** @return the system prompt for brief curation (issue #135, Fase 3). */
    public String curateSystemPrompt() {
        return curateSystemPrompt;
    }

    /** @return the structured-output schema constraining the expansion reply. */
    public JsonSchema expansionSchema() {
        return expansionSchema;
    }

    /** @return the structured-output schema constraining the rerank reply. */
    public JsonSchema rerankSchema() {
        return rerankSchema;
    }

    /** @return the structured-output schema constraining the brief-curation reply. */
    public JsonSchema curateSchema() {
        return curateSchema;
    }

    private static String loadResource(String name) {
        ClassLoader cl = RecallPrompts.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(
                        "missing classpath resource '" + name + "' (recall prompt not packaged)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading classpath resource '" + name + "'", e);
        }
    }
}
