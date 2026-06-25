package com.agentmemory.llm;

import com.agentmemory.config.ProviderAuth;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Google (Gemini) implementation of {@link Embedder}, talking to the Generative Language API
 * ({@code POST {baseUrl}/v1beta/models/{model}:embedContent}) over the shared {@link HttpJsonClient}
 * (issue #40). Built on #6's {@link Embedder} interface; the third embeddings-axis option alongside
 * Voyage (#16) and OpenAI.
 *
 * <p><b>Dimension contract.</b> The pgvector column width (issue #4) is fixed at {@value #DIMENSIONS}.
 * The {@code gemini-embedding-*} models support an {@code output_dimensionality} request parameter, so
 * this embedder always requests {@value #DIMENSIONS} and fails loudly if the response width differs
 * (invariant #8).
 *
 * <p><b>Auth (invariant #14).</b> The API key is resolved from the typed {@link ProviderAuth} at
 * construction and sent as the {@code x-goog-api-key} header.
 *
 * <p>Embeds one text per call via {@code embedContent}; the inherited {@link Embedder#embedAll} loops
 * (the native {@code batchEmbedContents} endpoint is left for a future optimization).
 */
public final class GoogleEmbedder implements Embedder {

    /** Canonical provider key selecting this implementation in {@link ProviderFactory}. */
    public static final String PROVIDER_KEY = "google";

    /** Default embedding model; supports {@code output_dimensionality} to hit the column contract. */
    public static final String DEFAULT_MODEL = "gemini-embedding-001";

    /** Default Generative Language API base (host only; the version + method are appended). */
    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    /** The fixed embedding width matching the pgvector column (issue #4 dimension contract). */
    public static final int DIMENSIONS = 1024;

    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final String baseUrl;
    private final HttpJsonClient client;

    public GoogleEmbedder(ProviderAuth auth) {
        this(auth, DIMENSIONS, new HttpJsonClient(Duration.ofSeconds(5), Duration.ofSeconds(30)));
    }

    GoogleEmbedder(ProviderAuth auth, int dimensions, HttpJsonClient client) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        this.apiKey = auth.requireApiKey(PROVIDER_KEY);
        this.model = auth.modelOr(DEFAULT_MODEL);
        this.dimensions = dimensions;
        this.baseUrl = stripTrailingSlash(
                auth.baseUrl() == null || auth.baseUrl().isBlank() ? DEFAULT_BASE_URL : auth.baseUrl());
        this.client = client;
    }

    @Override
    public EmbeddingResult embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedder.embed requires non-blank text");
        }
        ObjectMapper m = client.mapper();
        ObjectNode body = m.createObjectNode();
        // The model is also named in the body per the API; the path carries it too.
        body.put("model", "models/" + model);
        body.putObject("content").putArray("parts").addObject().put("text", text);
        body.put("output_dimensionality", dimensions);

        URI uri = URI.create(baseUrl + "/v1beta/models/" + model + ":embedContent");
        Map<String, String> headers = Map.of("x-goog-api-key", apiKey);
        JsonNode root = client.postJson(uri, headers, body, PROVIDER_KEY);

        JsonNode values = path(path(root, "embedding"), "values");
        return toResult(values);
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

    private EmbeddingResult toResult(JsonNode values) {
        if (values == null || !values.isArray() || values.isEmpty()) {
            throw LlmException.permanent("Google embeddings response had no embedding.values array.", null);
        }
        if (values.size() != dimensions) {
            throw LlmException.permanent(
                    "Google model '" + model + "' returned a " + values.size()
                            + "-dim vector but this embedder is configured for " + dimensions
                            + " dims. Use a 'gemini-embedding-*' model (which supports "
                            + "'output_dimensionality'), or reconfigure the pgvector column.", null);
        }
        float[] vector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) values.get(i).doubleValue();
        }
        return new EmbeddingResult(vector, PROVIDER_KEY, model, dimensions);
    }

    private static JsonNode path(JsonNode node, String field) {
        return node == null ? null : node.get(field);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
