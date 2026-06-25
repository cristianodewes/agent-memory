package com.agentmemory.llmrecall;

import com.agentmemory.llm.JsonSchema;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The static prompt text and {@link JsonSchema} documents for the two LLM steps of recall — query
 * expansion and candidate rerank. The system prompts are loaded once from the classpath
 * ({@code prompts/recall-*.system.md}) at construction; a missing resource fails fast (a packaging
 * error should surface at startup, not on the first query). The structured-output schemas (invariant
 * #7) are draft-2020-12 subsets with {@code additionalProperties:false} so the reply is exactly the
 * shape the parsers expect.
 *
 * <p>Stateless after construction. Held as a singleton bean by {@link LlmRecallConfiguration}.
 */
public final class RecallPrompts {

    private static final String EXPANSION_PROMPT_RESOURCE = "prompts/recall-expansion.system.md";
    private static final String RERANK_PROMPT_RESOURCE = "prompts/recall-rerank.system.md";

    /** Schema name for the expansion reply (labels the provider-side schema cache). */
    public static final String EXPANSION_SCHEMA_NAME = "recall_expansion";

    /** Schema name for the rerank reply. */
    public static final String RERANK_SCHEMA_NAME = "recall_rerank";

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

    private final String expansionSystemPrompt;
    private final String rerankSystemPrompt;
    private final JsonSchema expansionSchema;
    private final JsonSchema rerankSchema;

    public RecallPrompts() {
        this.expansionSystemPrompt = loadResource(EXPANSION_PROMPT_RESOURCE);
        this.rerankSystemPrompt = loadResource(RERANK_PROMPT_RESOURCE);
        this.expansionSchema = new JsonSchema(EXPANSION_SCHEMA_NAME, EXPANSION_SCHEMA_JSON);
        this.rerankSchema = new JsonSchema(RERANK_SCHEMA_NAME, RERANK_SCHEMA_JSON);
    }

    /** @return the system prompt for query expansion. */
    public String expansionSystemPrompt() {
        return expansionSystemPrompt;
    }

    /** @return the system prompt for candidate rerank. */
    public String rerankSystemPrompt() {
        return rerankSystemPrompt;
    }

    /** @return the structured-output schema constraining the expansion reply. */
    public JsonSchema expansionSchema() {
        return expansionSchema;
    }

    /** @return the structured-output schema constraining the rerank reply. */
    public JsonSchema rerankSchema() {
        return rerankSchema;
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
