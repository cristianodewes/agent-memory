package com.agentmemory.store;

import com.agentmemory.core.Identity;
import com.agentmemory.core.Observation;
import com.agentmemory.core.ObservationId;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.Uuid7;
import com.agentmemory.hooks.NewObservation;
import com.agentmemory.hooks.Sanitized;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Postgres-backed {@link ObservationWriter}: the <strong>single writer</strong> (ARCHITECTURE
 * invariant #2) for the capture log. Every accepted hook event reaches Postgres through exactly this
 * path, which persists three things in one transaction:
 *
 * <ol>
 *   <li>an upsert of the {@code (workspace, project)} identity rows (so a first-seen project is
 *       created on the fly and a {@code session}/{@code observation} FK always resolves);</li>
 *   <li>an upsert of the {@code session} row (created on first event, its {@code ended_at} stamped
 *       when a {@code session-end} arrives);</li>
 *   <li>the {@code observation} row itself, plus an {@code audit_log} entry recording the mutation
 *       with its identity tuple (issue #8 acceptance: every accepted event appears in both).</li>
 * </ol>
 *
 * <h2>Single-writer discipline</h2>
 * All work is serialized through one {@link ReentrantLock} so concurrent posts cannot interleave and
 * corrupt rows (invariant #2; issue #8 acceptance). The lock is process-local; the database
 * constraints are the cross-process backstop. The whole sequence runs in one {@code @Transactional}
 * unit so a mid-sequence failure rolls back cleanly (no orphan session/audit without its
 * observation).
 *
 * <h2>Idempotency</h2>
 * The observation insert is {@code ON CONFLICT (session_id, client_event_id) DO NOTHING}. When a
 * retried spool drain re-sends an event carrying the same {@code clientEventId}, the insert is a
 * no-op and this writer returns the <em>already-stored</em> row without writing a duplicate
 * observation or a second audit entry (issue #8 acceptance: replaying a batch creates no
 * duplicates). An event with a {@code null} {@code clientEventId} is always inserted (the partial
 * unique index excludes nulls), so callers that cannot supply a stable id still work — they simply
 * forgo dedupe.
 *
 * <h2>Privacy boundary</h2>
 * The single parameter is a {@link Sanitized}{@code <NewObservation>}; only
 * {@code com.agentmemory.hooks.Sanitizer} can produce one, so it is impossible to persist text that
 * was not privacy-stripped (DD-010 / invariant #6). The structural id/extension fields are written
 * as-is; only {@code payload} was rewritten upstream.
 */
public final class PostgresObservationWriter implements ObservationWriter {

    private static final Logger log = LoggerFactory.getLogger(PostgresObservationWriter.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    /**
     * Serializes every {@link #append} so writes never interleave (invariant #2). The lock is held
     * across the whole transaction (commit included), so two appends can never be in-flight at once
     * regardless of how many threads call in. Fair ordering keeps a high-throughput drain from
     * starving an interleaved single post.
     */
    private final ReentrantLock writeLock = new ReentrantLock(true);

    /**
     * @param jdbc the JDBC access template bound to the single application {@code DataSource}.
     * @param tx   a transaction template over the same {@code DataSource}; the multi-statement write
     *     runs inside it so a mid-sequence failure rolls back the session/observation/audit together.
     */
    public PostgresObservationWriter(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public Observation append(Sanitized<NewObservation> observation) {
        if (observation == null) {
            throw new IllegalArgumentException("cannot append a null sanitized observation");
        }
        NewObservation obs = observation.value();
        Identity identity = obs.identity();
        String workspace = identity.workspace().value();
        String project = identity.project().value();

        // Hold the lock across the transaction body AND its commit, so writes are strictly serialized
        // (invariant #2) — not merely started serially. The DB unique index is the cross-process
        // backstop; this lock is the in-process guarantee.
        writeLock.lock();
        try {
            return tx.execute(status -> {
                UUID workspaceId = upsertWorkspace(workspace);
                UUID projectId = upsertProject(workspaceId, workspace, project);
                upsertSession(obs, workspaceId, projectId, workspace, project);

                UUID observationId = Uuid7.randomUuid();
                boolean inserted =
                        insertObservation(obs, observationId, workspaceId, projectId, workspace, project);

                if (inserted) {
                    writeAudit(observationId, workspace, project, obs);
                    return toDomain(observationId, obs);
                }
                // Idempotent replay: the (session_id, client_event_id) row already exists. Return the
                // stored observation unchanged — no duplicate row, no second audit entry.
                log.debug(
                        "idempotent replay: observation for session={} clientEventId={} already stored",
                        obs.sessionId(), obs.clientEventId());
                return loadExisting(obs);
            });
        } finally {
            writeLock.unlock();
        }
    }

    // --- identity upserts ----------------------------------------------------------------------

    /** Resolve (creating if absent) the workspace row, returning its surrogate id. */
    private UUID upsertWorkspace(String workspace) {
        // ON CONFLICT touches updated_at so the RETURNING clause yields the id on both insert and
        // conflict (a bare DO NOTHING returns no row on conflict).
        return jdbc.queryForObject(
                "INSERT INTO workspaces (id, slug) VALUES (?, ?) "
                        + "ON CONFLICT (slug) DO UPDATE SET updated_at = now() "
                        + "RETURNING id",
                UUID.class,
                Uuid7.randomUuid(), workspace);
    }

    /** Resolve (creating if absent) the project row under {@code workspaceId}, returning its id. */
    private UUID upsertProject(UUID workspaceId, String workspace, String project) {
        return jdbc.queryForObject(
                "INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (workspace, slug) DO UPDATE SET updated_at = now() "
                        + "RETURNING id",
                UUID.class,
                Uuid7.randomUuid(), workspaceId, workspace, project);
    }

    /**
     * Upsert the session row. The session id is client-supplied (it groups a run's events), so the
     * first event for a session creates it with {@code started_at = createdAt}; later events are a
     * no-op except a {@code session-end}, which stamps {@code ended_at} (coalesced so an out-of-order
     * or duplicate end does not move an already-set time).
     */
    private void upsertSession(
            NewObservation obs, UUID workspaceId, UUID projectId, String workspace, String project) {
        UUID sessionId = obs.sessionId().value();
        Instant createdAt = obs.createdAt();
        boolean isEnd = obs.kind() == ObservationKind.SESSION_END;
        jdbc.update(
                "INSERT INTO sessions (id, workspace_id, project_id, workspace, project, started_at, ended_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET ended_at = COALESCE(sessions.ended_at, EXCLUDED.ended_at)",
                sessionId,
                workspaceId,
                projectId,
                workspace,
                project,
                java.sql.Timestamp.from(createdAt),
                isEnd ? java.sql.Timestamp.from(createdAt) : null);
    }

    // --- observation + audit -------------------------------------------------------------------

    /**
     * Insert the observation, deduping on {@code (session_id, client_event_id)}. Returns {@code true}
     * if a new row was written, {@code false} if it was an idempotent replay (conflict).
     */
    private boolean insertObservation(
            NewObservation obs,
            UUID observationId,
            UUID workspaceId,
            UUID projectId,
            String workspace,
            String project) {
        // ON CONFLICT DO NOTHING ... RETURNING id yields a row only when the insert actually happened;
        // an empty result means the unique (session_id, client_event_id) already existed.
        // The conflict arbiter must repeat the partial index predicate (WHERE client_event_id IS NOT
        // NULL) so Postgres matches observations_client_event_id_unique. A null client_event_id never
        // matches that partial index, so such an event is always inserted (no dedupe), as intended.
        List<UUID> ids = jdbc.query(
                "INSERT INTO observations "
                        + "(id, session_id, workspace_id, project_id, workspace, project, "
                        + " kind, source_event, extension, client_event_id, payload, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (session_id, client_event_id) WHERE client_event_id IS NOT NULL "
                        + "DO NOTHING "
                        + "RETURNING id",
                (rs, rowNum) -> (UUID) rs.getObject("id"),
                observationId,
                obs.sessionId().value(),
                workspaceId,
                projectId,
                workspace,
                project,
                obs.kind().wire(),
                obs.sourceEvent(),
                obs.extension(),
                obs.clientEventId(),
                obs.payload(),
                java.sql.Timestamp.from(obs.createdAt()));
        return !ids.isEmpty();
    }

    /** Record the mutation in {@code audit_log} with the identity tuple (issue #8 acceptance). */
    private void writeAudit(UUID observationId, String workspace, String project, NewObservation obs) {
        // detail is structured jsonb; keep it small and machine-readable (kind + raw event name).
        String detail = "{\"kind\":" + jsonString(obs.kind().wire())
                + ",\"sourceEvent\":" + jsonString(obs.sourceEvent()) + "}";
        jdbc.update(
                "INSERT INTO audit_log (id, workspace, project, action, entity_type, entity_id, detail) "
                        + "VALUES (?, ?, ?, 'observation.append', 'observation', ?, CAST(? AS jsonb))",
                Uuid7.randomUuid(), workspace, project, observationId, detail);
    }

    /**
     * Load the observation that already existed for this {@code (session_id, client_event_id)} on an
     * idempotent replay, mapping it back to the domain record. {@code clientEventId} is non-null here
     * because a null key never conflicts (it is always inserted), so this path is only reached with a
     * real key.
     */
    private Observation loadExisting(NewObservation obs) {
        return jdbc.queryForObject(
                "SELECT id, kind, source_event, extension, payload, created_at "
                        + "FROM observations WHERE session_id = ? AND client_event_id = ?",
                (rs, rowNum) -> new Observation(
                        new ObservationId((UUID) rs.getObject("id")),
                        obs.sessionId(),
                        obs.identity(),
                        ObservationKind.fromWire(rs.getString("kind")),
                        rs.getString("source_event"),
                        rs.getString("extension"),
                        rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant()),
                obs.sessionId().value(),
                obs.clientEventId());
    }

    /** Build the persisted domain record for a freshly-inserted observation. */
    private static Observation toDomain(UUID observationId, NewObservation obs) {
        return new Observation(
                new ObservationId(observationId),
                obs.sessionId(),
                obs.identity(),
                obs.kind(),
                obs.sourceEvent(),
                obs.extension(),
                obs.payload(),
                obs.createdAt());
    }

    /** Minimal JSON string encoder for the small audit {@code detail} tokens (or {@code null}). */
    private static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
