package com.agentmemory.hooks;

/**
 * A post-write hook invoked by {@link IngestService} <em>after</em> an observation has been persisted,
 * so a higher-level module can react to capture without the ingest path depending on it. This is the
 * seam that wires write-time side effects (issue #18 session consolidation, and any future
 * post-capture reaction) into the hot path while keeping {@code hooks} — a foundational module —
 * dependency-free: {@code hooks} declares this interface, and the reacting module
 * ({@code com.agentmemory.consolidate}) implements it and is injected as an optional collaborator.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Invoked once per successfully written observation, after {@code ObservationWriter.append}
 *       returns. {@link IngestService} dispatches the call on a dedicated executor — <em>off</em> the
 *       ingest worker thread — so a slow listener never blocks the queue drain or the agent's hot path
 *       (invariant #5). An implementation may therefore do expensive work (e.g. an LLM-backed
 *       synthesis) without throttling capture; it is run serially on a single background thread, and
 *       if the listener backlog saturates a notification is dropped (best-effort) rather than blocking.
 *       A listener that needs the failure surfaced (a manual/CLI invocation, a test) should call its
 *       underlying service directly instead of relying on this seam.</li>
 *   <li><strong>Best-effort / must not break ingest (invariant #5).</strong> An implementation should
 *       not throw; {@link IngestService} additionally guards the call and swallows any exception so a
 *       listener failure can never affect ingest.</li>
 *   <li>Receives the {@link NewObservation} (the typed, already-validated structure — session id,
 *       kind, identity); the free-text payload it carries has already passed the privacy sanitizer.</li>
 * </ul>
 */
@FunctionalInterface
public interface ObservationListener {

    /** A no-op listener — the default when no reacting module is wired. */
    ObservationListener NO_OP = observation -> { };

    /**
     * React to a just-persisted observation.
     *
     * @param observation the observation that was written; never null.
     */
    void onObservationWritten(NewObservation observation);
}
