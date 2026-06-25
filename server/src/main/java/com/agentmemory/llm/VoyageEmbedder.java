package com.agentmemory.llm;

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
 * The Voyage AI implementation of {@link Embedder} ({@code POST /v1/embeddings}), the recommended
 * default for the embeddings axis because Anthropic does not offer an embeddings endpoint (DD-005).
 *
 * <p>Auth is a resolved {@link com.agentmemory.config.ProviderAuth}; the bearer token comes from
 * {@link com.agentmemory.config.ProviderAuth#requireApiKey} (invariant #14). The model defaults to
 * {@link #DEFAULT_MODEL} and its known output width to {@link #DEFAULT_DIMENSIONS}; every returned
 * vector is asserted to match {@link #dimensions()} so a provider/model mismatch fails loudly here
 * rather than corrupting the {@code pgvector} column (invariant #8, and the dim contract #4 relies
 * on).
 *
 * <p>The embeddings probe is non-fatal at startup (DD-005): if Voyage is unreachable, recall
 * degrades to FTS + graph and the gate logs a warning instead of aborting.
 */
public final class VoyageEmbedder implements Embedder {

    /** Provider key — the data-driven factory key and denormalized {@code provider} value. */
    public static final String PROVIDER_KEY = "voyage";

    /** Default embedding model. {@code voyage-3} emits 1024-dim vectors (see {@link #DEFAULT_DIMENSIONS}). */
    public static final String DEFAULT_MODEL = "voyage-3";

    /**
     * Default embedding dimensionality for {@link #DEFAULT_MODEL} — <strong>1024</strong>. This is
     * the embedding-dim contract issue #4's {@code page_embeddings} {@code pgvector} column must be
     * sized to; it is documented in {@code docs/ARCHITECTURE.md} §4.2 and on
     * {@link Embedder#dimensions()}. A different model implies a different width, set via config.
     */
    public static final int DEFAULT_DIMENSIONS = 1024;

    /** Default Voyage base; overridable via {@code ProviderAuth.baseUrl}. */
    public static final String DEFAULT_BASE_URL = "https://api.voyageai.com";

    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final URI embeddingsUri;
    private final HttpJsonClient client;

    /**
     * Build an embedder from resolved auth, using the default {@value #DEFAULT_DIMENSIONS}-dim width
     * for the default model.
     *
     * @param auth the resolved, typed credentials for the embeddings axis.
     */
    public VoyageEmbedder(com.agentmemory.config.ProviderAuth auth) {
        this(auth, DEFAULT_DIMENSIONS, new HttpJsonClient(Duration.ofSeconds(5), Duration.ofSeconds(30)));
    }

    /**
     * Build an embedder with an explicit dimensionality (for non-default models) and an injectable
     * HTTP client (tests).
     *
     * @param auth       the resolved credentials.
     * @param dimensions the expected output width for the configured model.
     * @param client     the JSON-over-HTTP helper.
     */
    VoyageEmbedder(com.agentmemory.config.ProviderAuth auth, int dimensions, HttpJsonClient client) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        this.apiKey = auth.requireApiKey(PROVIDER_KEY);
        this.model = auth.modelOr(DEFAULT_MODEL);
        this.dimensions = dimensions;
        String base = stripTrailingSlash(
                auth.baseUrl() == null || auth.baseUrl().isBlank() ? DEFAULT_BASE_URL : auth.baseUrl());
        this.embeddingsUri = URI.create(base + "/v1/embeddings");
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
                    "Voyage response 'data' missing or wrong size (expected " + texts.size() + ").", null);
        }
        String usedModel = textOr(root.get("model"), model);

        // Order by the per-item 'index' so results line up with inputs even if the API reorders.
        EmbeddingResult[] ordered = new EmbeddingResult[texts.size()];
        for (JsonNode item : data) {
            int index = item.has("index") ? item.get("index").intValue() : -1;
            if (index < 0 || index >= ordered.length) {
                throw LlmException.permanent("Voyage response item had an out-of-range index: " + index, null);
            }
            ordered[index] = toResult(item.get("embedding"), usedModel);
        }
        List<EmbeddingResult> results = new ArrayList<>(ordered.length);
        for (int i = 0; i < ordered.length; i++) {
            if (ordered[i] == null) {
                throw LlmException.permanent("Voyage response missing embedding for index " + i, null);
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
        // Embed a trivial token; this checks connectivity, the key, and — via toResult — that the
        // model's output width matches the configured dim contract.
        embed("ping");
    }

    private EmbeddingResult toResult(JsonNode embedding, String usedModel) {
        if (embedding == null || !embedding.isArray() || embedding.isEmpty()) {
            throw LlmException.permanent("Voyage response item had no 'embedding' array.", null);
        }
        if (embedding.size() != dimensions) {
            // The denormalized-dim contract (invariant #8 / issue #4) is violated — fail, do not store.
            throw LlmException.permanent(
                    "Voyage model '" + usedModel + "' returned a " + embedding.size()
                            + "-dim vector but this embedder is configured for " + dimensions
                            + " dims. Set 'agent-memory.embeddings.auth.model' to a matching model "
                            + "or reconfigure the dimension.", null);
        }
        float[] vector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) embedding.get(i).doubleValue();
        }
        return new EmbeddingResult(vector, PROVIDER_KEY, usedModel, dimensions);
    }

    private static String textOr(JsonNode node, String fallback) {
        return node != null && node.isString() && !node.stringValue().isBlank()
                ? node.stringValue() : fallback;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
