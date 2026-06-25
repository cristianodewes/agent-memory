package com.agentmemory.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Handoff;
import com.agentmemory.core.HandoffId;
import com.agentmemory.core.Identity;
import com.agentmemory.core.HandoffStatus;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.handoff.HandoffException;
import com.agentmemory.handoff.HandoffService;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.HandoffRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Fast, context-free unit tests for {@link HandoffController}'s HTTP contract (issue #22): the
 * unwired {@code 503}, request-validation {@code 400}, the {@code 502} on an unusable LLM reply, and
 * the {@code 200}/{@code 204} accept/cancel shapes. The service is a hand-built
 * {@link HandoffService} over an in-memory {@link HandoffRepository} fake, injected through a tiny
 * {@link ObjectProvider} stub — no Spring, no database. (The LLM-grounded generation and real
 * persistence are covered by the Testcontainers {@code HandoffServiceIntegrationTest}.)
 */
class HandoffControllerTest {

    // --- 503 when unwired --------------------------------------------------------------------------

    @Test
    void beginReturns503WhenHandoffsUnwired() {
        HandoffController controller = new HandoffController(empty());
        ResponseEntity<Map<String, Object>> resp =
                controller.begin(new HandoffController.BeginRequest("ws", "proj", uuid()));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody()).containsEntry("status", "unavailable");
    }

    @Test
    void acceptReturns503WhenHandoffsUnwired() {
        HandoffController controller = new HandoffController(empty());
        ResponseEntity<Map<String, Object>> resp =
                controller.accept(new HandoffController.ScopeRequest("ws", "proj"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // --- 400 validation ----------------------------------------------------------------------------

    @Test
    void beginRejectsMissingBody() {
        HandoffController controller = new HandoffController(provider(service("ignored")));
        assertThat(controller.begin(null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void beginRejectsBlankSessionId() {
        HandoffController controller = new HandoffController(provider(service("ignored")));
        ResponseEntity<Map<String, Object>> resp =
                controller.begin(new HandoffController.BeginRequest("ws", "proj", "  "));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsKey("reason");
    }

    @Test
    void beginRejectsMissingProject() {
        HandoffController controller = new HandoffController(provider(service("ignored")));
        ResponseEntity<Map<String, Object>> resp =
                controller.begin(new HandoffController.BeginRequest("ws", null, uuid()));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void beginRejectsNonUuidSessionId() {
        HandoffController controller = new HandoffController(provider(service("ignored")));
        ResponseEntity<Map<String, Object>> resp =
                controller.begin(new HandoffController.BeginRequest("ws", "proj", "not-a-uuid"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void acceptRejectsHalfSpecifiedScope() {
        HandoffController controller = new HandoffController(provider(service("ignored")));
        ResponseEntity<Map<String, Object>> resp =
                controller.accept(new HandoffController.ScopeRequest("ws", null));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- 200 / 204 happy paths ---------------------------------------------------------------------

    @Test
    void beginReturns200WithTheOpenedHandoff() {
        HandoffService svc = service("{\"summary\":\"did work\",\"openQuestions\":[\"q?\"],"
                + "\"nextSteps\":[\"do x\"]}");
        HandoffController controller = new HandoffController(provider(svc));
        ResponseEntity<Map<String, Object>> resp =
                controller.begin(new HandoffController.BeginRequest("ws", "proj", uuid()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("status", "open");
        assertThat(body).containsEntry("summary", "did work");
        assertThat(body.get("openQuestions")).isEqualTo(List.of("q?"));
        assertThat(body.get("nextSteps")).isEqualTo(List.of("do x"));
        assertThat(body.get("acceptedAt")).isNull();
    }

    @Test
    void beginReturns502WhenLlmReplyIsUnusable() {
        HandoffController controller = new HandoffController(provider(service("not json")));
        ResponseEntity<Map<String, Object>> resp =
                controller.begin(new HandoffController.BeginRequest("ws", "proj", uuid()));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(resp.getBody()).containsEntry("status", "error");
    }

    @Test
    void acceptReturns200WhenOpenThen204WhenConsumed() {
        InMemoryHandoffRepo repo = new InMemoryHandoffRepo();
        HandoffService svc = new HandoffService(
                com.agentmemory.llm.TestDoubleProvider.builder()
                        .chatResponder(r -> "{\"summary\":\"s\",\"openQuestions\":[],\"nextSteps\":[]}")
                        .build(),
                repo);
        // open one first
        svc.begin(Scope.of("ws", "proj"), SessionId.of(uuid()));

        HandoffController controller = new HandoffController(provider(svc));
        ResponseEntity<Map<String, Object>> first =
                controller.accept(new HandoffController.ScopeRequest("ws", "proj"));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("status", "accepted");

        // single-use: second accept has nothing → 204 No Content.
        ResponseEntity<Map<String, Object>> second =
                controller.accept(new HandoffController.ScopeRequest("ws", "proj"));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void cancelReturns204WhenNothingOpen() {
        HandoffController controller = new HandoffController(provider(service("ignored")));
        ResponseEntity<Map<String, Object>> resp =
                controller.cancel(new HandoffController.ScopeRequest("ws", "proj"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    // --- fakes -------------------------------------------------------------------------------------

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    /** A {@link HandoffService} whose LLM always returns {@code cannedJson}, over an in-memory repo. */
    private static HandoffService service(String cannedJson) {
        return new HandoffService(
                com.agentmemory.llm.TestDoubleProvider.builder()
                        .chatResponder(r -> cannedJson)
                        .build(),
                new InMemoryHandoffRepo());
    }

    private static ObjectProvider<HandoffService> empty() {
        return provider(null);
    }

    /** Minimal {@link ObjectProvider} exposing only {@code getIfAvailable()} (all the controller uses). */
    private static ObjectProvider<HandoffService> provider(HandoffService svc) {
        return new ObjectProvider<>() {
            @Override
            public HandoffService getIfAvailable() {
                return svc;
            }

            @Override
            public HandoffService getObject(Object... args) {
                return svc;
            }

            @Override
            public HandoffService getObject() {
                return svc;
            }

            @Override
            public HandoffService getIfUnique() {
                return svc;
            }
        };
    }

    /**
     * A tiny in-memory {@link HandoffRepository}: enough for the controller's accept/cancel/begin
     * surface (one open handoff per project, single-use accept). No observations needed — the canned
     * LLM reply does not depend on them here.
     */
    private static final class InMemoryHandoffRepo implements HandoffRepository {
        private final java.util.Map<String, Handoff> open = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Map<UUID, Handoff> byId = new java.util.concurrent.ConcurrentHashMap<>();

        private static String key(Scope s) {
            return s.workspaceSlug() + "" + s.projectSlug();
        }

        @Override
        public Handoff open(Scope scope, SessionId fromSession, String summary,
                List<String> openQuestions, List<String> nextSteps) {
            // supersede any prior open
            Handoff prior = open.remove(key(scope));
            if (prior != null) {
                byId.put(prior.id().value(), expire(prior));
            }
            Handoff h = new Handoff(
                    HandoffId.newId(),
                    Identity.ofProject(
                            WorkspaceId.of(scope.workspaceSlug()), ProjectId.of(scope.projectSlug())),
                    fromSession, HandoffStatus.OPEN, summary, openQuestions, nextSteps,
                    Instant.now(), null);
            open.put(key(scope), h);
            byId.put(h.id().value(), h);
            return h;
        }

        @Override
        public Optional<Handoff> acceptLatestOpen(Scope scope) {
            Handoff h = open.remove(key(scope));
            if (h == null) {
                return Optional.empty();
            }
            Handoff accepted = new Handoff(h.id(), h.identity(), h.fromSession(),
                    HandoffStatus.ACCEPTED, h.summary(), h.openQuestions(), h.nextSteps(),
                    h.createdAt(), Instant.now());
            byId.put(accepted.id().value(), accepted);
            return Optional.of(accepted);
        }

        @Override
        public Optional<Handoff> cancelLatestOpen(Scope scope) {
            Handoff h = open.remove(key(scope));
            if (h == null) {
                return Optional.empty();
            }
            Handoff expired = expire(h);
            byId.put(expired.id().value(), expired);
            return Optional.of(expired);
        }

        @Override
        public Optional<Handoff> findLatestOpen(Scope scope) {
            return Optional.ofNullable(open.get(key(scope)));
        }

        @Override
        public Optional<Handoff> findById(HandoffId id) {
            return Optional.ofNullable(byId.get(id.value()));
        }

        @Override
        public List<ObservationLine> sessionObservations(SessionId session, int limit) {
            return List.of();
        }

        private static Handoff expire(Handoff h) {
            return new Handoff(h.id(), h.identity(), h.fromSession(), HandoffStatus.EXPIRED,
                    h.summary(), h.openQuestions(), h.nextSteps(), h.createdAt(), null);
        }
    }
}
