package com.agentmemory.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the 429 reset-hint extraction in {@link HttpJsonClient} (issue #148): the
 * {@code Retry-After} header and the ChatGPT {@code error.resets_in_seconds} body field must land on
 * {@link LlmException#retryAfterMillis()} so the rate-limit gate can pause background work until the
 * quota window actually reopens. Driven against the loopback {@link StubHttpServer} — no real network.
 */
class HttpJsonClientRateLimitTest {

    private static final String USAGE_LIMIT_BODY =
            "{\"error\":{\"type\":\"usage_limit_reached\",\"message\":\"The usage limit has been "
                    + "reached\",\"plan_type\":\"plus\",\"resets_at\":1783100492,"
                    + "\"resets_in_seconds\":13992}}";

    private static LlmException callAndCatch(StubHttpServer stub) {
        HttpJsonClient client = new HttpJsonClient(Duration.ofSeconds(2), Duration.ofSeconds(2));
        Throwable thrown = catchThrowable(() ->
                client.postJson(URI.create(stub.baseUrl() + "/v1/responses"),
                        Map.of(), Map.of("input", "probe"), "stub"));
        assertThat(thrown).isInstanceOf(LlmException.class);
        return (LlmException) thrown;
    }

    @Test
    void retryAfterHeaderBecomesTheResetHint() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(429, "{}", Map.of("Retry-After", "120"))) {
            LlmException e = callAndCatch(stub);

            assertThat(e.httpStatus()).isEqualTo(429);
            assertThat(e.isRetryable()).isTrue();
            assertThat(e.retryAfterMillis()).isEqualTo(120_000);
        }
    }

    @Test
    void chatGptUsageLimitBodyBecomesTheResetHint() throws Exception {
        // The exact production body shape from the openai-oauth provider (plan_type plus).
        try (StubHttpServer stub = StubHttpServer.start(429, USAGE_LIMIT_BODY)) {
            LlmException e = callAndCatch(stub);

            assertThat(e.retryAfterMillis()).isEqualTo(13_992_000);
        }
    }

    @Test
    void theHeaderWinsOverTheBody() throws Exception {
        try (StubHttpServer stub =
                StubHttpServer.start(429, USAGE_LIMIT_BODY, Map.of("Retry-After", "60"))) {
            LlmException e = callAndCatch(stub);

            assertThat(e.retryAfterMillis()).isEqualTo(60_000);
        }
    }

    @Test
    void aHintless429StaysRetryableWithNoHint() throws Exception {
        try (StubHttpServer stub = StubHttpServer.start(429, "not json at all")) {
            LlmException e = callAndCatch(stub);

            assertThat(e.httpStatus()).isEqualTo(429);
            assertThat(e.isRetryable()).isTrue();
            assertThat(e.retryAfterMillis()).isZero();
        }
    }

    @Test
    void anAbsurdRetryAfterIsTreatedAsNoHintInsteadOfOverflowing() throws Exception {
        // Long.MAX_VALUE seconds would wrap negative under a naive * 1000; the parse must degrade to
        // "no hint" (the body here carries none either), never a wrapped/negative pause.
        try (StubHttpServer stub =
                StubHttpServer.start(429, "{}", Map.of("Retry-After", "9223372036854775807"))) {
            LlmException e = callAndCatch(stub);

            assertThat(e.retryAfterMillis()).isZero();
        }
    }

    @Test
    void non429FailuresCarryNoHint() throws Exception {
        // Even with a Retry-After header present, only a 429 is a quota signal.
        try (StubHttpServer stub =
                StubHttpServer.start(500, USAGE_LIMIT_BODY, Map.of("Retry-After", "60"))) {
            LlmException e = callAndCatch(stub);

            assertThat(e.httpStatus()).isEqualTo(500);
            assertThat(e.retryAfterMillis()).isZero();
        }
    }
}
