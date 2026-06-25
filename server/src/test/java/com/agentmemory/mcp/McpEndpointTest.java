package com.agentmemory.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end MCP integration over the real Streamable-HTTP transport (issue #17 acceptance): a real
 * MCP client connects to {@code /mcp}, lists the five read tools, and calls each one against a
 * seeded corpus on a throwaway {@code pgvector/pgvector:pg16} (Testcontainers). Also covers
 * scope resolution — explicit {@code workspace}/{@code project} vs. the most-recent-activity default
 * (DD-003). The offline {@code test} LLM provider boots the context (DD-005 gate).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test"
        })
@Testcontainers
class McpEndpointTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort int port;
    @Autowired DataSource dataSource;

    private McpSyncClient client;

    @BeforeEach
    void connect() {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .endpoint("/mcp")
                .build();
        client = McpClient.sync(transport).build();
        client.initialize();
    }

    @AfterEach
    void disconnect() {
        if (client != null) {
            client.closeGracefully();
        }
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    // --- seeding -----------------------------------------------------------------------------------

    /** Seed a workspace/project with a page, a session+observation; returns the (ws, proj) slugs. */
    private String[] seedProject(String pageTitle, String pageBody, String obsPayload) {
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        String proj = "proj";
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        JdbcTemplate j = jdbc();
        j.update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        j.update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, proj);
        j.update("INSERT INTO pages (id, workspace_id, project_id, workspace, project, path, title, "
                        + "body, is_latest, access_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, 0)",
                UUID.randomUUID(), wsId, projId, ws, proj, "concepts/recall.md", pageTitle, pageBody);
        UUID sessionId = UUID.randomUUID();
        j.update("INSERT INTO sessions (id, workspace_id, project_id, workspace, project, started_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                sessionId, wsId, projId, ws, proj, java.sql.Timestamp.from(Instant.now()));
        j.update("INSERT INTO observations (id, session_id, workspace_id, project_id, workspace, "
                        + "project, kind, payload, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), sessionId, wsId, projId, ws, proj, "user-prompt", obsPayload,
                java.sql.Timestamp.from(Instant.now()));
        return new String[] {ws, proj};
    }

    private static String text(CallToolResult result) {
        assertThat(result.isError()).as("tool returned error: %s", result.content()).isNotEqualTo(true);
        return textRaw(result);
    }

    /** Extract the first text block regardless of the error flag (for asserting error messages). */
    private static String textRaw(CallToolResult result) {
        assertThat(result.content()).isNotEmpty();
        return ((TextContent) result.content().get(0)).text();
    }

    private static JsonNode json(CallToolResult result) {
        return JSON.readTree(text(result));
    }

    private CallToolResult call(String tool, Map<String, Object> args) {
        return client.callTool(new CallToolRequest(tool, args));
    }

    // --- list tools --------------------------------------------------------------------------------

    @Test
    void listsTheFiveReadOnlyTools() {
        List<Tool> tools = client.listTools().tools();
        List<String> readTools = List.of(
                "memory_query", "memory_recent", "memory_read_page", "memory_status", "memory_briefing");
        assertThat(tools).extracting(Tool::name).containsAll(readTools);
        // each of the five read tools is flagged read-only (write tools, #20, are not — covered by
        // McpWriteEndpointTest).
        assertThat(tools).filteredOn(t -> readTools.contains(t.name()))
                .allSatisfy(t -> assertThat(t.annotations().readOnlyHint()).isTrue());
    }

    // --- memory_query ------------------------------------------------------------------------------

    @Test
    void memoryQueryReturnsHybridHits() {
        String[] s = seedProject("Hybrid recall",
                "Reciprocal rank fusion blends full-text and link-graph signals.", "irrelevant");
        JsonNode r = json(call("memory_query", Map.of(
                "query", "reciprocal rank fusion", "workspace", s[0], "project", s[1])));

        assertThat(r.get("rawFallback").asBoolean()).isFalse();
        assertThat(r.get("scope").get("project").asString()).isEqualTo("proj");
        assertThat(r.get("hits")).isNotEmpty();
        JsonNode top = r.get("hits").get(0);
        assertThat(top.get("path").asString()).isEqualTo("concepts/recall.md");
        assertThat(top.get("source").asString()).isEqualTo("PAGE");
        assertThat(top.get("snippet").asString()).contains("<mark>");
        assertThat(top.get("rank").asInt()).isEqualTo(1);
    }

    // --- memory_read_page --------------------------------------------------------------------------

    @Test
    void memoryReadPageReturnsFullBodyByPath() {
        String[] s = seedProject("Recall", "the full body of the recall page", "x");
        JsonNode r = json(call("memory_read_page", Map.of(
                "path", "concepts/recall.md", "workspace", s[0], "project", s[1])));
        assertThat(r.get("title").asString()).isEqualTo("Recall");
        assertThat(r.get("body").asString()).isEqualTo("the full body of the recall page");
        assertThat(r.get("latest").asBoolean()).isTrue();
    }

    @Test
    void memoryReadPageFallsBackToTopHitWhenPathOmitted() {
        String[] s = seedProject("Storage decision",
                "we chose postgres with pgvector for the index", "x");
        JsonNode r = json(call("memory_read_page", Map.of(
                "query", "postgres pgvector", "workspace", s[0], "project", s[1])));
        assertThat(r.get("path").asString()).isEqualTo("concepts/recall.md");
        assertThat(r.get("matchedByQuery").asBoolean()).isTrue();
        assertThat(r.get("body").asString()).contains("pgvector");
    }

    @Test
    void memoryReadPageErrorsForUnknownPath() {
        String[] s = seedProject("t", "b", "x");
        CallToolResult result = call("memory_read_page", Map.of(
                "path", "nope/missing.md", "workspace", s[0], "project", s[1]));
        assertThat(result.isError()).isTrue();
    }

    // --- memory_recent -----------------------------------------------------------------------------

    @Test
    void memoryRecentReturnsLatestPages() {
        String[] s = seedProject("Recall", "body", "x");
        JsonNode r = json(call("memory_recent", Map.of("workspace", s[0], "project", s[1])));
        assertThat(r.get("count").asInt()).isEqualTo(1);
        assertThat(r.get("pages").get(0).get("path").asString()).isEqualTo("concepts/recall.md");
        // recent is metadata only — no body field
        assertThat(r.get("pages").get(0).has("body")).isFalse();
    }

    // --- memory_status -----------------------------------------------------------------------------

    @Test
    void memoryStatusReturnsLifetimeCounts() {
        String[] s = seedProject("t", "b", "x");
        JsonNode r = json(call("memory_status", Map.of("workspace", s[0], "project", s[1])));
        assertThat(r.get("pages").asLong()).isEqualTo(1);
        assertThat(r.get("observations").asLong()).isEqualTo(1);
        assertThat(r.get("sessions").asLong()).isEqualTo(1);
        assertThat(r.get("links").asLong()).isEqualTo(0);
    }

    // --- memory_briefing ---------------------------------------------------------------------------

    @Test
    void memoryBriefingReturnsStructuredSnapshotNoLlm() {
        String[] s = seedProject("Recall", "body", "investigate the widget");
        JsonNode r = json(call("memory_briefing", Map.of("workspace", s[0], "project", s[1])));
        assertThat(r.get("pages").asLong()).isEqualTo(1);
        assertThat(r.get("observationsLast7Days").asLong()).isEqualTo(1);
        assertThat(r.get("observationsLast30Days").asLong()).isEqualTo(1);
        assertThat(r.get("rules").isArray()).isTrue();
        assertThat(r.get("slots").isArray()).isTrue();
        assertThat(r.get("recent")).isNotEmpty();
    }

    // --- scope resolution --------------------------------------------------------------------------

    @Test
    void scopeDefaultsToMostRecentlyActiveProjectWhenUnspecified() {
        // Two projects; the SECOND seeded has the newer observation/session, so an unscoped call
        // resolves to it (DD-003: most recent hook activity).
        seedProject("Older", "older page", "older activity");
        String[] newer = seedProject("Newer", "newer page", "newer activity");

        JsonNode r = json(call("memory_status", Map.of())); // no workspace/project
        assertThat(r.get("scope").get("workspace").asString()).isEqualTo(newer[0]);
        assertThat(r.get("scope").get("project").asString()).isEqualTo("proj");
    }

    @Test
    void explicitScopeOverridesTheDefault() {
        String[] target = seedProject("Target", "target page", "target activity");
        seedProject("Other", "other page", "newer other activity"); // newer, but we override

        JsonNode r = json(call("memory_status", Map.of(
                "workspace", target[0], "project", target[1])));
        assertThat(r.get("scope").get("workspace").asString()).isEqualTo(target[0]);
    }

    @Test
    void halfSpecifiedScopeIsAClearToolError() {
        CallToolResult result = call("memory_status", Map.of("workspace", "only-ws"));
        assertThat(result.isError()).isTrue();
        assertThat(textRaw(result)).containsIgnoringCase("scope");
    }
}
