package com.agentmemory.llm;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Anthropic Claude implementation of {@link LlmProvider}, talking to the Messages API
 * ({@code POST /v1/messages}) over the JDK HTTP client. This is the recommended default chat
 * provider for the required-LLM dependency (DD-005).
 *
 * <p>Auth is a resolved {@link com.agentmemory.config.ProviderAuth} supplied at construction — the
 * {@code x-api-key} comes from {@link com.agentmemory.config.ProviderAuth#requireApiKey} and never
 * from a {@code System.getenv} read here (invariant #14). Structured output uses Anthropic's native
 * {@code output_config.format = {type: "json_schema", schema: …}} mode when the request carries a
 * {@link JsonSchema} (invariant #7); Anthropic guarantees the first text block is JSON validating
 * against that schema. As a tolerant fallback, the reply is still re-parsed our side and a
 * non-parseable structured reply is surfaced as a permanent {@link LlmException}.
 *
 * <p>Model selection is config-driven ({@link com.agentmemory.config.ProviderAuth#model()},
 * defaulting to {@link #DEFAULT_MODEL}); a default base URL of {@link #DEFAULT_BASE_URL} can be
 * overridden for proxies/gateways.
 */
public final class AnthropicLlmProvider implements LlmProvider {

    /** Provider key — the data-driven factory key and denormalized {@code provider} value. */
    public static final String PROVIDER_KEY = "anthropic";

    /** Default chat model when config does not pin one (the latest Opus at time of writing). */
    public static final String DEFAULT_MODEL = "claude-opus-4-8";

    /** Default Messages API base; overridable via {@code ProviderAuth.baseUrl} for gateways. */
    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";

    /** Pinned Messages API version header value. */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final String apiKey;
    private final String model;
    private final URI messagesUri;
    private final HttpJsonClient client;

    /**
     * Build a provider from resolved auth. The API key is required up front (fails fast if absent);
     * model and base URL fall back to {@link #DEFAULT_MODEL} / {@link #DEFAULT_BASE_URL}.
     *
     * @param auth the resolved, typed credentials for the chat axis.
     */
    public AnthropicLlmProvider(com.agentmemory.config.ProviderAuth auth) {
        this(auth, new HttpJsonClient(Duration.ofSeconds(5), Duration.ofSeconds(60)));
    }

    /** Package-visible constructor letting tests inject a pre-built {@link HttpJsonClient}. */
    AnthropicLlmProvider(com.agentmemory.config.ProviderAuth auth, HttpJsonClient client) {
        this.apiKey = auth.requireApiKey(PROVIDER_KEY);
        this.model = auth.modelOr(DEFAULT_MODEL);
        String base = stripTrailingSlash(
                auth.baseUrl() == null || auth.baseUrl().isBlank() ? DEFAULT_BASE_URL : auth.baseUrl());
        this.messagesUri = URI.create(base + "/v1/messages");
        this.client = client;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        ObjectMapper m = client.mapper();
        ObjectNode body = buildBody(m, request);

        Map<String, String> headers = Map.of(
                "x-api-key", apiKey,
                "anthropic-version", ANTHROPIC_VERSION);

        JsonNode root = client.postJson(messagesUri, headers, body, PROVIDER_KEY);

        String stopReason = text(root.get("stop_reason"));
        if ("refusal".equals(stopReason)) {
            throw LlmException.permanent(
                    "Anthropic declined the request (stop_reason=refusal); discard and do not retry.", null);
        }

        String reply = extractText(root);
        if (request.wantsStructuredOutput()) {
            validateJson(reply, request.schema());
        }

        String usedModel = textOr(root.get("model"), model);
        int inTokens = usage(root, "input_tokens");
        int outTokens = usage(root, "output_tokens");
        return new ChatResponse(reply, PROVIDER_KEY, usedModel, inTokens, outTokens);
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
        // A minimal, structured one-token round-trip exercises connectivity, the key, and the
        // model id — the same path real calls take — while staying cheap enough for startup.
        ChatRequest ping = new ChatRequest(
                List.of(ChatMessage.user("ping")),
                new JsonSchema("probe", "{\"type\":\"object\","
                        + "\"properties\":{\"ok\":{\"type\":\"boolean\"}},"
                        + "\"required\":[\"ok\"],\"additionalProperties\":false}"),
                16);
        chat(ping);
    }

    // --- request building ----------------------------------------------------------------------

    private ObjectNode buildBody(ObjectMapper m, ChatRequest request) {
        ObjectNode body = m.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", request.maxOutputTokens());

        // Anthropic hoists system content to a top-level field; user/assistant turns stay in messages.
        StringBuilder system = new StringBuilder();
        ArrayNode messages = body.putArray("messages");
        for (ChatMessage msg : request.messages()) {
            if (msg.role() == ChatMessage.Role.SYSTEM) {
                if (!system.isEmpty()) {
                    system.append("\n\n");
                }
                system.append(msg.content());
                continue;
            }
            ObjectNode node = messages.addObject();
            node.put("role", msg.role() == ChatMessage.Role.ASSISTANT ? "assistant" : "user");
            node.put("content", msg.content());
        }
        if (messages.isEmpty()) {
            // Messages API rejects an empty messages[]; a system-only request needs a user turn.
            messages.addObject().put("role", "user").put("content", "Proceed.");
        }
        if (!system.isEmpty()) {
            body.put("system", system.toString());
        }

        if (request.wantsStructuredOutput()) {
            ObjectNode outputConfig = body.putObject("output_config");
            ObjectNode format = outputConfig.putObject("format");
            format.put("type", "json_schema");
            format.set("schema", parseSchema(m, request.schema()));
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

    // --- response parsing ----------------------------------------------------------------------

    /** Concatenate the {@code text} of every {@code type:"text"} block in {@code content[]}. */
    private String extractText(JsonNode root) {
        JsonNode content = root.get("content");
        if (content == null || !content.isArray()) {
            throw LlmException.permanent(
                    "Anthropic response had no content array; cannot extract a reply.", null);
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode block : content) {
            if ("text".equals(text(block.get("type")))) {
                JsonNode t = block.get("text");
                if (t != null && t.isString()) {
                    parts.add(t.stringValue());
                }
            }
        }
        if (parts.isEmpty()) {
            throw LlmException.permanent(
                    "Anthropic response contained no text blocks (stop_reason="
                            + text(root.get("stop_reason")) + ").", null);
        }
        return String.join("", parts);
    }

    private void validateJson(String reply, JsonSchema schema) {
        // Tolerant fallback: even though Anthropic validates server-side, confirm the reply is
        // well-formed JSON so a downstream parser never chokes. (Full schema validation is left to
        // the provider; we only guard JSON well-formedness here.)
        try {
            client.parse(reply);
        } catch (LlmException e) {
            throw LlmException.permanent(
                    "Structured reply for schema '" + schema.name()
                            + "' was not valid JSON despite a json_schema request: " + e.getMessage(), e);
        }
    }

    private static int usage(JsonNode root, String field) {
        JsonNode usage = root.get("usage");
        if (usage == null) {
            return -1;
        }
        JsonNode value = usage.get(field);
        return value != null && value.isIntegralNumber() ? value.intValue() : -1;
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
