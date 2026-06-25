package com.agentmemory.llm;

import com.agentmemory.config.ProviderAuth;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The native Google Gemini implementation of {@link LlmProvider}, talking to the Generative Language
 * API ({@code POST {baseUrl}/v1beta/models/{model}:generateContent}) over the shared
 * {@link HttpJsonClient} (issue #40). Distinct from routing Gemini through its OpenAI-compatible shim
 * (#46's {@code openai-compat} key): this speaks Gemini's native wire format, so it gets Gemini's
 * first-class structured-output ({@code responseSchema}) and system-instruction handling.
 *
 * <p><b>Auth (invariant #14).</b> The API key is resolved from the typed {@link ProviderAuth} at
 * construction (never a call-site {@code System.getenv}) and sent as the {@code x-goog-api-key}
 * header. A key is required.
 *
 * <p><b>Structured output (invariant #7).</b> When the request carries a {@link JsonSchema}, the
 * provider asks for {@code generationConfig.responseMimeType = "application/json"} plus
 * {@code responseSchema} (Gemini's schema-constrained mode). Gemini's {@code responseSchema} accepts
 * only an OpenAPI subset, so a schema it rejects triggers a documented <em>tolerant fallback</em>:
 * the same call is retried with {@code responseMimeType = "application/json"} alone and the schema
 * injected into the system instruction, then the reply is validated as JSON locally. A
 * degraded-strictness warning is logged once. A reply that does not parse as JSON is always surfaced
 * as a permanent {@link LlmException}.
 *
 * <p>Model selection is config-driven ({@link ProviderAuth#modelOr(String)}, default
 * {@link #DEFAULT_MODEL}).
 */
public final class GeminiLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmProvider.class);

    /** Canonical provider key selecting this implementation in {@link ProviderFactory}. */
    public static final String PROVIDER_KEY = "gemini";

    /** Default chat model when config does not pin one. */
    public static final String DEFAULT_MODEL = "gemini-2.5-flash";

    /** Default Generative Language API base (includes the host, not the version). */
    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final HttpJsonClient client;
    private final AtomicBoolean fallbackWarned = new AtomicBoolean(false);

    public GeminiLlmProvider(ProviderAuth auth) {
        this(auth, new HttpJsonClient(Duration.ofSeconds(5), Duration.ofSeconds(60)));
    }

    GeminiLlmProvider(ProviderAuth auth, HttpJsonClient client) {
        this.apiKey = auth.requireApiKey(PROVIDER_KEY);
        this.model = auth.modelOr(DEFAULT_MODEL);
        this.baseUrl = stripTrailingSlash(
                auth.baseUrl() == null || auth.baseUrl().isBlank() ? DEFAULT_BASE_URL : auth.baseUrl());
        this.client = client;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        if (request.wantsStructuredOutput()) {
            return structuredChat(request);
        }
        return send(request, StructuredMode.NONE);
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
    public void probe() {
        // Minimal structured round-trip: exercises connectivity, key, model, and the same code path
        // real calls take (invariant #13 fail-fast at startup).
        ChatRequest ping = new ChatRequest(
                List.of(ChatMessage.user("ping")),
                new JsonSchema("probe", "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},"
                        + "\"required\":[\"ok\"]}"),
                16);
        chat(ping);
    }

    // --- structured-output orchestration ---------------------------------------------------------

    private ChatResponse structuredChat(ChatRequest request) {
        try {
            return send(request, StructuredMode.SCHEMA);
        } catch (LlmException e) {
            // responseSchema rejected (Gemini's subset is narrower than full JSON Schema)? Retry with
            // the tolerant fallback: application/json only + schema in the system instruction.
            if (e.isRetryable() || !looksLikeUnsupportedSchema(e)) {
                throw e;
            }
            if (fallbackWarned.compareAndSet(false, true)) {
                log.warn("Gemini rejected generationConfig.responseSchema; falling back to "
                        + "responseMimeType=application/json with the schema in the system instruction "
                        + "(Gemini no longer enforces the schema server-side: degraded strictness).");
            }
            return send(request, StructuredMode.JSON_ONLY);
        }
    }

    private static boolean looksLikeUnsupportedSchema(LlmException e) {
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase(java.util.Locale.ROOT);
        boolean mentionsSchema = m.contains("responseschema") || m.contains("response_schema")
                || m.contains("schema");
        boolean mentionsRejection = m.contains("not support") || m.contains("unsupported")
                || m.contains("unrecognized") || m.contains("unknown") || m.contains("invalid")
                || m.contains("400");
        return mentionsSchema && mentionsRejection;
    }

    private enum StructuredMode { NONE, SCHEMA, JSON_ONLY }

    private ChatResponse send(ChatRequest request, StructuredMode mode) {
        ObjectMapper m = client.mapper();
        ObjectNode body = buildBody(m, request, mode);

        URI uri = URI.create(baseUrl + "/v1beta/models/" + model + ":generateContent");
        Map<String, String> headers = Map.of("x-goog-api-key", apiKey);
        JsonNode root = client.postJson(uri, headers, body, PROVIDER_KEY);

        JsonNode candidate = firstCandidate(root);
        String finishReason = text(path(candidate, "finishReason"));
        if ("SAFETY".equals(finishReason) || "PROHIBITED_CONTENT".equals(finishReason)
                || "BLOCKLIST".equals(finishReason)) {
            throw LlmException.permanent(
                    "Gemini blocked the response (finishReason=" + finishReason + ").", null);
        }

        String reply = extractText(candidate);
        if (request.wantsStructuredOutput()) {
            validateJson(reply, request.schema());
        }

        String usedModel = textOr(root.get("modelVersion"), model);
        int inTokens = usage(root, "promptTokenCount");
        int outTokens = usage(root, "candidatesTokenCount");
        return new ChatResponse(reply, PROVIDER_KEY, usedModel, inTokens, outTokens);
    }

    // --- request building ------------------------------------------------------------------------

    private ObjectNode buildBody(ObjectMapper m, ChatRequest request, StructuredMode mode) {
        ObjectNode body = m.createObjectNode();

        // Gemini hoists system content into a top-level systemInstruction (like Anthropic, unlike
        // OpenAI). Collect SYSTEM turns; everything else becomes a content with role user/model.
        StringBuilder system = new StringBuilder();
        ArrayNode contents = body.putArray("contents");
        for (ChatMessage msg : request.messages()) {
            if (msg.role() == ChatMessage.Role.SYSTEM) {
                if (!system.isEmpty()) {
                    system.append("\n\n");
                }
                system.append(msg.content());
                continue;
            }
            ObjectNode content = contents.addObject();
            // Gemini roles are "user" and "model" (assistant -> model).
            content.put("role", msg.role() == ChatMessage.Role.ASSISTANT ? "model" : "user");
            content.putArray("parts").addObject().put("text", msg.content());
        }
        if (contents.isEmpty()) {
            contents.addObject().put("role", "user").putArray("parts").addObject().put("text", "Proceed.");
        }

        // In the tolerant fallback, the schema rides in the system instruction since responseSchema
        // is off.
        if (mode == StructuredMode.JSON_ONLY) {
            if (!system.isEmpty()) {
                system.append("\n\n");
            }
            system.append("You must respond with a single JSON object and nothing else. It must "
                    + "validate against this JSON Schema:\n").append(request.schema().schemaJson());
        }
        if (!system.isEmpty()) {
            ObjectNode sysInstruction = body.putObject("systemInstruction");
            sysInstruction.putArray("parts").addObject().put("text", system.toString());
        }

        ObjectNode genConfig = body.putObject("generationConfig");
        genConfig.put("maxOutputTokens", request.maxOutputTokens());
        if (request.wantsStructuredOutput()) {
            genConfig.put("responseMimeType", "application/json");
            if (mode == StructuredMode.SCHEMA) {
                genConfig.set("responseSchema", parseSchema(m, request.schema()));
            }
        }
        return body;
    }

    private JsonNode parseSchema(ObjectMapper m, JsonSchema schema) {
        try {
            return m.readTree(schema.schemaJson());
        } catch (JacksonException e) {
            throw LlmException.permanent(
                    "JsonSchema '" + schema.name() + "' is not valid JSON: " + e.getMessage(), e);
        }
    }

    // --- response parsing ------------------------------------------------------------------------

    private JsonNode firstCandidate(JsonNode root) {
        JsonNode candidates = root.get("candidates");
        if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
            // A prompt blocked before generation has no candidates but a promptFeedback.blockReason.
            JsonNode block = path(path(root, "promptFeedback"), "blockReason");
            String reason = text(block);
            throw LlmException.permanent(
                    "Gemini response had no candidates[]" + (reason == null ? "" : " (blockReason="
                            + reason + ")") + "; cannot extract a reply.", null);
        }
        return candidates.get(0);
    }

    private String extractText(JsonNode candidate) {
        JsonNode parts = path(path(candidate, "content"), "parts");
        if (parts == null || !parts.isArray() || parts.isEmpty()) {
            throw LlmException.permanent(
                    "Gemini candidate had no content.parts (finishReason="
                            + text(path(candidate, "finishReason")) + ").", null);
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : parts) {
            String t = text(path(part, "text"));
            if (t != null) {
                sb.append(t);
            }
        }
        if (sb.isEmpty()) {
            throw LlmException.permanent("Gemini candidate contained no text parts.", null);
        }
        return sb.toString();
    }

    private void validateJson(String reply, JsonSchema schema) {
        try {
            client.parse(reply);
        } catch (LlmException e) {
            throw LlmException.permanent(
                    "Structured reply for schema '" + schema.name()
                            + "' was not valid JSON: " + e.getMessage(), e);
        }
    }

    private static int usage(JsonNode root, String field) {
        JsonNode usage = root.get("usageMetadata");
        if (usage == null) {
            return -1;
        }
        JsonNode value = usage.get(field);
        return value != null && value.isIntegralNumber() ? value.intValue() : -1;
    }

    private static JsonNode path(JsonNode node, String field) {
        return node == null ? null : node.get(field);
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }

    private static String textOr(JsonNode node, String fallback) {
        String t = text(node);
        return t == null || t.isBlank() ? fallback : t;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
