package com.agentmemory.consolidate;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.hooks.HookPayload;
import com.agentmemory.hooks.IngestService;
import com.agentmemory.hooks.IngestStatus;
import com.agentmemory.llm.ProviderFactory;
import com.agentmemory.llm.TestDoubleProvider;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
 * Proves session synthesis (issue #18) actually fires <strong>through the live ingest path</strong>:
 * posting a {@code session-end} hook to the fully-wired {@link IngestService} triggers the
 * {@link SessionConsolidationTrigger} (via the {@link ConsolidationObservationListener} seam) and
 * writes the {@code sessions/<id>.md} page — the DoD of #18. This is the integration that closes the
 * "seam nobody calls" gap: the synthesizer/trigger were already covered in isolation, but nothing
 * exercised them from a real captured event until now.
 *
 * <p>The autowired LLM is the offline {@code test} double, scripted via a {@code @Primary}
 * {@link ProviderFactory} to return the golden synthesis JSON for a structured request, so the wired
 * synthesizer produces a deterministic page without a network. A fresh {@code (workspace, project,
 * session)} per test isolates the shared container.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test",
    "agent-memory.wiki.watch-enabled=false"
})
@Testcontainers
class IngestTriggersSynthesisTest {

    private static final String GOLDEN_JSON = """
            {
              "title": "Ingest-driven session synthesis",
              "summary": "Wired the consolidation trigger into the ingest path so session-end synthesizes.",
              "decisions": ["Used an ObservationListener seam to keep hooks decoupled"],
              "follow_ups": [],
              "open_questions": [],
              "highlights": ["Synthesis fires from a real captured session-end"]
            }
            """;

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres"));

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("agent-memory.data.dir", () -> dataDir.toString());
    }

    /** Replaces the provider factory with one whose {@code test} double returns the golden synthesis JSON. */
    @TestConfiguration
    static class GoldenLlmConfig {
        @Bean
        @Primary
        ProviderFactory goldenProviderFactory() {
            TestDoubleProvider golden = TestDoubleProvider.builder()
                    .chatResponder(req -> req.wantsStructuredOutput() ? GOLDEN_JSON : "chunk summary text")
                    .build();
            return new ProviderFactory(golden);
        }
    }

    @Autowired DataSource dataSource;
    @Autowired PageRepository pages;
    @Autowired IngestService ingest;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private record Scope(String ws, String proj, UUID wsId, UUID projId) {}

    private Scope freshScope() {
        String ws = "ws" + UUID.randomUUID().toString().replace("-", "");
        UUID wsId = UUID.randomUUID();
        UUID projId = UUID.randomUUID();
        jdbc().update("INSERT INTO workspaces (id, slug) VALUES (?, ?)", wsId, ws);
        jdbc().update("INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?)",
                projId, wsId, ws, "proj");
        return new Scope(ws, "proj", wsId, projId);
    }

    private SessionId seedSession(Scope s) {
        SessionId sid = SessionId.newId();
        jdbc().update(
                "INSERT INTO sessions (id, workspace_id, project_id, workspace, project, started_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                sid.value(), s.wsId(), s.projId(), s.ws(), s.proj(),
                java.sql.Timestamp.from(Instant.parse("2026-06-25T12:00:00Z")));
        return sid;
    }

    private void seedObservation(Scope s, SessionId sid, String kind, String payload) {
        jdbc().update(
                "INSERT INTO observations (id, session_id, workspace_id, project_id, workspace, project, "
                        + "kind, payload, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), sid.value(), s.wsId(), s.projId(), s.ws(), s.proj(),
                kind, payload, java.sql.Timestamp.from(Instant.now()));
    }

    private HookPayload sessionEndHook(Scope s, SessionId sid) {
        // HookPayload.of canonicalizes the event; "SessionEnd" -> ObservationKind.SESSION_END.
        return HookPayload.of(
                "SessionEnd", sid, WorkspaceId.of(s.ws()), ProjectId.of(s.proj()), Instant.now());
    }

    @Test
    void postingASessionEndHookSynthesizesTheSessionsPage() throws Exception {
        Scope s = freshScope();
        SessionId sid = seedSession(s);
        // A couple of prior observations give the synthesis something to summarize.
        seedObservation(s, sid, "user-prompt", "wire the consolidation trigger into ingest");
        seedObservation(s, sid, "post-tool-use", "added ObservationListener seam and adapter");

        Identity sessionPage = Identity.ofPage(
                WorkspaceId.of(s.ws()), ProjectId.of(s.proj()), SessionSynthesizer.pagePathFor(sid));
        // Pre-condition: no sessions page yet.
        assertThat(pages.readLatest(sessionPage)).isEmpty();

        // Drive the REAL ingest path with the session-end event (this also persists the session-end
        // observation, then the post-write listener fires synthesis on the ingest worker thread).
        assertThat(ingest.ingest(sessionEndHook(s, sid))).isEqualTo(IngestStatus.ACCEPTED);
        assertThat(ingest.awaitIdle(Duration.ofSeconds(20)))
                .as("ingest write + synthesis complete on the worker")
                .isTrue();

        // The sessions/<id>.md page now exists, written by synthesis triggered through ingest.
        Optional<PageRecord> latest = pages.readLatest(sessionPage);
        assertThat(latest).as("session-end via ingest produced the sessions page").isPresent();
        assertThat(latest.get().page().title()).isEqualTo("Ingest-driven session synthesis");
        assertThat(latest.get().page().path().value()).isEqualTo("sessions/" + sid.value() + ".md");
        assertThat(latest.get().page().body())
                .contains("Wired the consolidation trigger into the ingest path");
    }

    @Test
    void aNonTriggeringHookDoesNotSynthesize() throws Exception {
        Scope s = freshScope();
        SessionId sid = seedSession(s);
        seedObservation(s, sid, "user-prompt", "just a prompt, no session end yet");

        Identity sessionPage = Identity.ofPage(
                WorkspaceId.of(s.ws()), ProjectId.of(s.proj()), SessionSynthesizer.pagePathFor(sid));

        // A user-prompt event is captured but is NOT a consolidation trigger, so no page is written.
        assertThat(ingest.ingest(HookPayload.of(
                "UserPromptSubmit", sid, WorkspaceId.of(s.ws()), ProjectId.of(s.proj()), Instant.now())))
                .isEqualTo(IngestStatus.ACCEPTED);
        assertThat(ingest.awaitIdle(Duration.ofSeconds(10))).isTrue();

        assertThat(pages.readLatest(sessionPage))
                .as("a non-triggering kind must not synthesize").isEmpty();
    }
}
