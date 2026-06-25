package com.agentmemory.llm;

import com.agentmemory.config.ProviderAuth;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The OpenAI implementation of {@link Embedder}, talking to the Embeddings API
 * ({@code POST {baseUrl}/embeddings}) over the shared {@link HttpJsonClient} (issue #40). Built on
 * #6's {@link Embedder} interface; one of the embeddings-axis options alongside Voyage (#16) and
 * Google.
 *
 * <p><b>Dimension contract.</b> The pgvector column width (issue #4) is fixed at
 * {@value #DIMENSIONS}. The {@code text-embedding-3-*} models support arbitrary output widths via the
 * {@code dimensions} request parameter, so this embedder always requests {@value #DIMENSIONS} and
 * fails loudly if the response width differs — a mismatch must never be stored (invariant #8). Set
 * {@code agent-memory.embeddings.auth.model} to a {@code text-embedding-3-*} model; older models that
 * cannot resize are rejected by the width check.
 *
 * <p><b>Auth (invariant #14).</b> The API key is resolved from the typed {@link ProviderAuth} at
 * construction and sent as {@code Authorization: Bearer <key>}.
 */
public final class OpenAiEmbedder implements Embedder {

    /** Canonical provider key selecting this implementation in {@link ProviderFactory}. */
    public static final String PROVIDER_KEY = "openai";

    /** Default embedding model; resizable to the dimension contract via the {@code dimensions} param. */
    public static final String DEFAULT_MODEL = "text-embedding-3-small";

    /** Default Embeddings base for the native OpenAI endpoint; includes the {@code /v1}. */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    /** The fixed embedding width matching the pgvector column (issue #4 dimension contract). */
    public static final int DIMENSIONS = 1024;

    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final URI embeddingsUri;
    private final HttpJsonClient client;

    public OpenAiEmbedder(ProviderAuth auth) {
        this(auth, DIMENSIONS, new HttpJsonClient(Duration.ofSeconds(5), Duration.ofSeconds(30)));
    }

    OpenAiEmbedder(ProviderAuth auth, int dimensions, HttpJsonClient client) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        this.apiKey = auth.requireApiKey(PROVIDER_KEY);
        this.model = auth.modelOr(DEFAULT_MODEL);
        this.dimensions = dimensions;
        String base = stripTrailingSlash(
                auth.baseUrl() == null || auth.baseUrl().isBlank() ? DEFAULT_BASE_URL : auth.baseUrl());
        this.embeddingsUri = URI.create(base + "/embeddings");
        this.client = client;
    }

    @Override
    public EmbeddingResult embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedder.embed requires non-blank text");
        }
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<EmbeddingResult> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("Embedder.embedAll requires a non-empty list");
        }
        ObjectMapper m = client.mapper();
        ObjectNode body = m.createObjectNode();
        body.put("model", model);
        body.put("dimensions", dimensions); // pin output width to the column contract
        ArrayNode input = body.putArray("input");
        for (String t : texts) {
            if (t == null || t.isBlank()) {
                throw new IllegalArgumentException("Embedder.embedAll inputs must be non-blank");
            }
            input.add(t);
        }

        Map<String, String> headers = Map.of("authorization", "Bearer " + apiKey);
        JsonNode root = client.postJson(embeddingsUri, headers, body, PROVIDER_KEY);

        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.size() != texts.size()) {
            throw LlmException.permanent(
                    "OpenAI embeddings 'data' missing or wrong size (expected " + texts.size() + ").", null);
        }
        String usedModel = textOr(root.get("model"), model);

        // Order by 'index' so results line up with inputs even if the API reorders.
        EmbeddingResult[] ordered = new EmbeddingResult[texts.size()];
        for (JsonNode item : data) {
            int index = item.has("index") ? item.get("index").intValue() : -1;
            if (index < 0 || index >= ordered.length) {
                throw LlmException.permanent(
                        "OpenAI embeddings response item had out-of-range index: " + index, null);
            }
            ordered[index] = toResult(item.get("embedding"), usedModel);
        }

        List<EmbeddingResult> results = new ArrayList<>(ordered.length);
        for (int i = 0; i < ordered.length; i++) {
            if (ordered[i] == null) {
                throw LlmException.permanent(
                        "OpenAI embeddings response missing an embedding for index " + i, null);
            }
            results.add(ordered[i]);
        }
        return results;
    }

    @Override
    public String id() {
        return PROVIDER_KEY;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public void probe() {
        embed("ping");
    }

    private EmbeddingResult toResult(JsonNode embedding, String usedModel) {
        if (embedding == null || !embedding.isArray() || embedding.isEmpty()) {
            throw LlmException.permanent("OpenAI embeddings item had no 'embedding' array.", null);
        }
        if (embedding.size() != dimensions) {
            throw LlmException.permanent(
                    "OpenAI model '" + usedModel + "' returned a " + embedding.size()
                            + "-dim vector but this embedder is configured for " + dimensions
                            + " dims. Use a 'text-embedding-3-*' model (which supports the 'dimensions' "
                            + "parameter), or reconfigure the pgvector column.", null);
        }
        float[] vector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) embedding.get(i).doubleValue();
        }
        return new EmbeddingResult(vector, PROVIDER_KEY, usedModel, dimensions);
    }

    private static String textOr(JsonNode node, String fallback) {
        String t = node != null && node.isString() ? node.stringValue() : null;
        return t == null || t.isBlank() ? fallback : t;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
