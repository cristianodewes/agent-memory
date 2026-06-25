package com.agentmemory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.links.WikiLinkService;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
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
    @Autowired PageRepository pages;
    @Autowired WikiLinkService links;

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
        assertThat(r.get("dependents").asLong()).isEqualTo(0); // no inbound links seeded (#28)
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

    // --- unified dependency graph (#28) ------------------------------------------------------------

    /** A page written through the real writer path with its links maintained, exactly as production. */
    private PageRecord writeLinked(WorkspaceId ws, String project, String path, String title, String body) {
        PageRecord rec = pages.create(
                Identity.ofPage(ws, ProjectId.of(project), PagePath.of(path)), title, body);
        links.syncPageLinks(rec);
        return rec;
    }

    private static WorkspaceId freshWorkspace() {
        return WorkspaceId.of("ws" + UUID.randomUUID().toString().replace("-", ""));
    }

    /**
     * Seed a small two-project corpus in one fresh workspace and return it:
     * <pre>
     *   app/concepts/index.md   --[[concepts/recall]]-->     app/concepts/recall.md      (same-project)
     *   app/concepts/index.md   --[[platform:concepts/auth]]--> platform/concepts/auth.md (cross-project)
     *   platform/concepts/auth.md --[[concepts/missing]]-->   (unresolved: deferred)
     * </pre>
     * Three resolved-edge candidates? No — index has two outgoing resolved edges; auth has one
     * unresolved (dangling/deferred) link. So 2 resolved edges, 3 nodes, 1 unresolved link.
     */
    private WorkspaceId seedGraphCorpus() {
        WorkspaceId ws = freshWorkspace();
        // Targets first so the same-project + cross-project links resolve immediately on index's write.
        writeLinked(ws, "app", "concepts/recall.md", "Recall", "the recall page");
        writeLinked(ws, "platform", "concepts/auth.md",
                "Auth", "platform auth; refers to [[concepts/missing]] which does not exist");
        writeLinked(ws, "app", "concepts/index.md", "Index",
                "see [[concepts/recall]] and depends on [[platform:concepts/auth]]");
        return ws;
    }

    @Test
    void graphReturnsResolvedEdgesAndNodesAcrossProjects() {
        WorkspaceId ws = seedGraphCorpus();
        String w = ws.value();
        // Scope to this workspace's app project so the shared container's other rows don't leak in.
        JsonNode r = getJson("/api/v1/graph?workspace=" + w + "&project=app&limit=100");

        // Edges are resolved links only. app/index -> app/recall (same-project) and
        // app/index -> platform/auth (cross-project). The unresolved [[concepts/missing]] is NOT here.
        JsonNode edges = r.get("edges");
        assertThat(edges).hasSize(2);
        String idx = w + "/app/concepts/index.md";
        String recall = w + "/app/concepts/recall.md";
        String auth = w + "/platform/concepts/auth.md";
        assertThat(edges).anySatisfy(e -> {
            assertThat(e.get("from").asString()).isEqualTo(idx);
            assertThat(e.get("to").asString()).isEqualTo(recall);
            assertThat(e.get("crossProject").asBoolean()).isFalse();
        });
        assertThat(edges).anySatisfy(e -> {
            assertThat(e.get("from").asString()).isEqualTo(idx);
            assertThat(e.get("to").asString()).isEqualTo(auth);
            assertThat(e.get("crossProject").asBoolean()).isTrue();
        });

        // Nodes are exactly the distinct endpoints of those edges (self-contained subgraph).
        assertThat(r.get("nodes")).extracting(n -> n.get("id").asString())
                .containsExactlyInAnyOrder(idx, recall, auth);
        // A node carries its title (hydrated from the latest page).
        assertThat(r.get("nodes")).anySatisfy(n -> {
            if (n.get("id").asString().equals(recall)) {
                assertThat(n.get("title").asString()).isEqualTo("Recall");
            }
        });
        assertThat(r.get("totalEdges").asLong()).isEqualTo(2);
        assertThat(r.get("scope").get("project").asString()).isEqualTo("app");
    }

    @Test
    void graphScopedToProjectIncludesItsCrossProjectEdges() {
        WorkspaceId ws = seedGraphCorpus();
        String w = ws.value();
        // Scoping to "platform" must include the inbound cross-project edge app/index -> platform/auth
        // (an edge that *touches* the project, even though its source is in app).
        JsonNode r = getJson("/api/v1/graph?workspace=" + w + "&project=platform&limit=100");
        String idx = w + "/app/concepts/index.md";
        String auth = w + "/platform/concepts/auth.md";
        assertThat(r.get("edges")).singleElement().satisfies(e -> {
            assertThat(e.get("from").asString()).isEqualTo(idx);
            assertThat(e.get("to").asString()).isEqualTo(auth);
            assertThat(e.get("crossProject").asBoolean()).isTrue();
        });
        assertThat(r.get("totalEdges").asLong()).isEqualTo(1);
    }

    @Test
    void danglingReferenceLintClassifiesStaleVersusDeferred() {
        WorkspaceId ws = seedGraphCorpus();
        String w = ws.value();
        // The corpus has one unresolved link: platform/auth -> concepts/missing. Fresh => deferred.
        JsonNode fresh = getJson("/api/v1/graph?workspace=" + w + "&project=platform");
        assertThat(fresh.get("deferredCount").asLong()).isEqualTo(1);
        assertThat(fresh.get("danglingCount").asLong()).isEqualTo(0);
        assertThat(fresh.get("dangling")).singleElement().satisfies(d -> {
            assertThat(d.get("from").asString()).isEqualTo(w + "/platform/concepts/auth.md");
            assertThat(d.get("target").asString()).isEqualTo(w + "/platform/concepts/missing.md");
            assertThat(d.get("dangling").asBoolean()).isFalse(); // recent -> deferred
        });

        // Age the unresolved link past the 7-day staleness cutoff -> it must reclassify as dangling.
        jdbc().update(
                "UPDATE links SET created_at = now() - interval '30 days' "
                        + "WHERE source_workspace = ? AND source_path = ? AND NOT target_resolved",
                w, "concepts/auth.md");
        JsonNode stale = getJson("/api/v1/graph?workspace=" + w + "&project=platform");
        assertThat(stale.get("danglingCount").asLong()).isEqualTo(1);
        assertThat(stale.get("deferredCount").asLong()).isEqualTo(0);
        assertThat(stale.get("dangling")).singleElement().satisfies(d ->
                assertThat(d.get("dangling").asBoolean()).isTrue());
    }

    @Test
    void graphEdgesArePaginated() {
        WorkspaceId ws = seedGraphCorpus(); // 2 resolved edges in app's neighborhood
        String w = ws.value();
        String base = "/api/v1/graph?workspace=" + w + "&project=app";

        JsonNode page1 = getJson(base + "&limit=1&offset=0");
        assertThat(page1.get("edges")).hasSize(1);
        assertThat(page1.get("limit").asInt()).isEqualTo(1);
        assertThat(page1.get("totalEdges").asLong()).isEqualTo(2); // full count regardless of page

        JsonNode page2 = getJson(base + "&limit=1&offset=1");
        assertThat(page2.get("edges")).hasSize(1);
        assertThat(page2.get("offset").asInt()).isEqualTo(1);

        // Deterministic order => the two pages are disjoint edges.
        String e1 = page1.get("edges").get(0).get("to").asString();
        String e2 = page2.get("edges").get(0).get("to").asString();
        assertThat(e1).isNotEqualTo(e2);
    }

    @Test
    void graphWorkspaceWithoutProjectIs400() {
        // workspace and project must travel together; one without the other is a client error.
        HttpTestClient.Response r = http.get("/api/v1/graph?workspace=onlythis");
        assertThat(r.status()).isEqualTo(400);
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
