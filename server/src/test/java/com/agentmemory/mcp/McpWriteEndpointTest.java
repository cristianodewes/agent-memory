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
import java.util.List;
import java.util.Map;
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
 * End-to-end MCP integration for the issue #20 write tools over the real Streamable-HTTP transport: a
 * real MCP client connects to {@code /mcp}, sees {@code memory_write_page} / {@code memory_delete_page}
 * advertised with the right annotations, writes a durable page and finds it via {@code memory_query},
 * then deletes it (idempotently). Runs against a throwaway {@code pgvector/pgvector:pg16}
 * (Testcontainers) with the wiki on a temp dir; the offline {@code test} LLM provider boots the
 * context past the DD-005 gate, and the external-edit watcher is disabled.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test",
            "agent-memory.wiki.watch-enabled=false"
        })
@Testcontainers
class McpWriteEndpointTest {

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

    /** Seed an empty project so an explicit scope resolves (no pages yet). */
    private String[] seedProject() {
        String ws = "ws" + java.util.UUID.randomUUID().toString().replace("-", "");
        String proj = "proj";
        java.util.UUID wsId = java.util.UUID.randomUUID();
        java.util.UUID projId = java.util.UUID.randomUUID();
        jdbc().update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, proj);
        return new String[] {ws, proj};
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

    // --- tool listing + annotations ----------------------------------------------------------------

    @Test
    void advertisesWriteToolsWithCorrectAnnotations() {
        List<Tool> tools = client.listTools().tools();
        assertThat(tools).extracting(Tool::name).contains("memory_write_page", "memory_delete_page");

        Tool write = tools.stream().filter(t -> t.name().equals("memory_write_page"))
                .findFirst().orElseThrow();
        assertThat(write.annotations().readOnlyHint()).isFalse();
        assertThat(write.annotations().destructiveHint()).isFalse();

        Tool delete = tools.stream().filter(t -> t.name().equals("memory_delete_page"))
                .findFirst().orElseThrow();
        assertThat(delete.annotations().readOnlyHint()).isFalse();
        assertThat(delete.annotations().destructiveHint()).isTrue();
        assertThat(delete.annotations().idempotentHint()).isTrue();
    }

    // --- write then query --------------------------------------------------------------------------

    @Test
    void writePageThenQueryFindsIt() {
        String[] s = seedProject();
        JsonNode wrote = json(call("memory_write_page", Map.of(
                "path", "decisions/storage.md",
                "title", "Storage decision",
                "body", "We chose postgres with pgvector for the hybrid index.",
                "workspace", s[0], "project", s[1])));
        assertThat(wrote.get("path").asString()).isEqualTo("decisions/storage.md");
        assertThat(wrote.get("superseded").asBoolean()).isFalse();
        assertThat(wrote.get("versionId").asString()).isNotBlank();

        JsonNode hits = json(call("memory_query", Map.of(
                "query", "postgres pgvector", "workspace", s[0], "project", s[1])));
        assertThat(hits.get("rawFallback").asBoolean()).isFalse();
        assertThat(hits.get("hits")).isNotEmpty();
        assertThat(hits.get("hits").get(0).get("path").asString()).isEqualTo("decisions/storage.md");

        // memory_read_page returns the stored body.
        JsonNode read = json(call("memory_read_page", Map.of(
                "path", "decisions/storage.md", "workspace", s[0], "project", s[1])));
        assertThat(read.get("title").asString()).isEqualTo("Storage decision");
        assertThat(read.get("body").asString()).contains("pgvector");
    }

    @Test
    void writePageSecondTimeReportsSuperseded() {
        String[] s = seedProject();
        call("memory_write_page", Map.of(
                "path", "concepts/x.md", "title", "X1", "body", "first",
                "workspace", s[0], "project", s[1]));
        JsonNode again = json(call("memory_write_page", Map.of(
                "path", "concepts/x.md", "title", "X2", "body", "second",
                "workspace", s[0], "project", s[1])));
        assertThat(again.get("superseded").asBoolean()).isTrue();
    }

    // --- delete (idempotent) -----------------------------------------------------------------------

    @Test
    void deletePageRemovesItAndSecondDeleteIsNoOpSuccess() {
        String[] s = seedProject();
        call("memory_write_page", Map.of(
                "path", "concepts/temp.md", "title", "Temp", "body", "ephemeral",
                "workspace", s[0], "project", s[1]));

        JsonNode del = json(call("memory_delete_page", Map.of(
                "path", "concepts/temp.md", "workspace", s[0], "project", s[1])));
        assertThat(del.get("removed").asBoolean()).isTrue();

        // It is gone: reading it now errors.
        CallToolResult read = call("memory_read_page", Map.of(
                "path", "concepts/temp.md", "workspace", s[0], "project", s[1]));
        assertThat(read.isError()).isTrue();

        // Second delete: idempotent no-op success.
        JsonNode del2 = json(call("memory_delete_page", Map.of(
                "path", "concepts/temp.md", "workspace", s[0], "project", s[1])));
        assertThat(del2.get("removed").asBoolean()).isFalse();
    }

    @Test
    void deleteUnknownPathIsNoOpSuccess() {
        String[] s = seedProject();
        JsonNode del = json(call("memory_delete_page", Map.of(
                "path", "nope/missing.md", "workspace", s[0], "project", s[1])));
        assertThat(del.get("removed").asBoolean()).isFalse();
    }

    @Test
    void writePageMissingRequiredArgIsAClearToolError() {
        String[] s = seedProject();
        CallToolResult result = call("memory_write_page", Map.of(
                "path", "concepts/x.md", "title", "X", // body missing
                "workspace", s[0], "project", s[1]));
        assertThat(result.isError()).isTrue();
    }
}
