package com.agentmemory.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.ProviderFactory;
import com.agentmemory.llm.TestDoubleProvider;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.file.Path;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wiring proof for the issue #19 MCP tools over the <em>real</em> Streamable-HTTP transport — the
 * guard against a loose tool class that is never registered/reached. A real MCP client connects to
 * {@code /mcp}, lists {@code memory_consolidate} + {@code memory_explore}, and calls each against a
 * seeded session on a throwaway {@code pgvector/pgvector:pg16}.
 *
 * <p>The chat provider is scripted with a {@link Primary} {@link ProviderFactory} whose {@code test}
 * double returns a valid reply deterministically, so the autowired {@code Consolidator}/
 * {@code MemoryExplore} (which bind {@code @Qualifier("llmProvider")}) get it; the health gate probes
 * that same double, so startup is unaffected. (The full atomic fan-out / rollback / supersession
 * behaviour is proven in {@code ConsolidationIntegrationTest}; this test proves the tools are
 * registered and routed end-to-end.)
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false"
        })
@Testcontainers
class ConsolidationMcpEndpointTest {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @org.junit.jupiter.api.io.TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agent-memory.data.dir", () -> dataDir.toString());
    }

    /**
     * Script the chat provider via a {@link Primary} {@link ProviderFactory} so the module builds its
     * {@code llmProvider} (and the recall provider, which defaults to it) from the scripted double and
     * the autowired consolidation services return valid replies. A structured request yields a two-page
     * consolidation; a free-text request (explore) yields prose.
     */
    @TestConfiguration
    static class ScriptedLlm {
        @Bean
        @Primary
        ProviderFactory scriptedConsolidationProviderFactory() {
            return new ProviderFactory(
                    TestDoubleProvider.builder().chatResponder(ScriptedLlm::reply).build());
        }

        private static String reply(ChatRequest req) {
            if (req.wantsStructuredOutput()) {
                return "{\"pages\":["
                        + "{\"folder\":\"concepts\",\"slug\":\"recall\",\"title\":\"Recall\","
                        + "\"body\":\"How recall fuses signals. See [[decisions/pgvector]].\"},"
                        + "{\"folder\":\"decisions\",\"slug\":\"pgvector\",\"title\":\"Use pgvector\","
                        + "\"body\":\"We chose pgvector cosine.\"}]}";
            }
            return "The project is fresh; the recall work is the current thread.";
        }
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

    /** Seed a project + a session with an observation; returns {ws, proj, sessionId}. */
    private String[] seed(String obsPayload) {
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        String proj = "proj";
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        JdbcTemplate j = jdbc();
        j.update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        j.update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, proj);
        j.update("INSERT INTO sessions (id, workspace_id, project_id, workspace, project, started_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                sessionId, wsId, projId, ws, proj, java.sql.Timestamp.from(Instant.now()));
        j.update("INSERT INTO observations (id, session_id, workspace_id, project_id, workspace, "
                        + "project, kind, payload, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), sessionId, wsId, projId, ws, proj, "user-prompt", obsPayload,
                java.sql.Timestamp.from(Instant.now()));
        return new String[] {ws, proj, sessionId.toString()};
    }

    private static String text(CallToolResult result) {
        assertThat(result.isError()).as("tool returned error: %s", result.content()).isNotEqualTo(true);
        assertThat(result.content()).isNotEmpty();
        return ((TextContent) result.content().get(0)).text();
    }

    private JsonNode json(CallToolResult result) {
        return JSON.readTree(text(result));
    }

    private CallToolResult call(String tool, Map<String, Object> args) {
        return client.callTool(new CallToolRequest(tool, args));
    }

    // --- tool listing ------------------------------------------------------------------------------

    @Test
    void listsTheConsolidationTools() {
        List<Tool> tools = client.listTools().tools();
        assertThat(tools).extracting(Tool::name).contains("memory_consolidate", "memory_explore");
        // memory_consolidate is destructive (creates superseding versions); memory_explore is read-only.
        Tool consolidate = tools.stream().filter(t -> t.name().equals("memory_consolidate"))
                .findFirst().orElseThrow();
        assertThat(consolidate.annotations().destructiveHint()).isTrue();
        assertThat(consolidate.annotations().readOnlyHint()).isFalse();
        Tool explore = tools.stream().filter(t -> t.name().equals("memory_explore"))
                .findFirst().orElseThrow();
        assertThat(explore.annotations().readOnlyHint()).isTrue();
    }

    // --- memory_consolidate over the wire ----------------------------------------------------------

    @Test
    void memoryConsolidateMultiPageWritesPagesOverTheWire() {
        String[] s = seed("explain recall and the storage choice");
        JsonNode r = json(call("memory_consolidate", Map.of(
                "workspace", s[0], "project", s[1], "session_id", s[2], "multi_page", true)));

        assertThat(r.get("status").asString()).isEqualTo("written");
        assertThat(r.get("pageCount").asInt()).isEqualTo(2);
        List<String> paths = new java.util.ArrayList<>();
        r.get("pages").forEach(p -> paths.add(p.get("path").asString()));
        assertThat(paths).containsExactlyInAnyOrder("concepts/recall.md", "decisions/pgvector.md");

        // The pages are now queryable in the index (the write went through the real admission chain).
        Long rows = jdbc().queryForObject(
                "SELECT count(*) FROM pages WHERE workspace = ? AND project = ? AND is_latest "
                        + "AND path IN ('concepts/recall.md','decisions/pgvector.md')",
                Long.class, s[0], "proj");
        assertThat(rows).isEqualTo(2L);
    }

    @Test
    void memoryConsolidateSinglePageWritesOnePageOverTheWire() {
        String[] s = seed("just one durable idea");
        JsonNode r = json(call("memory_consolidate", Map.of(
                "workspace", s[0], "project", s[1], "session_id", s[2]))); // multi_page defaults false
        assertThat(r.get("status").asString()).isEqualTo("written");
        assertThat(r.get("pageCount").asInt()).isEqualTo(1);
    }

    @Test
    void memoryConsolidateRequiresASessionId() {
        String[] s = seed("x");
        CallToolResult result = call("memory_consolidate", Map.of("workspace", s[0], "project", s[1]));
        assertThat(result.isError()).isTrue();
    }

    // --- memory_explore over the wire --------------------------------------------------------------

    @Test
    void memoryExploreReturnsCalibratedProseOverTheWire() {
        String[] s = seed("fresh activity just now");
        JsonNode r = json(call("memory_explore", Map.of("workspace", s[0], "project", s[1])));
        assertThat(r.get("staleness").asString()).isEqualTo("fresh");
        assertThat(r.get("digest").asString()).isNotEmpty();
        assertThat(r.get("project").asString()).isEqualTo("proj");
    }
}
