package com.agentmemory.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // A real (empty) data dir so SlotsReader reads slot files from a known location, not ~/.
        registry.add("agent-memory.data.dir", () -> dataDir.toString());
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
        // each of the five read tools is flagged read-only (write tools #20 and handoff tools #22 are
        // not — those are asserted in McpWriteEndpointTest and listsTheThreeHandoffToolsAsMutating).
        assertThat(tools).filteredOn(t -> readTools.contains(t.name()))
                .allSatisfy(t -> assertThat(t.annotations().readOnlyHint()).isTrue());
    }

    // --- memory_install_self_routing (issue #40) ---------------------------------------------------

    @Test
    void installSelfRoutingIsRegisteredAsAReadOnlyTool() {
        List<Tool> tools = client.listTools().tools();
        Tool tool = tools.stream()
                .filter(t -> t.name().equals("memory_install_self_routing"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("memory_install_self_routing not registered"));
        assertThat(tool.annotations().readOnlyHint()).isTrue();
        // It takes no scope: that it works on a brand-new project with nothing active is proven by the
        // call test below succeeding with empty args (no most-recent-project to resolve).
    }

    @Test
    void installSelfRoutingReturnsTheFencedSnippetWithMarkersAndTarget() {
        JsonNode r = json(call("memory_install_self_routing", Map.of()));
        String begin = "<!-- BEGIN agent-memory:self-routing -->";
        String end = "<!-- END agent-memory:self-routing -->";
        assertThat(r.path("beginMarker").stringValue()).isEqualTo(begin);
        assertThat(r.path("endMarker").stringValue()).isEqualTo(end);
        assertThat(r.path("target").stringValue()).contains("CLAUDE.md").contains("AGENTS.md");
        String snippet = r.path("snippet").stringValue();
        // The block is fenced by the stable markers (so an installer can replace it in place) and the
        // body routes the agent to the core recall/handoff/write tools.
        assertThat(snippet).startsWith(begin).endsWith(end + "\n");
        assertThat(snippet)
                .contains("memory_query")
                .contains("memory_briefing")
                .contains("memory_handoff_accept")
                .contains("memory_write_page");
    }

    // --- handoff tools (issue #22) -----------------------------------------------------------------

    /** Seed an OPEN handoff row directly, returning its id (so accept/cancel can be tested over MCP). */
    private UUID seedOpenHandoff(String ws, String proj, String summary) {
        UUID sessionId = jdbc().queryForObject(
                "SELECT id FROM sessions WHERE workspace = ? AND project = ? LIMIT 1",
                UUID.class, ws, proj);
        UUID wsId = jdbc().queryForObject("SELECT id FROM workspaces WHERE slug = ?", UUID.class, ws);
        UUID projId = jdbc().queryForObject(
                "SELECT id FROM projects WHERE workspace = ? AND slug = ?", UUID.class, ws, proj);
        UUID id = UUID.randomUUID();
        jdbc().update(
                "INSERT INTO handoffs (id, workspace_id, project_id, workspace, project, from_session, "
                        + "status, summary, open_questions, next_steps, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'open', ?, '{}', '{}', ?)",
                id, wsId, projId, ws, proj, sessionId, summary, java.sql.Timestamp.from(Instant.now()));
        return id;
    }

    @Test
    void listsTheThreeHandoffToolsAsMutating() {
        List<Tool> tools = client.listTools().tools();
        assertThat(tools).extracting(Tool::name).contains(
                "memory_handoff_begin", "memory_handoff_accept", "memory_handoff_cancel");
        // handoff tools mutate the lifecycle — not read-only.
        assertThat(tools).filteredOn(t -> t.name().startsWith("memory_handoff_"))
                .allSatisfy(t -> assertThat(t.annotations().readOnlyHint()).isFalse());
    }

    @Test
    void handoffAcceptReturnsTheOpenHandoffOnceThenNone() {
        String[] s = seedProject("t", "b", "x");
        UUID handoffId = seedOpenHandoff(s[0], s[1], "where I left off");

        // First accept consumes it: present, the seeded body, marked accepted.
        JsonNode first = json(call("memory_handoff_accept", Map.of("workspace", s[0], "project", s[1])));
        assertThat(first.get("present").asBoolean()).isTrue();
        assertThat(first.get("id").asString()).isEqualTo(handoffId.toString());
        assertThat(first.get("summary").asString()).isEqualTo("where I left off");
        assertThat(first.get("status").asString()).isEqualTo("accepted");

        // Second accept finds nothing — single-use — but is still a well-formed result, not an error.
        JsonNode second = json(call("memory_handoff_accept", Map.of("workspace", s[0], "project", s[1])));
        assertThat(second.get("present").asBoolean()).isFalse();
        assertThat(second.get("id").isNull()).isTrue();
    }

    @Test
    void handoffCancelExpiresTheOpenHandoff() {
        String[] s = seedProject("t", "b", "x");
        UUID handoffId = seedOpenHandoff(s[0], s[1], "opened by mistake");

        JsonNode cancelled = json(call("memory_handoff_cancel", Map.of("workspace", s[0], "project", s[1])));
        assertThat(cancelled.get("present").asBoolean()).isTrue();
        assertThat(cancelled.get("id").asString()).isEqualTo(handoffId.toString());
        assertThat(cancelled.get("status").asString()).isEqualTo("expired");

        // Now nothing is open: accept finds none.
        JsonNode accept = json(call("memory_handoff_accept", Map.of("workspace", s[0], "project", s[1])));
        assertThat(accept.get("present").asBoolean()).isFalse();
    }

    @Test
    void handoffAcceptOnEmptyProjectIsWellFormedNotAnError() {
        String[] s = seedProject("t", "b", "x");
        JsonNode r = json(call("memory_handoff_accept", Map.of("workspace", s[0], "project", s[1])));
        assertThat(r.get("present").asBoolean()).isFalse();
        assertThat(r.get("scope").get("project").asString()).isEqualTo("proj");
    }

    @Test
    void handoffBeginRequiresASessionId() {
        String[] s = seedProject("t", "b", "x");
        CallToolResult result = call("memory_handoff_begin", Map.of("workspace", s[0], "project", s[1]));
        // No sessionId → a clear tool error (the schema marks it required; the handler guards too).
        assertThat(result.isError()).isTrue();
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

    /** Seed two sibling projects (alpha, beta) in a fresh workspace, each with a page carrying {@code token}. */
    private String seedCrossProjectCorpus(String token) {
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        UUID wsId = UUID.randomUUID();
        jdbc().update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        seedProjectPage(wsId, ws, "alpha", "concepts/a.md", "Alpha page", "alpha covers " + token);
        seedProjectPage(wsId, ws, "beta", "concepts/b.md", "Beta page", "beta covers " + token);
        return ws;
    }

    private void seedProjectPage(
            UUID wsId, String ws, String proj, String path, String title, String body) {
        UUID projId = UUID.randomUUID();
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, proj);
        seedPageRow(wsId, projId, ws, proj, path, title, body);
    }

    /** Insert one latest page row under an already-existing project. */
    private void seedPageRow(
            UUID wsId, UUID projId, String ws, String proj, String path, String title, String body) {
        jdbc().update("INSERT INTO pages (id, workspace_id, project_id, workspace, project, path, "
                        + "title, body, is_latest, access_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, 0)",
                UUID.randomUUID(), wsId, projId, ws, proj, path, title, body);
    }

    @Test
    void memoryQueryAcrossNamedScopesAnnotatesEachHitsOrigin() {
        String tok = "ztoken" + UUID.randomUUID().toString().replace("-", "");
        String ws = seedCrossProjectCorpus(tok);
        JsonNode r = json(call("memory_query", Map.of(
                "query", tok,
                "scopes", List.of(
                        Map.of("workspace", ws, "project", "alpha"),
                        Map.of("workspace", ws, "project", "beta")))));

        assertThat(r.get("global").asBoolean()).isFalse();
        assertThat(r.get("scopes")).hasSize(2);
        assertThat(r.get("hits")).isNotEmpty();
        // Every hit is annotated with its origin workspace + project, and only the requested ones appear.
        for (JsonNode h : r.get("hits")) {
            assertThat(h.get("workspace").asString()).isEqualTo(ws);
            assertThat(h.get("project").asString()).isIn("alpha", "beta");
        }
    }

    @Test
    void memoryQueryGlobalSearchesEveryProject() {
        String tok = "ztoken" + UUID.randomUUID().toString().replace("-", "");
        String ws = seedCrossProjectCorpus(tok);
        JsonNode r = json(call("memory_query", Map.of("query", tok, "global", true)));

        assertThat(r.get("global").asBoolean()).isTrue();
        // The nonce keeps other tests' data out, so the global result is exactly alpha + beta here.
        boolean alpha = false;
        boolean beta = false;
        for (JsonNode h : r.get("hits")) {
            assertThat(h.get("workspace").asString()).isEqualTo(ws);
            if ("alpha".equals(h.get("project").asString())) {
                alpha = true;
            }
            if ("beta".equals(h.get("project").asString())) {
                beta = true;
            }
        }
        assertThat(alpha).as("alpha project reached by global query").isTrue();
        assertThat(beta).as("beta project reached by global query").isTrue();
    }

    // --- memory_lint (issue #29) -------------------------------------------------------------------

    @Test
    void memoryLintReportsRuleFindingsAndStagesALintPage() {
        // Two same-title pages in ONE fresh project -> a DUPLICATE_TITLE rule finding.
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        jdbc().update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, "app");
        seedPageRow(wsId, projId, ws, "app", "concepts/a.md", "Recall design", "body a");
        seedPageRow(wsId, projId, ws, "app", "concepts/b.md", "recall design", "body b");

        // Rule-only preview: no LLM, nothing written.
        JsonNode preview = json(call("memory_lint", Map.of(
                "workspace", ws, "project", "app", "dry_run", true, "contradictions", false)));
        assertThat(preview.get("dryRun").asBoolean()).isTrue();
        assertThat(preview.get("written").asBoolean()).isFalse();
        assertThat(preview.get("ruleFindings")).anySatisfy(
                f -> assertThat(f.get("rule").asString()).isEqualTo("DUPLICATE_TITLE"));

        // Stage it: a _lint/ report page is written.
        JsonNode staged = json(call("memory_lint", Map.of(
                "workspace", ws, "project", "app", "dry_run", false, "contradictions", false)));
        assertThat(staged.get("written").asBoolean()).isTrue();
        assertThat(staged.get("lintPath").asString()).isEqualTo("_lint/report.md");
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
        // Inbound dependents (#28): the lone seeded page has no inbound links -> 0, but the field is
        // present so a client can show how much other memory depends on this project.
        assertThat(r.get("dependents").asLong()).isEqualTo(0);
        assertThat(r.get("rules").isArray()).isTrue();
        assertThat(r.get("slots").isArray()).isTrue();
        assertThat(r.get("recent")).isNotEmpty();
    }

    @Test
    void memoryBriefingRendersSlotsAsADedicatedSectionWithKind() throws Exception {
        String[] s = seedProject("Recall", "body", "investigate the widget");
        // Two slot pages on disk (source of truth) under wiki/<ws>/proj/_slots/.
        writeSlotFile(s[0], s[1], "identity.md", "Who I am", "invariant");
        writeSlotFile(s[0], s[1], "current-focus.md", "Current focus", "state");

        JsonNode r = json(call("memory_briefing", Map.of("workspace", s[0], "project", s[1])));
        JsonNode slots = r.get("slots");
        assertThat(slots.isArray()).isTrue();
        assertThat(slots.size()).isEqualTo(2);
        // Ordered by path; each carries path/title/slot_kind/pinned.
        JsonNode first = slots.get(0);
        assertThat(first.get("path").asString()).isEqualTo("_slots/current-focus.md");
        assertThat(first.get("slotKind").asString()).isEqualTo("state");
        assertThat(first.get("pinned").asBoolean()).isTrue();
        JsonNode second = slots.get(1);
        assertThat(second.get("path").asString()).isEqualTo("_slots/identity.md");
        assertThat(second.get("slotKind").asString()).isEqualTo("invariant");
    }

    /** Write a minimal valid slot page file under {@code wiki/<ws>/<proj>/_slots/<name>}. */
    private static void writeSlotFile(String ws, String proj, String name, String title, String slotKind)
            throws Exception {
        String path = "_slots/" + name;
        String content = "---\n"
                + "title: \"" + title + "\"\n"
                + "kind: _slots\n"
                + "pinned: true\n"
                + "slot_kind: " + slotKind + "\n"
                + "workspace: \"" + ws + "\"\n"
                + "project: \"" + proj + "\"\n"
                + "path: \"" + path + "\"\n"
                + "created_at: 2026-06-25T12:00:00Z\n"
                + "updated_at: 2026-06-25T12:00:00Z\n"
                + "---\n\n" + title + " body\n";
        Path file = dataDir.resolve("wiki").resolve(ws).resolve(proj).resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
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
