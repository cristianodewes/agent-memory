package com.agentmemory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.llm.ProviderFactory;
import com.agentmemory.llm.TestDoubleProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the chat endpoint inherits the issue #38 auth chain when {@code agent-memory.auth.enabled=true}
 * (the team-lead's integration requirement: "do not leave the chat route open when enabled"). Because
 * the security chain is deny-by-default ({@code anyRequest().hasRole}), the new
 * {@code /api/v1/.../chat} route requires the bearer token automatically — no security-config change.
 * This test locks that in: a tokenless POST is {@code 401}, a wrong token is {@code 401}, and a valid
 * bearer POST reaches the handler and streams ({@code 200}). The complementary auth-disabled streaming
 * path is covered by {@link ChatEndpointTest}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false",
            "agent-memory.recall.llm.enabled=false",
            "agent-memory.auth.enabled=true",
            "agent-memory.auth.token=s3cr3t-test-token"
        })
@Testcontainers
class ChatAuthTest {

    private static final String TOKEN = "s3cr3t-test-token";
    private static final String CHAT_PATH = "/api/v1/workspaces/acme/projects/proj/chat";
    private static final String BODY = "{\"message\":\"how does recall work\"}";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("agent-memory.data.dir", () -> dataDir.toString());
    }

    @TestConfiguration
    static class GoldenLlmConfig {
        @Bean
        @Primary
        ProviderFactory goldenProviderFactory() {
            return new ProviderFactory(TestDoubleProvider.builder()
                    .chatResponder(req -> "A grounded answer.")
                    .build());
        }
    }

    @LocalServerPort int port;

    @Test
    void chatRejectsMissingToken() throws Exception {
        assertThat(post(BODY, null).statusCode()).isEqualTo(401);
    }

    @Test
    void chatRejectsWrongToken() throws Exception {
        assertThat(post(BODY, "Bearer nope").statusCode()).isEqualTo(401);
    }

    @Test
    void chatAcceptsValidBearerTokenAndStreams() throws Exception {
        // A Bearer (programmatic) POST is not subject to the browser-write guard; it authenticates and
        // reaches the handler, which streams the answer (200) — proving the route is wired AND protected.
        HttpResponse<String> r = post(BODY, "Bearer " + TOKEN);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("done");
    }

    private HttpResponse<String> post(String body, String authorization) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + CHAT_PATH))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
