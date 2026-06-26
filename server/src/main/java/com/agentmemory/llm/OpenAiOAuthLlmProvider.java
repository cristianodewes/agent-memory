package com.agentmemory.llm;

import com.agentmemory.config.ProviderAuth;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The OpenAI ChatGPT/Codex <em>OAuth</em> implementation of {@link LlmProvider} (issue #113): it lets
 * a ChatGPT subscription (Plus/Pro/Team) drive the memory instead of a per-token Platform API key.
 *
 * <p><b>Not a Chat Completions wrapper.</b> A ChatGPT OAuth access token is a Codex/ChatGPT
 * credential, not a {@code api.openai.com} Platform key — it is rejected there. Requests therefore go
 * to the Codex <em>Responses</em> backend ({@link #DEFAULT_RESPONSES_URL}) with the Codex beta headers
 * and the account id, and the reply arrives as a {@code text/event-stream} (SSE) that this provider
 * reconstructs into a single completion. This mirrors the {@code ai-memory} reference and the Codex CLI.
 *
 * <p><b>Auth (invariant #14, DD-001).</b> The long-lived refresh token lives in the shared token file
 * ({@link OpenAiOAuthTokenStore}) the {@code agent-memory auth login openai-oauth} flow writes — the
 * server is the sole holder of state and the only component that refreshes. The token is resolved at
 * construction (fail fast if absent: "run the login"); each call mints/serves a short-lived access
 * token, refreshing it transparently from the refresh token a margin before expiry and writing the
 * rotated credential back to the file. The Codex client id and token endpoint are fixed, not config.
 *
 * <p><b>Structured output (invariant #7).</b> A request carrying a {@link JsonSchema} sets the
 * Responses-API {@code text.format = {type:"json_schema", name, schema, strict:true}} mode; the
 * reconstructed reply is validated as well-formed JSON, and a non-JSON reply is surfaced as a
 * permanent {@link LlmException} — malformed JSON never passes silently.
 */
public final class OpenAiOAuthLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiOAuthLlmProvider.class);

    /** Provider key — the data-driven factory key and denormalized {@code provider} value. */
    public static final String PROVIDER_KEY = "openai-oauth";

    /** Default chat model when config does not pin one. */
    public static final String DEFAULT_MODEL = "gpt-5.5";

    /** Default filename of the shared token file under the data dir. */
    public static final String DEFAULT_TOKEN_FILE = "auth.json";

    /** Public Codex/OpenCode OAuth client id used for the refresh-token grant (fixed, not config). */
    public static final String CODEX_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";

    /** OpenAI OAuth token endpoint (the {@code refresh_token} grant target). */
    public static final String DEFAULT_TOKEN_URL = "https://auth.openai.com/oauth/token";

    /** ChatGPT/Codex Responses backend — where OAuth chat requests go (override via {@code base-url}). */
    public static final String DEFAULT_RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses";

    /** Refresh the access token this long before its reported expiry (so an in-flight call stays valid). */
    private static final long REFRESH_MARGIN_MS = 60_000;

    /** Assumed access-token lifetime when a token response omits {@code expires_in}. */
    private static final long DEFAULT_EXPIRES_IN_SECONDS = 3600;

    /** SSE terminal-error / empty-reply status: the upstream connected but sent no usable completion. */
    private static final int SSE_TERMINAL_ERROR_STATUS = 502;

    private final String model;
    private final OpenAiOAuthTokenStore store;
    private final HttpJsonClient client;
    private final URI tokenUri;
    private final URI responsesUri;
    private final String clientId;
    private final LongSupplier nowMs;

    /** Cached token, guarded by {@code this}; refreshed (and persisted) when within the margin of expiry. */
    private OpenAiOAuthTokenStore.Token cached;

    /**
     * Build from resolved auth: the token-file path (resolved by the wiring under the data dir), the
     * model ({@link #DEFAULT_MODEL} fallback), and an optional {@code base-url} override of the Codex
     * Responses endpoint. The token is loaded up front; a missing token fails fast.
     *
     * @param auth the resolved, typed credentials for the chat axis.
     */
    public OpenAiOAuthLlmProvider(ProviderAuth auth) {
        this(auth, new HttpJsonClient(Duration.ofSeconds(10), Duration.ofSeconds(300)),
                System::currentTimeMillis);
    }

    /** Package-visible constructor that derives endpoints/store from {@code auth}; tests inject the clock. */
    OpenAiOAuthLlmProvider(ProviderAuth auth, HttpJsonClient client, LongSupplier nowMs) {
        this(auth.modelOr(DEFAULT_MODEL), new OpenAiOAuthTokenStore(resolveTokenFile(auth)), client,
                URI.create(DEFAULT_TOKEN_URL), URI.create(resolveResponsesUrl(auth)), CODEX_CLIENT_ID, nowMs);
    }

    /** Fully-injectable constructor (endpoints, store, clock) for hermetic loopback tests. */
    OpenAiOAuthLlmProvider(String model, OpenAiOAuthTokenStore store, HttpJsonClient client,
            URI tokenUri, URI responsesUri, String clientId, LongSupplier nowMs) {
        this.model = model;
        this.store = store;
        this.client = client;
        this.tokenUri = tokenUri;
        this.responsesUri = responsesUri;
        this.clientId = clientId;
        this.nowMs = nowMs;
        // Auth boundary (invariant #14): the refresh token must already exist; load it up front so a
        // missing credential fails fast at construction with an actionable "run the login" message.
        this.cached = store.load().orElseThrow(() -> LlmException.permanent(
                "No openai-oauth token found at '" + store.path() + "'. Sign in with "
                        + "`agent-memory auth login openai-oauth` (a ChatGPT subscription login) to create it.",
                null));
    }

    private static Path resolveTokenFile(ProviderAuth auth) {
        String tf = auth.oauth() == null ? null : auth.oauth().tokenFile();
        if (tf == null || tf.isBlank()) {
            throw LlmException.permanent(
                    "The 'openai-oauth' provider requires a token file. Leave "
                            + "'agent-memory.llm.auth.oauth.token-file' unset to use <data-dir>/"
                            + DEFAULT_TOKEN_FILE + ", or point it at the file `auth login openai-oauth` wrote.",
                    null);
        }
        return Path.of(tf.strip());
    }

    private static String resolveResponsesUrl(ProviderAuth auth) {
        String base = auth.baseUrl();
        return (base == null || base.isBlank()) ? DEFAULT_RESPONSES_URL : base.strip();
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
        // A minimal structured round-trip exercises the token refresh, the account header, the Codex
        // backend, and the SSE/structured path — the same path real calls take — for the startup gate.
        ChatRequest ping = new ChatRequest(
                List.of(ChatMessage.user("ping")),
                new JsonSchema("probe", "{\"type\":\"object\","
                        + "\"properties\":{\"ok\":{\"type\":\"boolean\"}},"
                        + "\"required\":[\"ok\"],\"additionalProperties\":false}"),
                16);
        chat(ping);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        OpenAiOAuthTokenStore.Token token = currentToken();
        ObjectMapper m = client.mapper();
        ObjectNode body = buildBody(m, request);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("authorization", "Bearer " + token.access());
        headers.put("accept", "text/event-stream");
        headers.put("openai-beta", "responses=experimental");
        headers.put("originator", "codex_cli_rs");
        headers.put("session_id", UUID.randomUUID().toString());
        if (token.accountId() != null && !token.accountId().isBlank()) {
            headers.put("chatgpt-account-id", token.accountId());
        }

        String sse = client.postJsonForText(responsesUri, headers, body, PROVIDER_KEY);
        Completed completed = parseSse(sse);

        String reply = completed.outputText();
        if (reply == null || reply.isBlank()) {
            throw LlmException.permanent("openai-oauth returned an empty completion.", null);
        }
        if (request.wantsStructuredOutput()) {
            validateJson(reply, request.schema());
        }
        return new ChatResponse(reply, PROVIDER_KEY, completed.modelOr(model),
                completed.inputTokens(), completed.outputTokens());
    }

    // --- token freshness -----------------------------------------------------------------------

    /** The current access token, refreshing (and persisting the rotation) when within the margin of expiry. */
    private synchronized OpenAiOAuthTokenStore.Token currentToken() {
        if (nowMs.getAsLong() + REFRESH_MARGIN_MS >= cached.expiresAtMs()) {
            log.info("openai-oauth access token expired or near expiry — refreshing.");
            OpenAiOAuthTokenStore.Token refreshed = refresh(cached);
            store.save(refreshed);
            cached = refreshed;
        }
        return cached;
    }

    /** Exchange the refresh token for a new access token at the OpenAI token endpoint. */
    private OpenAiOAuthTokenStore.Token refresh(OpenAiOAuthTokenStore.Token current) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", current.refresh());
        form.put("client_id", clientId);

        JsonNode response;
        try {
            response = client.postForm(tokenUri, Map.of(), form, PROVIDER_KEY + " token endpoint");
        } catch (LlmException e) {
            throw new LlmException(
                    "openai-oauth token refresh failed: " + e.getMessage() + ". If the refresh token was "
                            + "revoked or expired, sign in again with `agent-memory auth login openai-oauth`.",
                    e, e.isRetryable(), e.httpStatus());
        }

        String access = text(response, "access_token");
        if (access == null || access.isBlank()) {
            throw LlmException.permanent("openai-oauth token endpoint returned no access_token.", null);
        }
        // A refresh response may omit the refresh token — keep the existing one when so.
        String refresh = textOr(response, "refresh_token", current.refresh());
        long expiresIn = positiveLongOr(response, "expires_in", DEFAULT_EXPIRES_IN_SECONDS);
        // The account id rides in the id_token (or access token) claims; fall back to the prior value.
        String accountId = accountIdFromJwt(text(response, "id_token"))
                .or(() -> accountIdFromJwt(access))
                .orElse(current.accountId());

        return new OpenAiOAuthTokenStore.Token(
                access, refresh, nowMs.getAsLong() + expiresIn * 1000, accountId);
    }

    // --- request building ----------------------------------------------------------------------

    private ObjectNode buildBody(ObjectMapper m, ChatRequest request) {
        ObjectNode body = m.createObjectNode();
        body.put("model", model);

        // The Responses API hoists system content to a top-level `instructions`; user/assistant turns
        // become `input[]` items with `input_text` content (mirrors the Codex CLI request shape).
        StringBuilder instructions = new StringBuilder();
        ArrayNode input = body.putArray("input");
        for (ChatMessage msg : request.messages()) {
            if (msg.role() == ChatMessage.Role.SYSTEM) {
                if (!instructions.isEmpty()) {
                    instructions.append("\n\n");
                }
                instructions.append(msg.content());
                continue;
            }
            ObjectNode item = input.addObject();
            item.put("role", msg.role() == ChatMessage.Role.ASSISTANT ? "assistant" : "user");
            item.putArray("content").addObject().put("type", "input_text").put("text", msg.content());
        }
        if (input.isEmpty()) {
            input.addObject().put("role", "user")
                    .putArray("content").addObject().put("type", "input_text").put("text", "Proceed.");
        }
        if (!instructions.isEmpty()) {
            body.put("instructions", instructions.toString());
        }
        // The Codex backend rejects `max_output_tokens` for OAuth callers, so rely on the server default.
        body.put("store", false);
        body.put("stream", true);

        if (request.wantsStructuredOutput()) {
            ObjectNode format = body.putObject("text").putObject("format");
            format.put("type", "json_schema");
            format.put("name", request.schema().name());
            format.put("strict", true);
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

    // --- SSE response parsing ------------------------------------------------------------------

    /**
     * Reconstruct the completion from the Codex SSE stream: accumulate {@code response.output_text.delta}
     * events and finalize on {@code response.completed}. A terminal failure event ({@code response.failed
     * / incomplete / cancelled / error}) or a top-level {@code error} payload becomes a permanent
     * {@link LlmException}; a stream that ends before {@code response.completed} is surfaced explicitly.
     */
    private Completed parseSse(String body) {
        StringBuilder outputText = new StringBuilder();
        JsonNode completed = null;
        String currentEvent = null;
        StringBuilder data = new StringBuilder();

        for (String raw : body.split("\n", -1)) {
            // SSE streams may use CRLF; treat a trailing CR as part of the line terminator so a blank
            // line (event boundary) is recognized and data payloads don't carry a stray '\r'.
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isEmpty()) {
                JsonNode done = flushEvent(currentEvent, data, outputText);
                if (done != null) {
                    completed = done;
                }
                currentEvent = null;
                continue;
            }
            if (line.startsWith("event:")) {
                currentEvent = line.substring("event:".length()).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(stripLeading(line.substring("data:".length())));
            }
        }
        JsonNode done = flushEvent(currentEvent, data, outputText);
        if (done != null) {
            completed = done;
        }

        if (completed == null) {
            throw LlmException.permanent(
                    "openai-oauth stream closed before response.completed.", null);
        }
        return new Completed(completed, outputText.toString());
    }

    /** Parse one buffered SSE event; returns the completed-response node when this was the terminal event. */
    private JsonNode flushEvent(String event, StringBuilder data, StringBuilder outputText) {
        if (data.length() == 0) {
            return null;
        }
        String payload = data.toString().trim();
        data.setLength(0);
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return null;
        }

        JsonNode value;
        try {
            value = client.parse(payload);
        } catch (LlmException e) {
            // A stream that ended mid-`data:` JSON closes before the final brace — surface that clearly
            // instead of a generic parse error.
            throw LlmException.permanent(
                    "openai-oauth stream truncated before the final event closed (incomplete JSON).", e);
        }

        if (value.get("error") != null) {
            throw new LlmException("openai-oauth upstream error: " + truncate(payload),
                    null, false, SSE_TERMINAL_ERROR_STATUS);
        }
        String kind = textOr(value, "type", event == null ? "" : event);
        switch (kind) {
            case "response.output_text.delta" -> {
                String delta = text(value, "delta");
                if (delta != null) {
                    outputText.append(delta);
                }
            }
            case "response.completed" -> {
                JsonNode response = value.get("response");
                return response != null ? response : value;
            }
            case "response.failed", "response.incomplete", "response.cancelled", "error" ->
                throw new LlmException("openai-oauth upstream " + kind + ": " + truncate(payload),
                        null, false, SSE_TERMINAL_ERROR_STATUS);
            default -> { /* ignore other lifecycle events (created, in_progress, …) */ }
        }
        return null;
    }

    private void validateJson(String reply, JsonSchema schema) {
        try {
            client.parse(reply);
        } catch (LlmException e) {
            throw LlmException.permanent(
                    "Structured reply for schema '" + schema.name() + "' was not valid JSON: "
                            + e.getMessage(), e);
        }
    }

    // --- JWT account-id extraction -------------------------------------------------------------

    /**
     * Best-effort decode of the ChatGPT account id from a JWT's claims (display/header use only — no
     * signature verification): {@code chatgpt_account_id}, the namespaced
     * {@code https://api.openai.com/auth.chatgpt_account_id}, or {@code organizations[0].id}.
     */
    private Optional<String> accountIdFromJwt(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return Optional.empty();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode claims = client.parse(new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
            String direct = text(claims, "chatgpt_account_id");
            if (direct != null) {
                return Optional.of(direct);
            }
            JsonNode auth = claims.get("https://api.openai.com/auth");
            if (auth != null) {
                String nested = text(auth, "chatgpt_account_id");
                if (nested != null) {
                    return Optional.of(nested);
                }
            }
            JsonNode orgs = claims.get("organizations");
            if (orgs != null && orgs.isArray() && !orgs.isEmpty()) {
                String orgId = text(orgs.get(0), "id");
                if (orgId != null) {
                    return Optional.of(orgId);
                }
            }
        } catch (RuntimeException ignored) {
            // Opaque/non-JWT token or unexpected claims — no account id to extract.
        }
        return Optional.empty();
    }

    // --- helpers -------------------------------------------------------------------------------

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v != null && v.isString() ? v.stringValue() : null;
    }

    private static String textOr(JsonNode node, String field, String fallback) {
        String t = text(node, field);
        return t == null || t.isBlank() ? fallback : t;
    }

    private static long positiveLongOr(JsonNode node, String field, long fallback) {
        JsonNode v = node == null ? null : node.get(field);
        if (v != null && v.isIntegralNumber()) {
            long n = v.longValue();
            if (n > 0) {
                return n;
            }
        }
        return fallback;
    }

    private static String stripLeading(String s) {
        return s.startsWith(" ") ? s.substring(1) : s;
    }

    private static String truncate(String s) {
        String t = s.strip();
        return t.length() > 1024 ? t.substring(0, 1024) + "…(truncated)" : t;
    }

    /** The reconstructed Codex response: the completed-response node plus any streamed output text. */
    private record Completed(JsonNode response, String streamedText) {

        String outputText() {
            String direct = text(response, "output_text");
            if (direct != null && !direct.isBlank()) {
                return direct;
            }
            if (streamedText != null && !streamedText.isBlank()) {
                return streamedText;
            }
            JsonNode output = response.get("output");
            if (output != null && output.isArray()) {
                for (JsonNode item : output) {
                    JsonNode content = item.get("content");
                    if (content != null && content.isArray()) {
                        for (JsonNode c : content) {
                            String t = text(c, "text");
                            if (t != null && !t.isBlank()) {
                                return t;
                            }
                        }
                    }
                }
            }
            return null;
        }

        String modelOr(String fallback) {
            String mdl = text(response, "model");
            return mdl == null || mdl.isBlank() ? fallback : mdl;
        }

        int inputTokens() {
            return usage("input_tokens");
        }

        int outputTokens() {
            return usage("output_tokens");
        }

        private int usage(String field) {
            JsonNode usage = response.get("usage");
            JsonNode v = usage == null ? null : usage.get(field);
            return v != null && v.isIntegralNumber() ? v.intValue() : -1;
        }
    }
}
