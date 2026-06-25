package com.agentmemory.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * End-to-end MCP integration for the issue #25 {@code memory_forget_sweep} tool over the real
 * Streamable-HTTP transport: a real MCP client connects to {@code /mcp}, sees the tool advertised with
 * a destructive hint, then drives the sweep <em>through the tool</em> (client → transport → handler →
 * {@code ForgetSweepService} → DB) — first {@code dry_run=true} (preview, nothing changes), then a live
 * run (the cold page is reported soft-deleted and the row reflects it). This proves the seam is wired,
 * not dormant. A cold page is seeded with raw SQL so its age/access are controlled; runs against a
 * throwaway {@code pgvector/pgvector:pg16} (Testcontainers) with the wiki on a temp dir, the offline
 * {@code test} provider booting past the DD-005 gate, and the external-edit watcher disabled.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false",
            "agent-memory.decay.cold-threshold=0.05"
        })
@Testcontainers
class McpSweepEndpointTest {

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

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant LONG_AGO = Instant.now().minus(800, ChronoUnit.DAYS);

    @LocalServerPort int port;
    @Autowired javax.sql.DataSource dataSource;

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

    /** Seed a project plus one cold (very old, never-accessed episodic) latest page; return [ws, proj]. */
    private String[] seedColdPage(String path) {
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        String proj = "proj";
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        jdbc().update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, proj);
        jdbc().update(
                "INSERT INTO pages (id, workspace_id, project_id, workspace, project, path, title, body, "
                        + "is_latest, layer, access_count, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'proj', ?, 't', 'body', true, 'episodic', 0, ?, ?)",
                UUID.randomUUID(), wsId, projId, ws, path,
                java.sql.Timestamp.from(LONG_AGO), java.sql.Timestamp.from(LONG_AGO));
        return new String[] {ws, proj};
    }

    private boolean isLatest(String ws, String path) {
        Boolean v = jdbc().queryForObject(
                "SELECT is_latest FROM pages WHERE workspace = ? AND path = ?", Boolean.class, ws, path);
        return Boolean.TRUE.equals(v);
    }

    private CallToolResult call(String tool, Map<String, Object> args) {
        return client.callTool(new CallToolRequest(tool, args));
    }

    private static JsonNode json(CallToolResult result) {
        assertThat(result.isError()).as("tool returned error: %s", result.content()).isNotEqualTo(true);
        assertThat(result.content()).isNotEmpty();
        return JSON.readTree(((TextContent) result.content().get(0)).text());
    }

    // --- advertisement -----------------------------------------------------------------------------

    @Test
    void advertisesSweepToolWithDestructiveHint() {
        List<Tool> tools = client.listTools().tools();
        assertThat(tools).extracting(Tool::name).contains("memory_forget_sweep");

        Tool sweep = tools.stream().filter(t -> t.name().equals("memory_forget_sweep"))
                .findFirst().orElseThrow();
        assertThat(sweep.annotations().readOnlyHint()).isFalse();
        assertThat(sweep.annotations().destructiveHint()).isTrue();
        // The dry_run knob is on the input schema (a raw JSON-Schema map: type/object → properties).
        @SuppressWarnings("unchecked")
        Map<String, Object> schemaProps =
                (Map<String, Object>) sweep.inputSchema().get("properties");
        assertThat(schemaProps).containsKey("dry_run");
    }

    // --- dry run then live, through the tool -------------------------------------------------------

    @Test
    void dryRunPreviewsThenLiveRunSoftDeletes() {
        String[] s = seedColdPage("sessions/cold.md");

        // dry_run=true: the cold page is previewed but nothing changes.
        JsonNode preview = json(call("memory_forget_sweep", Map.of(
                "dry_run", true, "workspace", s[0], "project", s[1])));
        assertThat(preview.get("dryRun").asBoolean()).isTrue();
        assertThat(preview.get("softDeletedCount").asInt()).isEqualTo(1);
        assertThat(pathsOf(preview.get("softDeleted"))).contains("sessions/cold.md");
        assertThat(isLatest(s[0], "sessions/cold.md")).isTrue(); // untouched

        // Live run (default dry_run=false): the page is actually soft-deleted.
        JsonNode applied = json(call("memory_forget_sweep", Map.of(
                "workspace", s[0], "project", s[1])));
        assertThat(applied.get("dryRun").asBoolean()).isFalse();
        assertThat(applied.get("softDeletedCount").asInt()).isEqualTo(1);
        assertThat(pathsOf(applied.get("softDeleted"))).contains("sessions/cold.md");
        assertThat(isLatest(s[0], "sessions/cold.md")).isFalse(); // dropped from latest

        // A second live run has nothing left to do — idempotent on an already-swept store.
        JsonNode again = json(call("memory_forget_sweep", Map.of(
                "workspace", s[0], "project", s[1])));
        assertThat(again.get("softDeletedCount").asInt()).isZero();
    }

    private static List<String> pathsOf(JsonNode arr) {
        return arr.valueStream().map(n -> n.get("path").asString()).toList();
    }
}
