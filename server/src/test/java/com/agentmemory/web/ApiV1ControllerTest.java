package com.agentmemory.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
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

/**
 * Contract tests for the read-only {@code /api/v1} JSON API (issue #35) against a throwaway
 * {@code pgvector/pgvector:pg16} Postgres (Testcontainers): one assertion block per endpoint plus
 * pagination, proving each route returns the documented, stable JSON. Search is checked to return the
 * same hybrid hits as {@code memory_query} (#15). The offline {@code test} provider boots the context
 * (DD-005); these tests are about the HTTP contract.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "agent-memory.llm.auth.provider=test",
            "agent-memory.embeddings.auth.provider=test"
        })
@Testcontainers
class ApiV1ControllerTest {

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

    @LocalServerPort int port;
    @Autowired DataSource dataSource;

    private HttpTestClient http;

    @BeforeEach
    void setUp() {
        http = new HttpTestClient(port);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    // --- seeding -----------------------------------------------------------------------------------

    /** Seed a workspace/project with one page + a session+observation; returns {ws, proj}. */
    private String[] seedProject(String path, String title, String body, String obsPayload) {
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
                UUID.randomUUID(), wsId, projId, ws, proj, path, title, body);
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

    private void seedPage(String ws, String proj, String path, String title, String body) {
        UUID wsId = jdbc().queryForObject("SELECT id FROM workspaces WHERE slug = ?", UUID.class, ws);
        UUID projId = jdbc().queryForObject(
                "SELECT id FROM projects WHERE workspace = ? AND slug = ?", UUID.class, ws, proj);
        jdbc().update("INSERT INTO pages (id, workspace_id, project_id, workspace, project, path, "
                        + "title, body, is_latest, access_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, 0)",
                UUID.randomUUID(), wsId, projId, ws, proj, path, title, body);
    }

    private JsonNode getJson(String path) {
        return http.getJsonOk(path);
    }

    // --- workspaces / projects ---------------------------------------------------------------------

    @Test
    void listsWorkspacesAndProjects() {
        String[] s = seedProject("concepts/recall.md", "Recall", "body", "x");
        JsonNode ws = getJson("/api/v1/workspaces?limit=100");
        assertThat(ws.get("total").asLong()).isGreaterThanOrEqualTo(1);
        boolean found = false;
        for (JsonNode item : ws.get("items")) {
            if (item.get("workspace").asString().equals(s[0])) {
                found = true;
            }
        }
        assertThat(found).as("seeded workspace present in listing").isTrue();

        JsonNode projects = getJson("/api/v1/projects?workspace=" + s[0]);
        assertThat(projects.get("items")).hasSize(1);
        assertThat(projects.get("items").get(0).get("project").asString()).isEqualTo("proj");
        assertThat(projects.get("items").get(0).get("workspace").asString()).isEqualTo(s[0]);
    }

    // --- pages list + read -------------------------------------------------------------------------

    @Test
    void listsAndReadsPages() {
        String[] s = seedProject("concepts/recall.md", "Recall", "the recall page body", "x");
        String base = "/api/v1/workspaces/" + s[0] + "/projects/" + s[1];

        JsonNode list = getJson(base + "/pages");
        assertThat(list.get("total").asLong()).isEqualTo(1);
        assertThat(list.get("items").get(0).get("path").asString()).isEqualTo("concepts/recall.md");
        // summary is metadata only — no body
        assertThat(list.get("items").get(0).has("body")).isFalse();

        // read full body via the wildcard {*path} (path contains a slash)
        JsonNode detail = getJson(base + "/pages/concepts/recall.md");
        assertThat(detail.get("title").asString()).isEqualTo("Recall");
        assertThat(detail.get("body").asString()).isEqualTo("the recall page body");
        assertThat(detail.get("latest").asBoolean()).isTrue();
        // retention layer surfaced (#24). These rows are seeded with raw SQL (bypassing the
        // classifier in JdbcPageRepository.create), so they carry the DB default 'episodic' — the
        // point here is that the field is present and a valid layer, not which one.
        assertThat(detail.get("layer").asString()).isEqualTo("episodic");
    }

    @Test
    void unknownPageIs404() {
        String[] s = seedProject("concepts/recall.md", "Recall", "b", "x");
        HttpTestClient.Response r = http.get(
                "/api/v1/workspaces/" + s[0] + "/projects/" + s[1] + "/pages/nope/missing.md");
        assertThat(r.status()).isEqualTo(404);
    }

    // --- recent / briefing -------------------------------------------------------------------------

    @Test
    void recentReturnsLatestPages() {
        String[] s = seedProject("concepts/recall.md", "Recall", "b", "x");
        JsonNode r = getJson("/api/v1/workspaces/" + s[0] + "/projects/" + s[1] + "/recent?limit=5");
        assertThat(r.get("total").asLong()).isEqualTo(1);
        assertThat(r.get("items").get(0).get("path").asString()).isEqualTo("concepts/recall.md");
    }

    @Test
    void briefingReturnsStructuredSnapshot() {
        String[] s = seedProject("concepts/recall.md", "Recall", "b", "investigate widget");
        seedPage(s[0], s[1], "_rules/no-secrets.md", "No secrets", "rule body");
        JsonNode r = getJson("/api/v1/workspaces/" + s[0] + "/projects/" + s[1] + "/briefing");
        assertThat(r.get("pages").asLong()).isEqualTo(2);
        assertThat(r.get("observationsLast7Days").asLong()).isEqualTo(1);
        assertThat(r.get("observationsLast30Days").asLong()).isEqualTo(1);
        assertThat(r.get("rules").isArray()).isTrue();
        assertThat(r.get("rules")).anySatisfy(n -> assertThat(n.asString()).isEqualTo("_rules/no-secrets.md"));
        assertThat(r.get("recent")).isNotEmpty();
    }

    // --- overview bundle ---------------------------------------------------------------------------

    @Test
    void overviewBundlesHandoffBriefingAndHealth() {
        String[] s = seedProject("concepts/recall.md", "Recall", "b", "x");
        JsonNode r = getJson("/api/v1/workspaces/" + s[0] + "/overview?project=" + s[1]);
        // handoff seam (#22): present as an explicit null field
        assertThat(r.has("handoff")).isTrue();
        assertThat(r.get("handoff").isNull()).isTrue();
        // briefing + health are bundled
        assertThat(r.get("briefing").get("pages").asLong()).isEqualTo(1);
        assertThat(r.get("health").get("pages").asLong()).isEqualTo(1);
        assertThat(r.get("health").get("observations").asLong()).isEqualTo(1);
        assertThat(r.get("scope").get("project").asString()).isEqualTo("proj");
    }

    // --- search GET + POST = same hybrid results as memory_query -----------------------------------

    @Test
    void searchGetReturnsHybridHits() {
        String[] s = seedProject("concepts/recall.md",
                "Hybrid recall", "Reciprocal rank fusion blends full-text and graph.", "x");
        JsonNode r = getJson("/api/v1/search?q=reciprocal+rank+fusion&workspace=" + s[0]
                + "&project=" + s[1]);
        assertThat(r.get("rawFallback").asBoolean()).isFalse();
        assertThat(r.get("hits")).isNotEmpty();
        JsonNode top = r.get("hits").get(0);
        assertThat(top.get("path").asString()).isEqualTo("concepts/recall.md");
        assertThat(top.get("source").asString()).isEqualTo("PAGE");
        assertThat(top.get("snippet").asString()).contains("<mark>");
        assertThat(top.get("rank").asInt()).isEqualTo(1);
        assertThat(r.get("scopes").get(0).get("project").asString()).isEqualTo("proj");
    }

    @Test
    void searchPostWithScopesMatchesGet() {
        String[] s = seedProject("concepts/recall.md",
                "Hybrid recall", "Reciprocal rank fusion blends full-text and graph.", "x");
        String q = "reciprocal rank fusion";

        JsonNode get = getJson("/api/v1/search?q=reciprocal+rank+fusion&workspace=" + s[0]
                + "&project=" + s[1]);

        Map<String, Object> body = Map.of(
                "q", q,
                "scopes", List.of(Map.of("workspace", s[0], "project", s[1])));
        HttpTestClient.Response post = http.postJson("/api/v1/search", body);
        assertThat(post.is2xx()).as("POST /search -> %s : %s", post.status(), post.body()).isTrue();
        JsonNode postJson = post.json();

        // Same top hit, same path, same count — POST and GET share one recall path (#15).
        assertThat(postJson.get("count").asInt()).isEqualTo(get.get("count").asInt());
        assertThat(postJson.get("hits").get(0).get("path").asString())
                .isEqualTo(get.get("hits").get(0).get("path").asString());
    }

    @Test
    void searchPostWithoutScopesIs400() {
        HttpTestClient.Response r = http.postJson("/api/v1/search", Map.of("q", "anything"));
        assertThat(r.status()).isEqualTo(400);
    }

    // --- graph seam (#28) --------------------------------------------------------------------------

    @Test
    void graphIsAnEmptyWellTypedSeam() {
        JsonNode r = getJson("/api/v1/graph");
        assertThat(r.get("nodes").isArray()).isTrue();
        assertThat(r.get("edges").isArray()).isTrue();
        assertThat(r.get("nodes")).isEmpty();
        assertThat(r.get("note").asString()).contains("#28");
    }

    // --- pagination --------------------------------------------------------------------------------

    @Test
    void pagesArePaginatedWithLimitAndOffset() {
        String[] s = seedProject("concepts/p00.md", "p00", "b", "x");
        for (int i = 1; i < 5; i++) {
            seedPage(s[0], s[1], String.format("concepts/p%02d.md", i), "p" + i, "b" + i);
        }
        String base = "/api/v1/workspaces/" + s[0] + "/projects/" + s[1] + "/pages";

        JsonNode page1 = getJson(base + "?limit=2&offset=0");
        assertThat(page1.get("total").asLong()).isEqualTo(5);
        assertThat(page1.get("limit").asInt()).isEqualTo(2);
        assertThat(page1.get("offset").asInt()).isEqualTo(0);
        assertThat(page1.get("items")).hasSize(2);

        JsonNode page2 = getJson(base + "?limit=2&offset=2");
        assertThat(page2.get("items")).hasSize(2);
        assertThat(page2.get("offset").asInt()).isEqualTo(2);

        JsonNode page3 = getJson(base + "?limit=2&offset=4");
        assertThat(page3.get("items")).hasSize(1); // 5 total, last page has the remainder

        // No id overlap across pages (distinct slices).
        String p1a = page1.get("items").get(0).get("path").asString();
        String p2a = page2.get("items").get(0).get("path").asString();
        assertThat(p1a).isNotEqualTo(p2a);
    }

    @Test
    void badLimitIs400() {
        String[] s = seedProject("concepts/recall.md", "Recall", "b", "x");
        HttpTestClient.Response r = http.get(
                "/api/v1/workspaces/" + s[0] + "/projects/" + s[1] + "/pages?limit=0");
        assertThat(r.status()).isEqualTo(400);
    }

    @Test
    void malformedScopeSlugIs400() {
        // A slug containing a path separator is rejected by the core value type (a slug is one
        // segment) -> 400, not 500. Asserted via the POST body so the value reaches the handler
        // verbatim (a JSON string body is not subject to URL path/query decoding quirks).
        HttpTestClient.Response r = http.postJson("/api/v1/search", Map.of(
                "q", "anything",
                "scopes", List.of(Map.of("workspace", "bad/slug", "project", "proj"))));
        assertThat(r.status()).isEqualTo(400);
    }
}
