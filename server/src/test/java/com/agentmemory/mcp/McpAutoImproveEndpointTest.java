package com.agentmemory.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.autoimprove.JdbcPendingWriteRepository;
import com.agentmemory.autoimprove.ProposedWrite;
import com.agentmemory.recall.Scope;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.file.Path;
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
 * End-to-end MCP integration for the issue #30 {@code memory_auto_improve} tool over the real
 * Streamable-HTTP transport: a real client sees the tool advertised (non-read-only), reports on a project,
 * and approves/rejects a held proposal. Approve must apply through the normal write path (the page becomes
 * readable via {@code memory_read_page}); reject must leave memory untouched. Held proposals are seeded
 * directly through the repository (the review engine that produces them is deferred to #29/#19).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false"
        })
@Testcontainers
class McpAutoImproveEndpointTest {

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

    private String[] seedProject() {
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        String proj = "proj";
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        jdbc().update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, proj);
        return new String[] {ws, proj};
    }

    /** Seed a held ({@code proposed}) proposal, as the deferred engine eventually will. */
    private UUID seedProposal(String ws, String proj, String path, String title, String body) {
        return new JdbcPendingWriteRepository(jdbc()).propose(
                Scope.of(ws, proj), null,
                new ProposedWrite(path, title, body, "page.edit", "seeded for test"));
    }

    private CallToolResult call(String tool, Map<String, Object> args) {
        return client.callTool(new CallToolRequest(tool, args));
    }

    private static String text(CallToolResult result) {
        assertThat(result.isError()).as("tool returned error: %s", result.content()).isNotEqualTo(true);
        assertThat(result.content()).isNotEmpty();
        return ((TextContent) result.content().get(0)).text();
    }

    private static JsonNode json(CallToolResult result) {
        return JSON.readTree(text(result));
    }

    @Test
    void advertisesAutoImproveToolAsNonReadOnly() {
        List<Tool> tools = client.listTools().tools();
        assertThat(tools).extracting(Tool::name).contains("memory_auto_improve");

        Tool t = tools.stream().filter(x -> x.name().equals("memory_auto_improve"))
                .findFirst().orElseThrow();
        assertThat(t.annotations().readOnlyHint()).isFalse();
        assertThat(t.annotations().destructiveHint()).isFalse();
    }

    @Test
    void reportOnAnEmptyProjectIsEmpty() {
        String[] s = seedProject();
        JsonNode report = json(call("memory_auto_improve", Map.of(
                "action", "report", "workspace", s[0], "project", s[1])));
        assertThat(report.get("count").asInt()).isZero();
        assertThat(report.get("proposals")).isEmpty();
    }

    @Test
    void approveAppliesTheHeldProposalThroughTheWritePath() {
        String[] s = seedProject();
        UUID id = seedProposal(s[0], s[1], "concepts/improve.md", "Improved concept", "Better body.");

        JsonNode approved = json(call("memory_auto_improve", Map.of("action", "approve", "id", id.toString())));
        assertThat(approved.get("status").asString()).isEqualTo("applied");
        assertThat(approved.get("appliedAt").asString()).isNotBlank();

        // It went through the real write path: the page is now readable.
        JsonNode read = json(call("memory_read_page", Map.of(
                "path", "concepts/improve.md", "workspace", s[0], "project", s[1])));
        assertThat(read.get("title").asString()).isEqualTo("Improved concept");
        assertThat(read.get("body").asString()).contains("Better body.");

        // And the report reflects the applied status.
        JsonNode report = json(call("memory_auto_improve", Map.of(
                "action", "report", "workspace", s[0], "project", s[1])));
        assertThat(report.get("count").asInt()).isEqualTo(1);
        assertThat(report.get("proposals").get(0).get("status").asString()).isEqualTo("applied");
    }

    @Test
    void rejectLeavesMemoryUntouched() {
        String[] s = seedProject();
        UUID id = seedProposal(s[0], s[1], "concepts/nope.md", "Rejected", "Should not be written.");

        JsonNode rejected = json(call("memory_auto_improve", Map.of("action", "reject", "id", id.toString())));
        assertThat(rejected.get("status").asString()).isEqualTo("rejected");

        // Never written: reading the page errors.
        CallToolResult read = call("memory_read_page", Map.of(
                "path", "concepts/nope.md", "workspace", s[0], "project", s[1]));
        assertThat(read.isError()).isTrue();
    }

    @Test
    void approvingTwiceConflicts() {
        String[] s = seedProject();
        UUID id = seedProposal(s[0], s[1], "concepts/once.md", "Once", "Only once.");

        assertThat(call("memory_auto_improve", Map.of("action", "approve", "id", id.toString())).isError())
                .isFalse();
        // Second approve: the row is no longer 'proposed' → a conflict error, not a silent re-apply.
        assertThat(call("memory_auto_improve", Map.of("action", "approve", "id", id.toString())).isError())
                .isTrue();
    }
}
