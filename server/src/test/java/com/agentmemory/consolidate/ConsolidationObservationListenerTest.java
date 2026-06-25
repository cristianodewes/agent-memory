package com.agentmemory.consolidate;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.hooks.NewObservation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link ConsolidationObservationListener}: it forwards a written observation's
 * session id and kind to the {@link SessionConsolidationTrigger}, and does nothing else (the trigger
 * owns the which-kinds-consolidate policy). Uses a recording trigger subclass; no Spring, no DB, no
 * LLM.
 */
class ConsolidationObservationListenerTest {

    /** A trigger that records the (sessionId, kind) pairs it is asked to react to. */
    private static final class RecordingTrigger extends SessionConsolidationTrigger {
        final List<SessionId> sessions = new ArrayList<>();
        final List<ObservationKind> kinds = new ArrayList<>();

        RecordingTrigger() {
            super(null); // the synthesizer is never used by this override
        }

        @Override
        public SynthesisOutcome onObservation(SessionId sessionId, ObservationKind kind) {
            sessions.add(sessionId);
            kinds.add(kind);
            return null;
        }
    }

    private static NewObservation observation(SessionId session, ObservationKind kind) {
        return new NewObservation(
                session,
                Identity.ofProject(WorkspaceId.of("acme"), ProjectId.of("agent-memory")),
                kind,
                kind.wire(),
                null,
                null,
                "payload",
                Instant.parse("2026-06-25T12:00:00Z"));
    }

    @Test
    void forwardsSessionIdAndKindToTheTrigger() {
        RecordingTrigger trigger = new RecordingTrigger();
        ConsolidationObservationListener listener = new ConsolidationObservationListener(trigger);

        SessionId session = SessionId.newId();
        listener.onObservationWritten(observation(session, ObservationKind.SESSION_END));

        assertThat(trigger.sessions).containsExactly(session);
        assertThat(trigger.kinds).containsExactly(ObservationKind.SESSION_END);
    }

    @Test
    void forwardsEveryKindAndLetsTheTriggerDecide() {
        RecordingTrigger trigger = new RecordingTrigger();
        ConsolidationObservationListener listener = new ConsolidationObservationListener(trigger);

        // The adapter does not pre-filter — it forwards all kinds; the trigger no-ops non-triggers.
        listener.onObservationWritten(observation(SessionId.newId(), ObservationKind.USER_PROMPT));
        listener.onObservationWritten(observation(SessionId.newId(), ObservationKind.PRE_COMPACT));

        assertThat(trigger.kinds)
                .containsExactly(ObservationKind.USER_PROMPT, ObservationKind.PRE_COMPACT);
    }
}
