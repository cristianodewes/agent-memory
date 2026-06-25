package com.agentmemory.consolidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.agentmemory.core.Identity;
import com.agentmemory.core.Observation;
import com.agentmemory.core.ObservationId;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.Uuid7;
import com.agentmemory.core.WorkspaceId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link ConsolidationObservationListener}: as an ingest post-write
 * {@code Consumer<Observation>}, it forwards a <em>triggering</em> observation's session id and kind to
 * the {@link SessionConsolidationTrigger}, and it does so <strong>off the calling thread</strong> (the
 * ingest worker) on its dedicated synthesis executor — the LLM-backed synthesis must never run inline
 * on the worker (invariant #5). Non-triggering kinds are filtered cheaply on the calling thread and
 * never reach the trigger. Uses a recording trigger subclass; no Spring, no DB, no LLM.
 */
class ConsolidationObservationListenerTest {

    /**
     * A trigger that records the (sessionId, kind) pairs it is asked to react to, and the thread it ran
     * on (to prove the dispatch is off the caller's thread).
     */
    private static final class RecordingTrigger extends SessionConsolidationTrigger {
        final List<SessionId> sessions = new CopyOnWriteArrayList<>();
        final List<ObservationKind> kinds = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();

        RecordingTrigger() {
            super(null); // the synthesizer is never used by this override
        }

        @Override
        public SynthesisOutcome onObservation(SessionId sessionId, ObservationKind kind) {
            sessions.add(sessionId);
            kinds.add(kind);
            threads.add(Thread.currentThread().getName());
            return null;
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
    void dispatchesTriggeringObservationToTheTriggerOffTheCallerThread() {
        RecordingTrigger trigger = new RecordingTrigger();
        String callerThread = Thread.currentThread().getName();
        try (ConsolidationObservationListener listener =
                new ConsolidationObservationListener(trigger)) {
            SessionId session = SessionId.newId();

            listener.accept(observation(session, ObservationKind.SESSION_END));

            // Dispatch is asynchronous (off the worker): the trigger is invoked shortly, on the
            // dedicated synthesis thread, with the right session id and kind. All three recordings are
            // asserted INSIDE the await — RecordingTrigger writes sessions, then kinds, then threads as
            // separate (non-atomic) steps, so asserting threads outside the await could observe it
            // before the third write lands (a flaky race).
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(trigger.sessions).containsExactly(session);
                assertThat(trigger.kinds).containsExactly(ObservationKind.SESSION_END);
                assertThat(trigger.threads).hasSize(1);
                assertThat(trigger.threads.get(0))
                        .as("synthesis runs off the calling (ingest worker) thread")
                        .isNotEqualTo(callerThread)
                        .startsWith("agent-memory-consolidation");
            });
        }
    }

    @Test
    void ignoresNonTriggeringKindsWithoutTouchingTheTrigger() throws Exception {
        RecordingTrigger trigger = new RecordingTrigger();
        try (ConsolidationObservationListener listener =
                new ConsolidationObservationListener(trigger)) {

            // USER_PROMPT is not a consolidation trigger: the listener filters it on the calling thread
            // and never dispatches, so the trigger is never invoked.
            listener.accept(observation(SessionId.newId(), ObservationKind.USER_PROMPT));
            listener.accept(observation(SessionId.newId(), ObservationKind.POST_TOOL_USE));

            // Give any (erroneous) dispatch a chance to run before asserting nothing happened.
            Thread.sleep(200);
            assertThat(trigger.kinds).isEmpty();
            assertThat(trigger.sessions).isEmpty();
        }
    }

    @Test
    void dispatchesEachTriggeringKind() {
        RecordingTrigger trigger = new RecordingTrigger();
        try (ConsolidationObservationListener listener =
                new ConsolidationObservationListener(trigger)) {

            listener.accept(observation(SessionId.newId(), ObservationKind.SESSION_END));
            listener.accept(observation(SessionId.newId(), ObservationKind.PRE_COMPACT));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(trigger.kinds).containsExactlyInAnyOrder(
                            ObservationKind.SESSION_END, ObservationKind.PRE_COMPACT));
        }
    }

    @Test
    void aNullObservationIsIgnored() throws Exception {
        RecordingTrigger trigger = new RecordingTrigger();
        try (ConsolidationObservationListener listener =
                new ConsolidationObservationListener(trigger)) {
            listener.accept(null); // must not throw, must not dispatch
            Thread.sleep(100);
            assertThat(trigger.kinds).isEmpty();
        }
    }
}
