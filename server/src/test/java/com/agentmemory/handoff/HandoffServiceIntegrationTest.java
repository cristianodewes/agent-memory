package com.agentmemory.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.Handoff;
import com.agentmemory.core.HandoffStatus;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.hooks.HookPayload;
import com.agentmemory.hooks.IngestService;
import com.agentmemory.hooks.IngestStatus;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.TestDoubleProvider;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.HandoffRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end tests for the LLM-generated typed handoff (issue #22; ARCHITECTURE §3.4) against a
 * throwaway {@code pgvector/pgvector:pg16} Postgres. Proves the acceptance criteria that need a real
 * database and the real persistence:
 *
 * <ul>
 *   <li><b>LLM-generated from observations</b> — {@code begin} reads the session's captured
 *       observations into the prompt and persists exactly the structured handoff the model returned.</li>
 *   <li><b>Single-use</b> — {@code accept} returns the open handoff once and marks it consumed; a
 *       second {@code accept} returns empty.</li>
 *   <li><b>Cross-agent</b> — a handoff opened from one session is consumable by a different session
 *       in the same project (the next agent picks it up).</li>
 *   <li><b>Cancel/expire</b> — {@code cancel} expires the open handoff so a later {@code accept} finds
 *       nothing.</li>
 *   <li><b>Supersede</b> — a second {@code begin} expires the prior open handoff (one-open-per-project
 *       invariant), and only the newer is acceptable.</li>
 *   <li><b>Malformed reply</b> — a model reply that is not the typed shape surfaces as
 *       {@link HandoffException} and nothing is persisted (invariant #7).</li>
 * </ul>
 *
 * <p>Grounding observations are seeded through the real {@link IngestService} (which upserts the
 * {@code sessions} FK row the handoff references). The LLM is a <em>scripted</em>
 * {@link TestDoubleProvider} wired into a hand-built {@link HandoffService} over the autowired real
 * {@link HandoffRepository}, so the reply is deterministic while persistence is exercised for real.
 * The offline {@code test} provider is selected so the context boots past the DD-005 health gate.
 */
@SpringBootTest(properties = {
    "agent-memory.llm.auth.provider=test",
    "agent-memory.embeddings.auth.provider=test"
})
@Testcontainers
class HandoffServiceIntegrationTest {

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

    @Autowired IngestService ingest;
    @Autowired HandoffRepository handoffRepository;
    @Autowired DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    // A fresh workspace per test keeps the per-project one-open invariant isolated on the shared container.
    private static Scope freshScope() {
        return Scope.of("ws" + UUID.randomUUID().toString().replace("-", ""), "proj");
    }

    private HookPayload event(Scope scope, SessionId session, String evt, String clientEventId, String body) {
        return new HookPayload(
                evt, null, session,
                WorkspaceId.of(scope.workspaceSlug()), ProjectId.of(scope.projectSlug()),
                null, "claude-code", null, body,
                null, null, null, null, clientEventId,
                Instant.parse("2026-06-25T12:00:00Z"));
    }

    /** Seed a couple of grounding observations under {@code session} and block until written. */
    private void seedSession(Scope scope, SessionId session, String... prompts) {
        for (int i = 0; i < prompts.length; i++) {
            assertThat(ingest.ingest(event(scope, session, "UserPromptSubmit",
                    "evt-" + session.value() + "-" + i, prompts[i])))
                    .isEqualTo(IngestStatus.ACCEPTED);
        }
        assertThat(ingest.awaitIdle(Duration.ofSeconds(10))).isTrue();
    }

    /** A {@link HandoffService} backed by a scripted LLM double over the real repository. */
    private HandoffService serviceReturning(String cannedJson, AtomicReference<ChatRequest> captured) {
        TestDoubleProvider llm = TestDoubleProvider.builder()
                .chatResponder(req -> {
                    if (captured != null) {
                        captured.set(req);
                    }
                    return cannedJson;
                })
                .build();
        return new HandoffService(llm, handoffRepository);
    }

    private HandoffService serviceReturning(String cannedJson) {
        return serviceReturning(cannedJson, null);
    }

    private static String handoffJson(String summary, List<String> open, List<String> next) {
        String openArr = open.stream().map(s -> "\"" + s + "\"").reduce((a, b) -> a + "," + b).orElse("");
        String nextArr = next.stream().map(s -> "\"" + s + "\"").reduce((a, b) -> a + "," + b).orElse("");
        return "{\"summary\":\"" + summary + "\","
                + "\"openQuestions\":[" + openArr + "],"
                + "\"nextSteps\":[" + nextArr + "]}";
    }

    // --- LLM-generated from the session's observations -----------------------------------------

    @Test
    void beginGeneratesHandoffFromSessionObservationsAndPersistsIt() {
        Scope scope = freshScope();
        SessionId session = SessionId.newId();
        seedSession(scope, session, "wire the handoff endpoints", "make accept single-use");

        AtomicReference<ChatRequest> captured = new AtomicReference<>();
        HandoffService service = serviceReturning(
                handoffJson("Wired handoff endpoints; accept is single-use.",
                        List.of("should cancel require a reason?"),
                        List.of("write the MCP tools", "open the PR")),
                captured);

        Handoff opened = service.begin(scope, session);

        // The model saw the session's observations as grounding material.
        String prompt = captured.get().messages().get(captured.get().messages().size() - 1).content();
        assertThat(prompt).contains("wire the handoff endpoints").contains("make accept single-use");
        assertThat(captured.get().wantsStructuredOutput()).as("structured-JSON request (#7)").isTrue();

        // Exactly the structured handoff the model returned was persisted, open, linked to the session.
        assertThat(opened.status()).isEqualTo(HandoffStatus.OPEN);
        assertThat(opened.fromSession()).isEqualTo(session);
        assertThat(opened.identity().workspace().value()).isEqualTo(scope.workspaceSlug());
        assertThat(opened.identity().project().value()).isEqualTo(scope.projectSlug());
        assertThat(opened.summary()).isEqualTo("Wired handoff endpoints; accept is single-use.");
        assertThat(opened.openQuestions()).containsExactly("should cancel require a reason?");
        assertThat(opened.nextSteps()).containsExactly("write the MCP tools", "open the PR");
        assertThat(opened.acceptedAt()).isNull();

        // It is the project's open handoff (peek does not consume).
        assertThat(service.peekOpen(scope).map(Handoff::id)).contains(opened.id());
    }

    @Test
    void beginToleratesAModelReplyThatOmitsTheArrays() {
        Scope scope = freshScope();
        SessionId session = SessionId.newId();
        seedSession(scope, session, "did some work");

        // Only summary present — the lists must default to empty, never null (serialization contract).
        HandoffService service = serviceReturning("{\"summary\":\"just a summary\"}");
        Handoff opened = service.begin(scope, session);

        assertThat(opened.summary()).isEqualTo("just a summary");
        assertThat(opened.openQuestions()).isEmpty();
        assertThat(opened.nextSteps()).isEmpty();
    }

    // --- single-use ----------------------------------------------------------------------------

    @Test
    void acceptIsSingleUse() {
        Scope scope = freshScope();
        SessionId session = SessionId.newId();
        seedSession(scope, session, "work happened");
        HandoffService service = serviceReturning(
                handoffJson("did the thing", List.of(), List.of("next thing")));
        Handoff opened = service.begin(scope, session);

        Optional<Handoff> first = service.accept(scope);
        assertThat(first).isPresent();
        assertThat(first.get().id()).isEqualTo(opened.id());
        assertThat(first.get().status()).isEqualTo(HandoffStatus.ACCEPTED);
        assertThat(first.get().acceptedAt()).isNotNull();

        // Second accept finds nothing — the handoff is consumed.
        assertThat(service.accept(scope)).isEmpty();
        // And no open handoff remains for the project.
        assertThat(service.peekOpen(scope)).isEmpty();
    }

    // --- cross-agent -----------------------------------------------------------------------------

    @Test
    void handoffOpenedByOneSessionIsConsumableByAnother() {
        Scope scope = freshScope();
        SessionId writer = SessionId.newId();
        seedSession(scope, writer, "agent A did setup");
        HandoffService service = serviceReturning(
                handoffJson("A left notes for B", List.of(), List.of("B: continue")));
        Handoff opened = service.begin(scope, writer);

        // A different session/agent (no shared in-memory state) accepts it — cross-agent within project.
        Optional<Handoff> consumed = service.accept(scope);
        assertThat(consumed).isPresent();
        assertThat(consumed.get().id()).isEqualTo(opened.id());
        assertThat(consumed.get().fromSession()).isEqualTo(writer);
        assertThat(consumed.get().summary()).isEqualTo("A left notes for B");
    }

    // --- cancel / expire -------------------------------------------------------------------------

    @Test
    void cancelExpiresTheOpenHandoffSoItIsNotAccepted() {
        Scope scope = freshScope();
        SessionId session = SessionId.newId();
        seedSession(scope, session, "opened by mistake");
        HandoffService service = serviceReturning(handoffJson("oops", List.of(), List.of()));
        Handoff opened = service.begin(scope, session);

        Optional<Handoff> cancelled = service.cancel(scope);
        assertThat(cancelled).isPresent();
        assertThat(cancelled.get().id()).isEqualTo(opened.id());
        assertThat(cancelled.get().status()).isEqualTo(HandoffStatus.EXPIRED);
        assertThat(cancelled.get().acceptedAt()).as("expired without consuming").isNull();

        // Nothing left to accept; a second cancel is a no-op.
        assertThat(service.accept(scope)).isEmpty();
        assertThat(service.cancel(scope)).isEmpty();
    }

    // --- supersede (one open per project) --------------------------------------------------------

    @Test
    void beginSupersedesThePriorOpenHandoff() {
        Scope scope = freshScope();
        SessionId first = SessionId.newId();
        SessionId second = SessionId.newId();
        seedSession(scope, first, "first session work");
        seedSession(scope, second, "second session work");

        HandoffService service = serviceReturning(handoffJson("first", List.of(), List.of()));
        Handoff older = service.begin(scope, first);

        HandoffService service2 = serviceReturning(handoffJson("second", List.of(), List.of("go")));
        Handoff newer = service2.begin(scope, second);
        assertThat(newer.id()).isNotEqualTo(older.id());

        // Exactly one open row for the project at the DB level (the partial unique index holds).
        Integer openCount = jdbc().queryForObject(
                "SELECT count(*) FROM handoffs WHERE workspace = ? AND project = ? AND status = 'open'",
                Integer.class, scope.workspaceSlug(), scope.projectSlug());
        assertThat(openCount).isEqualTo(1);

        // The prior open handoff was expired; accept returns the newer one.
        assertThat(handoffRepository.findById(older.id()).orElseThrow().status())
                .isEqualTo(HandoffStatus.EXPIRED);
        Optional<Handoff> accepted = service2.accept(scope);
        assertThat(accepted).isPresent();
        assertThat(accepted.get().id()).isEqualTo(newer.id());
    }

    // --- malformed reply (invariant #7) ----------------------------------------------------------

    @Test
    void malformedModelReplyIsRejectedAndNothingPersisted() {
        Scope scope = freshScope();
        SessionId session = SessionId.newId();
        seedSession(scope, session, "work");

        HandoffService service = serviceReturning("this is not json at all");
        assertThatThrownBy(() -> service.begin(scope, session))
                .isInstanceOf(HandoffException.class);

        // Nothing was persisted for the project.
        Integer count = jdbc().queryForObject(
                "SELECT count(*) FROM handoffs WHERE workspace = ? AND project = ?",
                Integer.class, scope.workspaceSlug(), scope.projectSlug());
        assertThat(count).isZero();
    }

    @Test
    void replyMissingSummaryIsRejected() {
        Scope scope = freshScope();
        SessionId session = SessionId.newId();
        seedSession(scope, session, "work");

        HandoffService service = serviceReturning("{\"openQuestions\":[],\"nextSteps\":[]}");
        assertThatThrownBy(() -> service.begin(scope, session))
                .isInstanceOf(HandoffException.class)
                .hasMessageContaining("summary");
    }
}
