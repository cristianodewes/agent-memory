package com.agentmemory.core;

/**
 * Resolves the authenticated principal name — the <em>actor</em> — for the current request thread, so
 * a mutation can be attributed in the {@code audit_log} / {@code observations} {@code actor} column
 * (issue #39). The actor is a {@link UserId} slug in multi-user mode, the literal {@code "root"} in
 * single-user mode, or {@code null} when nothing authenticated (auth disabled, loopback, or an
 * anonymous request) — an unattributed write.
 *
 * <p><strong>Thread contract:</strong> this MUST be called on the thread that carries the security
 * context, i.e. the HTTP request thread. The async ingest worker ({@code agent-memory-ingest}) runs
 * with an empty {@code SecurityContext}, so the capture path resolves the actor at the controller
 * boundary and threads it explicitly through {@code NewObservation} rather than calling a resolver on
 * the worker. The synchronous lifecycle/forget paths write their audit row on the request thread, so
 * they resolve here directly.
 *
 * <p>Kept in {@code core} (no Spring dependency) so the low-level {@code store} writer can depend on
 * the interface; the {@link SecurityContextHolder}-backed implementation lives in {@code security}.
 */
@FunctionalInterface
public interface ActorResolver {

    /**
     * @return the current actor's name (a {@link UserId} slug, or {@code "root"}), or {@code null}
     *     when there is no authenticated, non-anonymous principal on this thread.
     */
    String currentActor();

    /** A resolver that never attributes — the default for auth-disabled and pure-unit contexts. */
    ActorResolver NONE = () -> null;
}
