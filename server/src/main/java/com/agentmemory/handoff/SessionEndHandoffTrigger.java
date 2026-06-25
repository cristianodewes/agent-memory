package com.agentmemory.handoff;

import com.agentmemory.core.Observation;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.recall.Scope;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens an LLM-written handoff when a {@code session-end} observation lands (ARCHITECTURE §3.4;
 * issue #22 acceptance: "session-end opens an LLM-written handoff"). Wired as the ingest pipeline's
 * post-write listener, so it runs on the single ingest worker thread <em>after</em> the event is
 * durably captured — off the HTTP hot path and outside the write transaction, which is where the
 * (slower) LLM call belongs.
 *
 * <p>Only {@link ObservationKind#SESSION_END} events trigger generation; every other kind is ignored
 * cheaply. A generation failure is swallowed (logged): the capture already succeeded, and a client
 * can still open a handoff explicitly via {@code POST /handoff} / {@code memory_handoff_begin}, so a
 * transient LLM hiccup must not break ingest.
 */
public class SessionEndHandoffTrigger implements Consumer<Observation> {

    private static final Logger log = LoggerFactory.getLogger(SessionEndHandoffTrigger.class);

    private final HandoffService handoffs;

    public SessionEndHandoffTrigger(HandoffService handoffs) {
        this.handoffs = handoffs;
    }

    @Override
    public void accept(Observation observation) {
        if (observation == null || observation.kind() != ObservationKind.SESSION_END) {
            return;
        }
        Scope scope = new Scope(observation.identity().workspace(), observation.identity().project());
        try {
            handoffs.begin(scope, observation.sessionId());
        } catch (RuntimeException e) {
            // Non-fatal: capture is durable; the next session can still begin a handoff explicitly.
            log.warn("session-end handoff generation failed for {}/{} session {}: {}",
                    scope.workspaceSlug(), scope.projectSlug(), observation.sessionId().value(),
                    e.toString());
        }
    }
}
