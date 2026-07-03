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
import com.agentmemory.llm.LlmException;
import com.agentmemory.llm.LlmRateLimitGate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link ConsolidationObservationListener}: as an ingest post-write
 * {@code Consumer<Observation>}, it forwards a <em>triggering</em> observation's session id and kind to
 * the {@link SessionConsolidationTrigger}, and it does so <strong>off the calling thread</strong> (the
 * ingest worker) on its dedicated synthesis executor — the LLM-backed synthesis must never run inline
 * on the worker (invariant #5). Non-triggering kinds are filtered cheaply on the calling thread and
 * never reach the trigger. Since issue #148 the dispatcher is also rate-limit aware: a retryable LLM
 * failure is re-dispatched after a deferral (instead of dropped), a paused {@link LlmRateLimitGate}
 * defers the run entirely, and a permanent failure is still dropped. Uses a recording trigger
 * subclass; no Spring, no DB, no LLM.
 */
class ConsolidationObservationListenerTest {

    /**
     * A trigger that records the (sessionId, kind) pairs it is asked to react to, and the thread it ran
     * on (to prove the dispatch is off the caller's thread). Overrides
     * {@link #onObservationOrThrow} — the variant the listener dispatches through — and can be told to
     * fail its first N runs to drive the retry paths.
     */
    private static final class RecordingTrigger extends SessionConsolidationTrigger {
        final List<SessionId> sessions = new CopyOnWriteArrayList<>();
        final List<ObservationKind> kinds = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresRemaining = new AtomicInteger();
        volatile RuntimeException failure;

        RecordingTrigger() {
            super(null); // the synthesizer is never used by this override
        }

        @Override
        public SynthesisOutcome onObservationOrThrow(SessionId sessionId, ObservationKind kind) {
            sessions.add(sessionId);
            kinds.add(kind);
            threads.add(Thread.currentThread().getName());
            if (failuresRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw failure;
            }
            return null;
        }
    }

    /** A listener with test-fast retry timing (base/floor/jitter of a few ms). */
    private static ConsolidationObservationListener fastListener(
            RecordingTrigger trigger, LlmRateLimitGate gate) {
        return new ConsolidationObservationListener(trigger, gate, 5, 1, 1);
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

    private static LlmException retryable429() {
        return new LlmException("stub request failed with HTTP 429: usage_limit_reached",
                null, true, 429, 0);
    }

    @Test
    void dispatchesTriggeringObservationToTheTriggerOffTheCallerThread() {
        RecordingTrigger trigger = new RecordingTrigger();
        String callerThread = Thread.currentThread().getName();
        try (ConsolidationObservationListener listener =
                new ConsolidationObservationListener(trigger, new LlmRateLimitGate())) {
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
                new ConsolidationObservationListener(trigger, new LlmRateLimitGate())) {

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
                new ConsolidationObservationListener(trigger, new LlmRateLimitGate())) {

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
                new ConsolidationObservationListener(trigger, new LlmRateLimitGate())) {
            listener.accept(null); // must not throw, must not dispatch
            Thread.sleep(100);
            assertThat(trigger.kinds).isEmpty();
        }
    }

    @Test
    void aRetryableFailureIsRedispatchedUntilItSucceeds() {
        // Issue #148: a 429 during an exhausted quota window used to be dropped with a "will retry on
        // next trigger" that never comes for a session's final event. Now the same task re-dispatches
        // after a deferral until it succeeds (here: fails twice, succeeds on the third run).
        RecordingTrigger trigger = new RecordingTrigger();
        trigger.failure = retryable429();
        trigger.failuresRemaining.set(2);
        try (ConsolidationObservationListener listener =
                fastListener(trigger, new LlmRateLimitGate())) {
            SessionId session = SessionId.newId();

            listener.accept(observation(session, ObservationKind.SESSION_END));

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(trigger.sessions).hasSize(3).containsOnly(session));
        }
    }

    @Test
    void aPermanentFailureIsNotRetried() throws Exception {
        RecordingTrigger trigger = new RecordingTrigger();
        trigger.failure = new IllegalStateException("malformed LLM reply"); // not retryable
        trigger.failuresRemaining.set(1);
        try (ConsolidationObservationListener listener =
                fastListener(trigger, new LlmRateLimitGate())) {

            listener.accept(observation(SessionId.newId(), ObservationKind.SESSION_END));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(trigger.sessions).hasSize(1));
            Thread.sleep(200); // a (wrong) retry would land within a few ms at test timing
            assertThat(trigger.sessions).hasSize(1);
        }
    }

    @Test
    void aPausedGateDefersTheRunInsteadOfBurningACall() {
        // The gate says the quota window is exhausted for a short while: the run must not reach the
        // trigger during the pause, and must fire (exactly once) after the pause lifts.
        LlmRateLimitGate gate = new LlmRateLimitGate();
        gate.recordFailure(new LlmException("HTTP 429", null, true, 429, 800)); // paused ~800ms
        RecordingTrigger trigger = new RecordingTrigger();
        try (ConsolidationObservationListener listener = fastListener(trigger, gate)) {
            SessionId session = SessionId.newId();

            listener.accept(observation(session, ObservationKind.SESSION_END));

            assertThat(trigger.sessions).as("no synthesis while the gate is paused").isEmpty();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(trigger.sessions).containsExactly(session));
        }
    }

    @Test
    void retriesGiveUpAfterMaxAttempts() throws Exception {
        // A quota window that never reopens must not retry forever: MAX_ATTEMPTS bounds the loop.
        RecordingTrigger trigger = new RecordingTrigger();
        trigger.failure = retryable429();
        trigger.failuresRemaining.set(Integer.MAX_VALUE);
        try (ConsolidationObservationListener listener =
                fastListener(trigger, new LlmRateLimitGate())) {

            listener.accept(observation(SessionId.newId(), ObservationKind.SESSION_END));

            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(trigger.sessions).hasSize(ConsolidationObservationListener.MAX_ATTEMPTS));
            Thread.sleep(300); // no further attempts after the cap
            assertThat(trigger.sessions).hasSize(ConsolidationObservationListener.MAX_ATTEMPTS);
        }
    }
}
