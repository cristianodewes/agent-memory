package com.agentmemory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.llm.ProviderFactory;
import com.agentmemory.llm.TestDoubleProvider;
import com.agentmemory.recall.Scope;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end coverage of "chat with your memory" (issue #37) over the real {@code RANDOM_PORT} server +
 * a throwaway {@code pgvector/pgvector:pg16}. Proves the acceptance criteria through the live SSE path:
 * a posted question retrieves a seeded page, the answer streams as {@code sources}/{@code delta}/
 * {@code done} events, the citations resolve to the real page, and — the read-only guardrail — no page
 * is created by the chat turn.
 *
 * <p>LLM-assisted recall is disabled ({@code agent-memory.recall.llm.enabled=false}) so recall is the
 * deterministic base RRF and the only LLM call is the chat generation, which a {@code @Primary}
 * {@link ProviderFactory} scripts to a fixed grounded answer. Auth is left disabled (the default
 * loopback mode); {@link com.agentmemory.web.ChatAuthTest} covers the enabled path.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false",
            "agent-memory.recall.llm.enabled=false"
        })
@Testcontainers
class ChatEndpointTest {

    /** The deterministic grounded answer the scripted chat LLM returns (plain ASCII, no JSON escapes). */
    private static final String ANSWER =
            "Per the project memory the recall pipeline fuses full text search and the link graph [1].";

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

    /** The chat LLM returns the fixed grounded answer for the free-form (non-structured) chat request. */
    @TestConfiguration
    static class GoldenLlmConfig {
        @Bean
        @Primary
        ProviderFactory goldenProviderFactory() {
            return new ProviderFactory(TestDoubleProvider.builder()
                    .chatResponder(req -> ANSWER)
                    .build());
        }
    }

    @LocalServerPort int port;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Test
    void postingAQuestionStreamsAGroundedAnswerWithCitations() throws Exception {
        Scope s = freshScope();
        seedPage(s, "notes/recall.md", "Recall design",
                "the recall pipeline fuses full text search and the link graph neighborhood");
        long pagesBefore = latestPageCount(s);

        Resp r = postChat(s, "{\"message\":\"recall pipeline full text graph\"}");

        assertThat(r.status).isEqualTo(200);
        // The sources event streams first and resolves to the real seeded page.
        assertThat(r.body).contains("sources").contains("notes/recall.md");
        // The delta events reconstruct exactly the model's answer.
        assertThat(reconstructDeltas(r.body)).isEqualTo(ANSWER);
        // Terminal done event present.
        assertThat(r.body).contains("done");
        // Read-only guardrail: the chat turn created no page.
        assertThat(latestPageCount(s)).isEqualTo(pagesBefore);
    }

    @Test
    void aBlankMessageIsRejected() throws Exception {
        Scope s = freshScope();
        Resp r = postChat(s, "{\"message\":\"   \"}");
        assertThat(r.status).isEqualTo(400);
    }

    // --- HTTP + SSE helpers ------------------------------------------------------------------------

    private record Resp(int status, String body) {}

    private Resp postChat(Scope s, String jsonBody) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        String path = "/api/v1/workspaces/" + s.workspaceSlug() + "/projects/" + s.projectSlug() + "/chat";
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return new Resp(resp.statusCode(), resp.body());
    }

    /** Concatenate the {@code "text":"…"} fields of the delta events, in stream order. */
    private static String reconstructDeltas(String sseBody) {
        Matcher m = Pattern.compile("\"text\":\"([^\"]*)\"").matcher(sseBody);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            sb.append(m.group(1));
        }
        return sb.toString();
    }

    // --- seeding (mirrors LlmRecallIntegrationTest) ------------------------------------------------

    private Scope freshScope() {
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        jdbc().update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, "proj");
        return Scope.of(ws, "proj");
    }

    private void seedPage(Scope s, String path, String title, String body) {
        UUID wsId = jdbc().queryForObject(
                "SELECT id FROM workspaces WHERE slug = ?", UUID.class, s.workspaceSlug());
        UUID projId = jdbc().queryForObject(
                "SELECT id FROM projects WHERE workspace = ? AND slug = ?",
                UUID.class, s.workspaceSlug(), s.projectSlug());
        jdbc().update(
                "INSERT INTO pages (id, workspace_id, project_id, workspace, project, path, title, "
                        + "body, is_latest, access_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, 0)",
                UUID.randomUUID(), wsId, projId, s.workspaceSlug(), s.projectSlug(), path, title, body);
    }

    private long latestPageCount(Scope s) {
        return jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ? AND is_latest = true",
                Long.class, s.workspaceSlug(), s.projectSlug());
    }
}
