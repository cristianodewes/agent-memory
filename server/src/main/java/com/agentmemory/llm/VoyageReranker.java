package com.agentmemory.llm;

import com.agentmemory.config.ProviderAuth;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Voyage AI cross-encoder reranker ({@code POST /v1/rerank}, model {@code rerank-2-lite}) — the
 * calibrated, non-generative ranking backend for LLM-assisted recall (issue #130, Fase 2). It scores
 * a query against a set of candidate documents and returns a {@code relevance_score} in {@code [0, 1]}
 * for <strong>every</strong> document, so the recall layer never faces the full-coverage fragility of
 * the generative reranker and gets a score calibrated enough to drive an absolute relevance gate.
 *
 * <p>Auth reuses the <em>embeddings</em> {@link ProviderAuth} (DD-005): the rerank API takes the same
 * Voyage API key as {@link VoyageEmbedder}, so a deployment that already configured Voyage embeddings
 * gets the cross-encoder for free. The rerank model is passed explicitly (it differs from the
 * embedding model on the same key), defaulting to {@link #DEFAULT_MODEL}.
 *
 * <p>Built on the shared {@link HttpJsonClient} exactly like {@link VoyageEmbedder}; the recall path
 * hands {@link #scoreDocuments} a short, budget-derived per-call timeout so a slow rerank is cancelled
 * inside the client's deadline. On any failure the caller ({@code CrossEncoderReranker}) degrades to
 * the LLM reranker / raw RRF order — this client just maps a failure to an {@link LlmException}.
 */
public final class VoyageReranker implements CrossEncoderClient {

    /** Auth label / provider key — the same Voyage key the embedder uses (DD-005). */
    public static final String PROVIDER_KEY = VoyageEmbedder.PROVIDER_KEY;

    /** Default cross-encoder model: the fast, cheap reranker (~50-150ms). */
    public static final String DEFAULT_MODEL = "rerank-2-lite";

    private final String apiKey;
    private final String model;
    private final URI rerankUri;
    private final HttpJsonClient client;

    /**
     * Build a reranker from the resolved embeddings auth, using a default HTTP client (the recall path
     * bounds each call with a budget-derived per-call timeout regardless).
     *
     * @param auth  the resolved Voyage credentials (the embeddings axis' auth).
     * @param model the rerank model id (e.g. {@value #DEFAULT_MODEL}).
     */
    public VoyageReranker(ProviderAuth auth, String model) {
        this(auth, model, new HttpJsonClient(Duration.ofSeconds(5), Duration.ofSeconds(30)));
    }

    /**
     * Build a reranker with an injectable HTTP client (tests).
     *
     * @param auth   the resolved credentials.
     * @param model  the rerank model id.
     * @param client the JSON-over-HTTP helper.
     */
    VoyageReranker(ProviderAuth auth, String model, HttpJsonClient client) {
        this.apiKey = auth.requireApiKey(PROVIDER_KEY);
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model;
        String base = stripTrailingSlash(
                auth.baseUrl() == null || auth.baseUrl().isBlank()
                        ? VoyageEmbedder.DEFAULT_BASE_URL : auth.baseUrl());
        this.rerankUri = URI.create(base + "/v1/rerank");
        this.client = client;
    }

    @Override
    public double[] scoreDocuments(String query, List<String> documents, Duration requestTimeout) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("VoyageReranker.scoreDocuments requires non-blank query");
        }
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("VoyageReranker.scoreDocuments requires a non-empty list");
        }

        ObjectMapper m = client.mapper();
        ObjectNode body = m.createObjectNode();
        body.put("model", model);
        body.put("query", query);
        ArrayNode docs = body.putArray("documents");
        for (String d : documents) {
            if (d == null || d.isBlank()) {
                throw new IllegalArgumentException("VoyageReranker documents must be non-blank");
            }
            docs.add(d);
        }
        // Ask for a score for every document (top_k = N), so the result is full-coverage by construction.
        body.put("top_k", documents.size());

        Map<String, String> headers = Map.of("authorization", "Bearer " + apiKey);
        JsonNode root = client.postJson(rerankUri, headers, body, PROVIDER_KEY, requestTimeout);

        JsonNode data = root.get("data");
        if (data == null || !data.isArray() || data.size() != documents.size()) {
            throw LlmException.permanent(
                    "Voyage rerank 'data' missing or wrong size (expected " + documents.size() + ").", null);
        }

        // Map each item back to its input position by 'index'; require a calibrated score for all.
        double[] scores = new double[documents.size()];
        boolean[] seen = new boolean[documents.size()];
        for (JsonNode item : data) {
            JsonNode idxNode = item.get("index");
            JsonNode scoreNode = item.get("relevance_score");
            if (idxNode == null || !idxNode.isIntegralNumber()) {
                throw LlmException.permanent("Voyage rerank item missing an integer 'index'.", null);
            }
            int index = idxNode.intValue();
            if (index < 0 || index >= scores.length) {
                throw LlmException.permanent("Voyage rerank item had an out-of-range index: " + index, null);
            }
            if (scoreNode == null || !scoreNode.isNumber()) {
                throw LlmException.permanent(
                        "Voyage rerank item " + index + " missing a numeric 'relevance_score'.", null);
            }
            scores[index] = clamp01(scoreNode.doubleValue());
            seen[index] = true;
        }
        for (int i = 0; i < seen.length; i++) {
            if (!seen[i]) {
                throw LlmException.permanent("Voyage rerank response missing a score for document " + i, null);
            }
        }
        return scores;
    }

    /** @return the configured rerank model id. */
    public String model() {
        return model;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
