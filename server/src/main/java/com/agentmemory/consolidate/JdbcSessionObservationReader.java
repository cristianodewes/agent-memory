package com.agentmemory.consolidate;

import com.agentmemory.core.Identity;
import com.agentmemory.core.Observation;
import com.agentmemory.core.ObservationId;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.Session;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.WorkspaceId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link JdbcTemplate}-backed {@link SessionObservationReader} over the {@code sessions} /
 * {@code observations} tables (issue #18 read side). Reads are {@code readOnly}; observations are
 * pulled via the {@code observations_session_idx (session_id, created_at)} index in capture order.
 */
public class JdbcSessionObservationReader implements SessionObservationReader {

    private final JdbcTemplate jdbc;

    public JdbcSessionObservationReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Session> findSession(SessionId sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
        try {
            Session session = jdbc.queryForObject(
                    "SELECT id, workspace, project, agent, started_at, ended_at "
                            + "FROM sessions WHERE id = ?",
                    SESSION_MAPPER, sessionId.value());
            return Optional.ofNullable(session);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Observation> observationsFor(SessionId sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
        return jdbc.query(
                "SELECT id, session_id, workspace, project, kind, source_event, extension, payload, "
                        + "       created_at "
                        + "FROM observations WHERE session_id = ? "
                        + "ORDER BY created_at ASC, id ASC",
                OBSERVATION_MAPPER, sessionId.value());
    }

    @Override
    @Transactional(readOnly = true)
    public int countFor(SessionId sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM observations WHERE session_id = ?", Integer.class, sessionId.value());
        return n == null ? 0 : n;
    }

    // --- mapping -------------------------------------------------------------------------------

    private static final RowMapper<Session> SESSION_MAPPER = (rs, n) -> {
        var ended = rs.getTimestamp("ended_at");
        return new Session(
                new SessionId(rs.getObject("id", UUID.class)),
                Identity.ofProject(
                        WorkspaceId.of(rs.getString("workspace")),
                        ProjectId.of(rs.getString("project"))),
                rs.getString("agent"),
                rs.getTimestamp("started_at").toInstant(),
                ended == null ? null : ended.toInstant());
    };

    private static final RowMapper<Observation> OBSERVATION_MAPPER = (rs, n) -> new Observation(
            new ObservationId(rs.getObject("id", UUID.class)),
            new SessionId(rs.getObject("session_id", UUID.class)),
            Identity.ofProject(
                    WorkspaceId.of(rs.getString("workspace")),
                    ProjectId.of(rs.getString("project"))),
            ObservationKind.fromWire(rs.getString("kind")),
            rs.getString("source_event"),
            rs.getString("extension"),
            rs.getString("payload"),
            rs.getTimestamp("created_at").toInstant());
}
