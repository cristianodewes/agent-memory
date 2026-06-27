package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the {@code openai-oauth} (ChatGPT/Codex) provider (issue #113) against loopback stubs (no
 * external network): a separate token-endpoint stub mints the access token and a Codex stub serves the
 * SSE Responses stream. It proves the bearer + {@code chatgpt-account-id} headers, the Responses-API
 * request shape (instructions / input / json_schema text.format), the SSE reconstruction of the
 * completion, transparent refresh of an expired token (with the rotated credential written back to the
 * token file), and fail-fast on a rejected credential / missing token file.
 */
class OpenAiOAuthLlmProviderTest {

    private static final JsonSchema OK_SCHEMA = new JsonSchema("t",
            "{\"type\":\"object\",\"properties\":{\"ok\":{\"type\":\"boolean\"}},"
                    + "\"required\":[\"ok\"],\"additionalProperties\":false}");

    /** A Codex SSE stream that streams `{"ok":true}` as two deltas then completes with usage + model. */
    private static final String OK_SSE = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"{\\"ok\\""}

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":":true}"}

            event: response.completed
            data: {"type":"response.completed","response":{"model":"gpt-5.5","usage":{"input_tokens":7,"output_tokens":2},"output":[]}}

            data: [DONE]

            """;

    private static HttpJsonClient client() {
        return new HttpJsonClient(Duration.ofSeconds(5), Duration.ofSeconds(60));
    }

    private static Path tokenFile(Path dir, String access, String refresh, long expiresAtMs, String accountId)
            throws Exception {
        Path file = dir.resolve("auth.json");
        String acct = accountId == null ? "" : ",\"accountId\":\"" + accountId + "\"";
        Files.writeString(file, "{\"openai\":{\"type\":\"oauth\",\"access\":\"" + access + "\",\"refresh\":\""
                + refresh + "\",\"expires\":" + expiresAtMs + acct + "}}");
        return file;
    }

    private static OpenAiOAuthLlmProvider provider(Path tokenFile, URI tokenUri, URI responsesUri, long nowMs) {
        LongSupplier clock = () -> nowMs;
        return new OpenAiOAuthLlmProvider("gpt-5.5", new OpenAiOAuthTokenStore(tokenFile), client(),
                tokenUri, responsesUri, OpenAiOAuthLlmProvider.CODEX_CLIENT_ID, clock);
    }

    // --- happy path: cached token, SSE reconstruction, headers, request shape -------------------

    @Test
    void sendsCodexRequestWithAccountHeaderAndParsesSse(@TempDir Path tmp) throws Exception {
        // Token valid far into the future: no refresh, the Codex stub is hit directly.
        Path token = tokenFile(tmp, "tokA", "rt", 9_999_999_999_999L, "acct-1");
        try (StubHttpServer codex = StubHttpServer.start(200, OK_SSE)) {
            OpenAiOAuthLlmProvider provider =
                    provider(token, URI.create("http://unused.invalid/token"), URI.create(codex.baseUrl()), 1_000L);

            ChatResponse response = provider.chat(ChatRequest.structured(
                    List.of(ChatMessage.system("be terse"), ChatMessage.user("ping")), OK_SCHEMA));

            assertThat(response.text()).isEqualTo("{\"ok\":true}");
            assertThat(response.provider()).isEqualTo("openai-oauth");
            assertThat(response.model()).isEqualTo("gpt-5.5");
            assertThat(response.inputTokens()).isEqualTo(7);
            assertThat(response.outputTokens()).isEqualTo(2);

            // Codex auth + account + beta headers.
            assertThat(codex.lastRequestHeader("Authorization")).isEqualTo("Bearer tokA");
            assertThat(codex.lastRequestHeader("chatgpt-account-id")).isEqualTo("acct-1");
            assertThat(codex.lastRequestHeader("openai-beta")).isEqualTo("responses=experimental");
            assertThat(codex.lastRequestHeader("originator")).isEqualTo("codex_cli_rs");

            // Responses-API request shape: system hoisted to instructions, input_text content, json_schema.
            String body = codex.lastRequestBody();
            assertThat(body).contains("\"instructions\":\"be terse\"");
            assertThat(body).contains("\"type\":\"input_text\"").contains("\"stream\":true");
            assertThat(body).contains("\"format\":{\"type\":\"json_schema\"").contains("\"strict\":true");
            assertThat(body).doesNotContain("max_output_tokens");
        }
    }

    // --- reasoning-effort hint + per-call timeout (issue #130) ----------------------------------

    @Test
    void emitsMinimalReasoningAndLowVerbosityWhenTheRecallHintIsSet(@TempDir Path tmp) throws Exception {
        Path token = tokenFile(tmp, "tokA", "rt", 9_999_999_999_999L, "acct-1");
        try (StubHttpServer codex = StubHttpServer.start(200, OK_SSE)) {
            OpenAiOAuthLlmProvider provider =
                    provider(token, URI.create("http://unused.invalid/token"), URI.create(codex.baseUrl()), 1_000L);

            // A structured recall call carrying the MINIMAL reasoning hint (as QueryExpander/CandidateReranker set).
            ChatResponse response = provider.chat(ChatRequest.structured(
                            List.of(ChatMessage.user("rank these")), OK_SCHEMA)
                    .withReasoningEffort(ReasoningEffort.MINIMAL));

            assertThat(response.text()).isEqualTo("{\"ok\":true}");
            String body = codex.lastRequestBody();
            // reasoning.effort=minimal at top level, and text.verbosity=low on the structured text node.
            assertThat(body).contains("\"reasoning\":{\"effort\":\"minimal\"}");
            assertThat(body).contains("\"verbosity\":\"low\"");
        }
    }

    @Test
    void omitsReasoningObjectOnAPlainChatCall(@TempDir Path tmp) throws Exception {
        // The main chat path leaves the hint null (issue #130 invariant): no reasoning/verbosity emitted.
        Path token = tokenFile(tmp, "tokA", "rt", 9_999_999_999_999L, "acct-1");
        try (StubHttpServer codex = StubHttpServer.start(200, OK_SSE)) {
            OpenAiOAuthLlmProvider provider =
                    provider(token, URI.create("http://unused.invalid/token"), URI.create(codex.baseUrl()), 1_000L);

            provider.chat(ChatRequest.text(ChatMessage.ofUser("just chat")));

            String body = codex.lastRequestBody();
            assertThat(body).doesNotContain("reasoning");
            assertThat(body).doesNotContain("verbosity");
        }
    }

    @Test
    void shortPerCallTimeoutCancelsASlowRecallCall(@TempDir Path tmp) throws Exception {
        // A request carrying a short budget-derived timeout is cancelled at the HTTP layer when the
        // backend stalls — the real enforcement behind the recall budget (issue #130). A normal chat
        // call (no override) would keep the generous client default and not time out here.
        Path token = tokenFile(tmp, "tokA", "rt", 9_999_999_999_999L, "acct-1");
        try (StubHttpServer slow = StubHttpServer.start(200, req -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return OK_SSE;
        })) {
            OpenAiOAuthLlmProvider provider =
                    provider(token, URI.create("http://unused.invalid/token"), URI.create(slow.baseUrl()), 1_000L);

            assertThatThrownBy(() -> provider.chat(ChatRequest.structured(
                            List.of(ChatMessage.user("rank")), OK_SCHEMA)
                    .withRequestTimeout(Duration.ofMillis(100))))
                    .isInstanceOf(LlmException.class);
        }
    }

    @Test
    void defaultTimeoutIsPreservedWhenNoOverrideIsGiven(@TempDir Path tmp) throws Exception {
        // No per-call override: a brief backend delay well under the client default still succeeds,
        // proving heavy chat keeps its generous timeout (the recall axis is the only one shortened).
        Path token = tokenFile(tmp, "tokA", "rt", 9_999_999_999_999L, "acct-1");
        try (StubHttpServer slow = StubHttpServer.start(200, req -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return OK_SSE;
        })) {
            OpenAiOAuthLlmProvider provider =
                    provider(token, URI.create("http://unused.invalid/token"), URI.create(slow.baseUrl()), 1_000L);

            ChatResponse response = provider.chat(ChatRequest.text(ChatMessage.ofUser("chat")));

            assertThat(response.text()).isEqualTo("{\"ok\":true}");
        }
    }

    // --- transparent refresh of an expired token, with write-back -------------------------------

    @Test
    void refreshesExpiredTokenThenChatsAndPersistsRotation(@TempDir Path tmp) throws Exception {
        Path token = tokenFile(tmp, "old", "rt0", 0L, "acct-old"); // already expired
        String idToken = jwt("{\"chatgpt_account_id\":\"acct-new\"}");
        String tokenResponse = "{\"access_token\":\"new\",\"refresh_token\":\"rt1\",\"expires_in\":3600,"
                + "\"id_token\":\"" + idToken + "\"}";

        try (StubHttpServer tokenEndpoint = StubHttpServer.start(200, tokenResponse);
                StubHttpServer codex = StubHttpServer.start(200, OK_SSE)) {
            OpenAiOAuthLlmProvider provider =
                    provider(token, URI.create(tokenEndpoint.baseUrl()), URI.create(codex.baseUrl()), 1_000L);

            ChatResponse response = provider.chat(ChatRequest.text(ChatMessage.ofUser("hi")));

            assertThat(response.text()).isEqualTo("{\"ok\":true}");
            // The refresh used the Codex client id + refresh_token grant.
            assertThat(tokenEndpoint.lastRequestBody())
                    .contains("grant_type=refresh_token")
                    .contains("refresh_token=rt0")
                    .contains("client_id=" + OpenAiOAuthLlmProvider.CODEX_CLIENT_ID);
            // The chat used the freshly-minted access token + the account id from the id_token.
            assertThat(codex.lastRequestHeader("Authorization")).isEqualTo("Bearer new");
            assertThat(codex.lastRequestHeader("chatgpt-account-id")).isEqualTo("acct-new");

            // The rotated credential was written back to the token file.
            OpenAiOAuthTokenStore.Token persisted = new OpenAiOAuthTokenStore(token).load().orElseThrow();
            assertThat(persisted.access()).isEqualTo("new");
            assertThat(persisted.refresh()).isEqualTo("rt1");
            assertThat(persisted.accountId()).isEqualTo("acct-new");
        }
    }

    @Test
    void probeFailsFastWhenRefreshRejected(@TempDir Path tmp) throws Exception {
        Path token = tokenFile(tmp, "old", "rt0", 0L, null); // expired -> forces a refresh
        try (StubHttpServer tokenEndpoint = StubHttpServer.start(400, "{\"error\":\"invalid_grant\"}");
                StubHttpServer codex = StubHttpServer.start(200, OK_SSE)) {
            OpenAiOAuthLlmProvider provider =
                    provider(token, URI.create(tokenEndpoint.baseUrl()), URI.create(codex.baseUrl()), 1_000L);

            assertThatThrownBy(provider::probe)
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("token refresh failed")
                    .hasMessageContaining("auth login openai-oauth");
            assertThat(codex.lastRequestBody()).isNull(); // chat never attempted
        }
    }

    // --- SSE terminal / malformed handling ------------------------------------------------------

    @Test
    void terminalFailureEventSurfaces(@TempDir Path tmp) throws Exception {
        Path token = tokenFile(tmp, "tokA", "rt", 9_999_999_999_999L, null);
        String sse = "event: response.failed\n"
                + "data: {\"type\":\"response.failed\",\"error\":{\"message\":\"stream stopped\"}}\n\n";
        try (StubHttpServer codex = StubHttpServer.start(200, sse)) {
            OpenAiOAuthLlmProvider provider =
                    provider(token, URI.create("http://unused.invalid/token"), URI.create(codex.baseUrl()), 1_000L);

            assertThatThrownBy(() -> provider.chat(ChatRequest.text(ChatMessage.ofUser("x"))))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("stream stopped");
        }
    }

    @Test
    void parsesCrlfTerminatedStream(@TempDir Path tmp) throws Exception {
        // Real SSE often uses CRLF line endings; the parser must still find event boundaries + payloads.
        Path token = tokenFile(tmp, "tokA", "rt", 9_999_999_999_999L, null);
        String sse = "event: response.output_text.delta\r\n"
                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\r\n\r\n"
                + "event: response.completed\r\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"model\":\"gpt-5.5\",\"output\":[]}}\r\n\r\n"
                + "data: [DONE]\r\n\r\n";
        try (StubHttpServer codex = StubHttpServer.start(200, sse)) {
            OpenAiOAuthLlmProvider provider =
                    provider(token, URI.create("http://unused.invalid/token"), URI.create(codex.baseUrl()), 1_000L);

            ChatResponse response = provider.chat(ChatRequest.text(ChatMessage.ofUser("x")));

            assertThat(response.text()).isEqualTo("hello");
        }
    }

    @Test
    void streamWithoutCompletedFails(@TempDir Path tmp) throws Exception {
        Path token = tokenFile(tmp, "tokA", "rt", 9_999_999_999_999L, null);
        String sse = "event: response.output_text.delta\n"
                + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"
                + "data: [DONE]\n\n";
        try (StubHttpServer codex = StubHttpServer.start(200, sse)) {
            OpenAiOAuthLlmProvider provider =
                    provider(token, URI.create("http://unused.invalid/token"), URI.create(codex.baseUrl()), 1_000L);

            assertThatThrownBy(() -> provider.chat(ChatRequest.text(ChatMessage.ofUser("x"))))
                    .isInstanceOf(LlmException.class)
                    .hasMessageContaining("stream closed before response.completed");
        }
    }

    // --- construction-time fail-fast ------------------------------------------------------------

    @Test
    void missingTokenFileFailsFastAtConstruction(@TempDir Path tmp) {
        Path absent = tmp.resolve("nope.json");
        assertThatThrownBy(() -> new OpenAiOAuthLlmProvider("gpt-5.5", new OpenAiOAuthTokenStore(absent),
                client(), URI.create("http://unused.invalid/token"),
                URI.create("http://unused.invalid/responses"),
                OpenAiOAuthLlmProvider.CODEX_CLIENT_ID, () -> 1_000L))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("No openai-oauth token found")
                .hasMessageContaining("auth login openai-oauth");
    }

    /** Build a display-only JWT (header.payload.sig) carrying the given JSON payload (no signing). */
    private static String jwt(String payloadJson) {
        String header = base64Url("{\"alg\":\"none\"}");
        return header + "." + base64Url(payloadJson) + ".sig";
    }

    private static String base64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
