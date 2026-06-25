package com.agentmemory.llm;

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
 * The OpenAI / OpenAI-compatible implementation of {@link LlmProvider}, talking to the Chat
 * Completions API ({@code POST {baseUrl}/chat/completions}) over the shared {@link HttpJsonClient}.
 *
 * <p>A single client pointed at a configurable {@code base_url} unlocks essentially <em>any</em>
 * LLM that speaks the OpenAI wire format: OpenAI itself, OpenRouter, DeepSeek, Groq, Together,
 * Mistral, Google Gemini's OpenAI-compatible endpoint, and every local engine (Ollama, vLLM,
 * LM Studio, llama.cpp server). The {@code openai} key may use the default OpenAI endpoint; the
 * {@code openai-compat} key <strong>requires</strong> an explicit {@code base_url} (fail fast),
 * since "OpenAI-compatible" only means something once an endpoint is named.
 *
 * <p><b>Auth (invariant #14).</b> Credentials are resolved from the typed
 * {@link com.agentmemory.config.ProviderAuth} at construction, never from a {@code System.getenv}
 * read at a call site. The API key is <em>optional</em>: keyless local engines legitimately carry
 * no key, in which case the {@code Authorization} header is omitted entirely; when a key is present
 * it is sent as {@code Authorization: Bearer <key>}.
 *
 * <p><b>Structured output (invariant #7).</b> When the request carries a {@link JsonSchema}, the
 * provider asks for {@code response_format = {type:"json_schema", json_schema:{name, schema,
 * strict:true}}} — OpenAI's strict Structured Outputs mode, which guarantees a schema-valid reply.
 * Engines that do not implement {@code json_schema} reject that request; the provider then performs
 * a documented <em>tolerant fallback</em>: it retries the same call with
 * {@code response_format = {type:"json_object"}} <em>and</em> the schema injected into a system
 * turn, then validates the reply against {@link JsonSchema}. A degraded-strictness warning is
 * logged once. A reply that does not parse as JSON is always surfaced as a permanent
 * {@link LlmException} — malformed JSON never passes silently.
 *
 * <p>Model selection is config-driven ({@link com.agentmemory.config.ProviderAuth#modelOr(String)},
 * defaulting to {@link #DEFAULT_MODEL}); local engines must pin their own model id, as there is no
 * universal default across compatible engines.
 */
public final class OpenAiCompatLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatLlmProvider.class);

    /** Canonical provider key for the native OpenAI endpoint. */
    public static final String PROVIDER_KEY = "openai";

    /** Alias key for self-hosted / third-party OpenAI-compatible endpoints. */
    public static final String COMPAT_KEY = "openai-compat";

    /** Default chat model for the native {@code openai} endpoint when config does not pin one. */
    public static final String DEFAULT_MODEL = "gpt-5.5";

    /** Default Chat Completions base for the native {@code openai} key; includes the {@code /v1}. */
    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    private final String key;
    private final String apiKey; // null/blank => keyless engine, no Authorization header
    private final String model;
    private final URI chatUri;
    private final HttpJsonClient client;

    /** One-shot guard so the degraded-strictness fallback is logged at most once per provider. */
    private final AtomicBoolean fallbackWarned = new AtomicBoolean(false);

    /**
     * Build a provider from resolved auth. The model and (for {@code openai}) the base URL fall back
     * to {@link #DEFAULT_MODEL} / {@link #DEFAULT_BASE_URL}; {@code openai-compat} requires an
     * explicit base URL and fails fast otherwise. The API key is optional — a keyless engine sends
     * no {@code Authorization} header.
     *
     * @param key  the registered provider key — {@link #PROVIDER_KEY} or {@link #COMPAT_KEY}.
     * @param auth the resolved, typed credentials for the chat axis.
     */
    public OpenAiCompatLlmProvider(String key, com.agentmemory.config.ProviderAuth auth) {
        this(key, auth, new HttpJsonClient(Duration.ofSeconds(5), Duration.ofSeconds(60)));
    }

    /** Package-visible constructor letting tests inject a pre-built {@link HttpJsonClient}. */
    OpenAiCompatLlmProvider(String key, com.agentmemory.config.ProviderAuth auth, HttpJsonClient client) {
        this.key = key;
        // Auth boundary (invariant #14): resolve up front. The key is OPTIONAL here — keyless local
        // engines are legitimate — so we read it permissively rather than requiring it.
        this.apiKey = auth.hasApiKey() ? auth.apiKey() : null;
        this.model = auth.modelOr(DEFAULT_MODEL);
        this.chatUri = URI.create(resolveBaseUrl(key, auth) + "/chat/completions");
        this.client = client;
    }

    /**
     * Resolve the base URL: {@code openai} defaults to {@link #DEFAULT_BASE_URL}; {@code openai-compat}
     * requires an explicit, non-blank {@code base_url}. A trailing slash is normalized off so the
     * {@code /chat/completions} suffix never double-slashes.
     */
    private static String resolveBaseUrl(String key, com.agentmemory.config.ProviderAuth auth) {
        String configured = auth.baseUrl();
        boolean hasBase = configured != null && !configured.isBlank();
        if (!hasBase) {
            if (COMPAT_KEY.equals(key)) {
                throw LlmException.permanent(
                        "The '" + COMPAT_KEY + "' provider requires an explicit base URL. Set "
                                + "'agent-memory.llm.auth.base-url' to your endpoint (e.g. "
                                + "http://localhost:11434/v1 for Ollama, or https://openrouter.ai/api/v1). "
                                + "Use the 'openai' provider if you want the default OpenAI endpoint.", null);
            }
            return DEFAULT_BASE_URL;
        }
        return stripTrailingSlash(configured.strip());
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
        return key;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public void probe() {
        // A minimal, structured one-token round-trip exercises connectivity, the (optional) key, the
        // model id, and the structured-output path — the same path real calls take — while staying
        // cheap enough for the startup health gate (invariant #13).
        ChatRequest ping = new ChatRequest(
                List.of(ChatMessage.user("ping")),
                new JsonSchema("probe", "{\"type\":\"object\","
                        + "\"properties\":{\"ok\":{\"type\":\"boolean\"}},"
                        + "\"required\":[\"ok\"],\"additionalProperties\":false}"),
                16);
        chat(ping);
    }

    // --- structured-output orchestration -------------------------------------------------------

    /**
     * Drive the structured path: prefer strict {@code json_schema}; if the endpoint rejects that mode
     * (a permanent 4xx — many compatible engines do not implement it), retry once with the tolerant
     * {@code json_object} fallback plus a prompt-injected schema, then validate. Retryable failures
     * (timeouts, 429, 5xx) are propagated as-is so the caller's backoff still applies.
     */
    private ChatResponse structuredChat(ChatRequest request) {
        try {
            return send(request, StructuredMode.JSON_SCHEMA);
        } catch (LlmException e) {
            if (e.isRetryable() || !looksLikeUnsupportedSchema(e)) {
                throw e;
            }
            if (fallbackWarned.compareAndSet(false, true)) {
                log.warn("Endpoint at {} rejected response_format=json_schema; falling back to "
                        + "json_object with a prompt-injected schema and client-side JSON validation. "
                        + "Replies are validated as well-formed JSON but the engine no longer enforces "
                        + "the schema server-side (degraded strictness). Detail: {}", chatUri, e.getMessage());
            }
            return send(request, StructuredMode.JSON_OBJECT);
        }
    }

    /**
     * Heuristic: did the endpoint reject the request <em>because</em> it does not support the
     * {@code json_schema} response format (versus a genuine bad request)? OpenAI-compatible engines
     * vary in wording, so we look for the response-format / schema vocabulary in the surfaced error.
     * Conservative by design — anything we cannot attribute to schema support is re-thrown.
     */
    private static boolean looksLikeUnsupportedSchema(LlmException e) {
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase(java.util.Locale.ROOT);
        // Only consider a 4xx (the client/transport layer marks 5xx/429/408 retryable, handled above).
        boolean mentionsFormat = m.contains("response_format") || m.contains("response format")
                || m.contains("json_schema") || m.contains("json schema");
        boolean mentionsRejection = m.contains("not support") || m.contains("unsupported")
                || m.contains("unrecognized") || m.contains("unknown") || m.contains("invalid")
                || m.contains("unexpected") || m.contains("must be");
        return mentionsFormat && mentionsRejection;
    }

    // --- request building & dispatch -----------------------------------------------------------

    private enum StructuredMode { NONE, JSON_SCHEMA, JSON_OBJECT }

    private ChatResponse send(ChatRequest request, StructuredMode mode) {
        ObjectMapper m = client.mapper();
        ObjectNode body = buildBody(m, request, mode);

        JsonNode root = client.postJson(chatUri, authHeaders(), body, key);

        JsonNode choice = firstChoice(root);
        String finishReason = text(path(choice, "finish_reason"));
        if ("content_filter".equals(finishReason)) {
            throw LlmException.permanent(
                    "The endpoint filtered the response (finish_reason=content_filter); "
                            + "discard and do not retry.", null);
        }
        JsonNode refusal = path(path(choice, "message"), "refusal");
        if (refusal != null && refusal.isString() && !refusal.stringValue().isBlank()) {
            throw LlmException.permanent(
                    "The model declined the request (refusal): " + refusal.stringValue(), null);
        }

        String reply = extractContent(choice);
        if (request.wantsStructuredOutput()) {
            validateJson(reply, request.schema());
        }

        String usedModel = textOr(root.get("model"), model);
        int inTokens = usage(root, "prompt_tokens");
        int outTokens = usage(root, "completion_tokens");
        return new ChatResponse(reply, key, usedModel, inTokens, outTokens);
    }

    /** {@code Authorization: Bearer <key>} when a key is present; no auth header for keyless engines. */
    private Map<String, String> authHeaders() {
        return apiKey == null ? Map.of() : Map.of("authorization", "Bearer " + apiKey);
    }

    private ObjectNode buildBody(ObjectMapper m, ChatRequest request, StructuredMode mode) {
        ObjectNode body = m.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", request.maxOutputTokens());

        // OpenAI keeps system/developer turns inline in messages[] (unlike Anthropic's hoisting).
        ArrayNode messages = body.putArray("messages");
        if (mode == StructuredMode.JSON_OBJECT) {
            // Tolerant fallback: the engine no longer enforces the schema, so we instruct it to, and
            // ensure the literal word "json" appears (OpenAI's json_object mode requires it).
            messages.addObject()
                    .put("role", "system")
                    .put("content", "You must respond with a single JSON object and nothing else. "
                            + "It must validate against this JSON Schema:\n" + request.schema().schemaJson());
        }
        for (ChatMessage msg : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", roleOf(msg.role()));
            node.put("content", msg.content());
        }
        if (messages.isEmpty()) {
            // Defensive: ChatRequest guarantees non-empty messages, but never send an empty array.
            messages.addObject().put("role", "user").put("content", "Proceed.");
        }

        if (request.wantsStructuredOutput()) {
            applyResponseFormat(m, body, request.schema(), mode);
        }
        return body;
    }

    private void applyResponseFormat(ObjectMapper m, ObjectNode body, JsonSchema schema, StructuredMode mode) {
        ObjectNode responseFormat = body.putObject("response_format");
        if (mode == StructuredMode.JSON_SCHEMA) {
            responseFormat.put("type", "json_schema");
            ObjectNode jsonSchema = responseFormat.putObject("json_schema");
            jsonSchema.put("name", schema.name());
            jsonSchema.put("strict", true);
            jsonSchema.set("schema", parseSchema(m, schema));
        } else { // JSON_OBJECT — tolerant fallback; the schema rides in the prompt (see buildBody).
            responseFormat.put("type", "json_object");
        }
    }

    private static String roleOf(ChatMessage.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case ASSISTANT -> "assistant";
            case USER -> "user";
        };
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

    private JsonNode firstChoice(JsonNode root) {
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw LlmException.permanent(
                    "Chat Completions response had no choices[]; cannot extract a reply.", null);
        }
        return choices.get(0);
    }

    /** Read {@code choices[0].message.content}, the assistant's reply text. */
    private String extractContent(JsonNode choice) {
        JsonNode content = path(path(choice, "message"), "content");
        if (content == null || !content.isString() || content.stringValue().isBlank()) {
            throw LlmException.permanent(
                    "Chat Completions response had no message content (finish_reason="
                            + text(path(choice, "finish_reason")) + ").", null);
        }
        return content.stringValue();
    }

    private void validateJson(String reply, JsonSchema schema) {
        // Confirm the reply is well-formed JSON so a downstream parser never chokes. In json_schema
        // mode the engine already guarantees this; in the json_object fallback it is the only guard.
        try {
            client.parse(reply);
        } catch (LlmException e) {
            throw LlmException.permanent(
                    "Structured reply for schema '" + schema.name()
                            + "' was not valid JSON: " + e.getMessage(), e);
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
