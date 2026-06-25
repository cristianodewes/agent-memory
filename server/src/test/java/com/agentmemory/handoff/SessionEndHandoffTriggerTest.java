package com.agentmemory.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agentmemory.core.Handoff;
import com.agentmemory.core.Identity;
import com.agentmemory.core.Observation;
import com.agentmemory.core.ObservationId;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.Uuid7;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.recall.Scope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link SessionEndHandoffTrigger} (issue #78): as an ingest post-write
 * {@code Consumer<Observation>}, on a {@code session-end} it calls {@link HandoffService#begin}
 * <strong>off the calling thread</strong> (the ingest worker) on its dedicated daemon executor — the
 * LLM-backed generation must never run inline on the worker (invariant #5). Non-{@code session-end}
 * kinds are filtered cheaply on the calling thread and never reach {@code begin}, and a failing
 * {@code begin} is swallowed without killing the executor. Uses a recording {@link HandoffService}
 * subclass; no Spring, no DB, no LLM.
 */
class SessionEndHandoffTriggerTest {

    /**
     * A {@link HandoffService} that records the (scope, session) it is asked to open a handoff for, and
     * the thread it ran on (to prove the dispatch is off the caller's thread). Optionally throws to
     * prove the trigger swallows a generation failure. The {@code (null, null)} super args are never
     * used because {@code begin} is fully overridden.
     */
    private static final class RecordingHandoffService extends HandoffService {
        final List<SessionId> sessions = new CopyOnWriteArrayList<>();
        final List<String> workspaces = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        volatile boolean throwOnce = false;

        RecordingHandoffService() {
            super(null, null);
        }

        @Override
        public Handoff begin(Scope scope, SessionId session) {
            sessions.add(session);
            workspaces.add(scope.workspaceSlug());
            threads.add(Thread.currentThread().getName());
            if (throwOnce) {
                throwOnce = false;
                throw new IllegalStateException("boom"); // simulate an LLM/persistence failure
            }
            return null; // the trigger ignores the return value
        }
    }

    private static Observation observation(SessionId session, ObservationKind kind) {
        return new Observation(
                new ObservationId(Uuid7.randomUuid()),
                session,
                Identity.ofProject(WorkspaceId.of("acme"), ProjectId.of("agent-memory")),
                kind,
                kind.wire(),
                null,
                "payload",
                Instant.parse("2026-06-25T12:00:00Z"));
    }

    @Test
    void dispatchesSessionEndToBeginOffTheCallerThread() {
        RecordingHandoffService service = new RecordingHandoffService();
        String callerThread = Thread.currentThread().getName();
        try (SessionEndHandoffTrigger trigger = new SessionEndHandoffTrigger(service)) {
            SessionId session = SessionId.newId();

            trigger.accept(observation(session, ObservationKind.SESSION_END));

            // Dispatch is asynchronous (off the worker): begin is invoked shortly, on the dedicated
            // handoff thread, with the right session and scope. All recordings are asserted INSIDE the
            // await — begin writes sessions, then workspaces, then threads as separate (non-atomic)
            // steps, so asserting threads outside the await could observe it before the third write
            // lands (a flaky race).
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(service.sessions).containsExactly(session);
                assertThat(service.workspaces).containsExactly("acme");
                assertThat(service.threads).hasSize(1);
                assertThat(service.threads.get(0))
                        .as("handoff generation runs off the calling (ingest worker) thread")
                        .isNotEqualTo(callerThread)
                        .startsWith("agent-memory-handoff");
            });
        }
    }

    @Test
    void ignoresNonSessionEndKindsWithoutCallingBegin() throws Exception {
        RecordingHandoffService service = new RecordingHandoffService();
        try (SessionEndHandoffTrigger trigger = new SessionEndHandoffTrigger(service)) {

            // Only session-end triggers a handoff; these are filtered on the calling thread and never
            // dispatched, so begin is never invoked.
            trigger.accept(observation(SessionId.newId(), ObservationKind.USER_PROMPT));
            trigger.accept(observation(SessionId.newId(), ObservationKind.POST_TOOL_USE));
            trigger.accept(observation(SessionId.newId(), ObservationKind.PRE_COMPACT));

            // Give any (erroneous) dispatch a chance to run before asserting nothing happened.
            Thread.sleep(200);
            assertThat(service.sessions).isEmpty();
        }
    }

    @Test
    void aNullObservationIsIgnored() throws Exception {
        RecordingHandoffService service = new RecordingHandoffService();
        try (SessionEndHandoffTrigger trigger = new SessionEndHandoffTrigger(service)) {
            trigger.accept(null); // must not throw, must not dispatch
            Thread.sleep(100);
            assertThat(service.sessions).isEmpty();
        }
    }

    @Test
    void aFailingBeginIsSwallowedAndDoesNotKillTheExecutor() {
        RecordingHandoffService service = new RecordingHandoffService();
        service.throwOnce = true;
        try (SessionEndHandoffTrigger trigger = new SessionEndHandoffTrigger(service)) {
            SessionId first = SessionId.newId();
            SessionId second = SessionId.newId();

            // The first generation throws (swallowed by the trigger); the executor must survive so the
            // second session-end is still processed on the same single worker thread.
            trigger.accept(observation(first, ObservationKind.SESSION_END));
            trigger.accept(observation(second, ObservationKind.SESSION_END));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(service.sessions).containsExactly(first, second));
        }
    }
}
