package com.agentmemory.store;

import com.agentmemory.core.Handoff;
import com.agentmemory.core.HandoffId;
import com.agentmemory.core.Identity;
import com.agentmemory.core.HandoffStatus;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.Uuid7;
import com.agentmemory.recall.Scope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link JdbcTemplate}-backed {@link HandoffRepository} over the {@code handoffs} table (issue #22).
 *
 * <p><strong>Single open per project (single-writer, DD-006).</strong> {@link #open} and the
 * accept/cancel mutations take a transaction-scoped Postgres advisory lock keyed by
 * {@code (workspace, project)} so two concurrent opens for the same project serialize — the first
 * supersedes (expires) any prior open handoff, the second supersedes the first — and the
 * {@code handoffs_one_open_per_project} partial unique index is the hard backstop. We expire the
 * prior open row <em>before</em> inserting the new open row so that index is never transiently
 * violated.
 *
 * <p><strong>Single-use.</strong> {@link #acceptLatestOpen} flips the open row to {@code accepted}
 * and stamps {@code accepted_at} in one statement; because it only ever matches {@code status='open'},
 * a second accept finds nothing and returns empty.
 */
public class JdbcHandoffRepository implements HandoffRepository {

    private final JdbcTemplate jdbc;

    public JdbcHandoffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Handoff open(Scope scope, SessionId fromSession, String summary,
            List<String> openQuestions, List<String> nextSteps) {
        requireArgs(scope, fromSession, summary, openQuestions, nextSteps);
        String workspace = scope.workspaceSlug();
        String project = scope.projectSlug();

        lockProject(workspace, project);
        UUID workspaceId = getOrCreateWorkspace(workspace);
        UUID projectId = getOrCreateProject(workspaceId, workspace, project);

        // Supersede any currently-open handoff (expire it) BEFORE inserting the new one, so the
        // one-open-per-project partial index is never transiently violated.
        jdbc.update(
                "UPDATE handoffs SET status = 'expired' "
                        + "WHERE workspace = ? AND project = ? AND status = 'open'",
                workspace, project);

        UUID id = Uuid7.randomUuid();
        Instant now = Instant.now();
        // Bind the two list fields as Postgres array literals cast to text[] (?::text[]). This avoids
        // opening a side connection to build a java.sql.Array, so everything stays on the transaction's
        // connection.
        return jdbc.queryForObject(
                "INSERT INTO handoffs (id, workspace_id, project_id, workspace, project, from_session, "
                        + " status, summary, open_questions, next_steps, created_at, accepted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'open', ?, ?::text[], ?::text[], ?, NULL) "
                        + "RETURNING " + COLUMNS,
                HANDOFF_MAPPER,
                id, workspaceId, projectId, workspace, project, fromSession.value(),
                summary, arrayLiteral(openQuestions), arrayLiteral(nextSteps), Timestamp.from(now));
    }

    @Override
    @Transactional
    public Optional<Handoff> acceptLatestOpen(Scope scope) {
        requireScope(scope);
        String workspace = scope.workspaceSlug();
        String project = scope.projectSlug();
        lockProject(workspace, project);

        // Accept the (single) open handoff: flip to accepted + stamp accepted_at, atomically. Only
        // matches status='open', so a second call accepts nothing — single-use.
        List<Handoff> accepted = jdbc.query(
                "UPDATE handoffs SET status = 'accepted', accepted_at = ? "
                        + "WHERE id = ( "
                        + "  SELECT id FROM handoffs "
                        + "  WHERE workspace = ? AND project = ? AND status = 'open' "
                        + "  ORDER BY created_at DESC LIMIT 1 "
                        + ") "
                        + "RETURNING " + COLUMNS,
                HANDOFF_MAPPER,
                Timestamp.from(Instant.now()), workspace, project);
        return accepted.stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<Handoff> cancelLatestOpen(Scope scope) {
        requireScope(scope);
        String workspace = scope.workspaceSlug();
        String project = scope.projectSlug();
        lockProject(workspace, project);

        List<Handoff> expired = jdbc.query(
                "UPDATE handoffs SET status = 'expired' "
                        + "WHERE id = ( "
                        + "  SELECT id FROM handoffs "
                        + "  WHERE workspace = ? AND project = ? AND status = 'open' "
                        + "  ORDER BY created_at DESC LIMIT 1 "
                        + ") "
                        + "RETURNING " + COLUMNS,
                HANDOFF_MAPPER,
                workspace, project);
        return expired.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Handoff> findLatestOpen(Scope scope) {
        requireScope(scope);
        List<Handoff> open = jdbc.query(
                "SELECT " + COLUMNS + " FROM handoffs "
                        + "WHERE workspace = ? AND project = ? AND status = 'open' "
                        + "ORDER BY created_at DESC LIMIT 1",
                HANDOFF_MAPPER,
                scope.workspaceSlug(), scope.projectSlug());
        return open.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Handoff> findById(HandoffId id) {
        if (id == null) {
            throw new IllegalArgumentException("handoff id must not be null");
        }
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + COLUMNS + " FROM handoffs WHERE id = ?", HANDOFF_MAPPER, id.value()));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<ObservationLine> sessionObservations(SessionId session, int limit) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        // Take the most recent `limit` observations, then return them oldest-first so the LLM reads
        // the session in chronological order.
        return jdbc.query(
                "SELECT kind, payload, created_at FROM ( "
                        + "  SELECT kind, payload, created_at FROM observations "
                        + "  WHERE session_id = ? ORDER BY created_at DESC, id DESC LIMIT ? "
                        + ") recent ORDER BY created_at ASC",
                (rs, n) -> new ObservationLine(
                        rs.getString("kind"),
                        rs.getString("payload"),
                        rs.getTimestamp("created_at").toInstant().toString()),
                session.value(), limit);
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Serialize concurrent handoff mutations for one project (different projects run in parallel). */
    private void lockProject(String workspace, String project) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtext('handoff' || chr(31) || ? || chr(31) || ?))",
                (ResultSetExtractor<Void>) rs -> { rs.next(); return null; },
                workspace, project);
    }

    /**
     * Encode a list of strings as a Postgres array literal (e.g. {@code {"a","b\"c"}}) for binding as
     * {@code ?::text[]}. Each element is double-quoted with {@code "} and {@code \} backslash-escaped,
     * so any payload text (commas, braces, quotes) round-trips safely. An empty list yields
     * {@code {}}.
     */
    private static String arrayLiteral(List<String> values) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"');
            String v = values.get(i) == null ? "" : values.get(i);
            for (int j = 0; j < v.length(); j++) {
                char c = v.charAt(j);
                if (c == '"' || c == '\\') {
                    sb.append('\\');
                }
                sb.append(c);
            }
            sb.append('"');
        }
        return sb.append('}').toString();
    }

    private UUID getOrCreateWorkspace(String slug) {
        jdbc.update(
                "INSERT INTO workspaces (id, slug) VALUES (?, ?) ON CONFLICT (slug) DO NOTHING",
                Uuid7.randomUuid(), slug);
        return jdbc.queryForObject("SELECT id FROM workspaces WHERE slug = ?", UUID.class, slug);
    }

    private UUID getOrCreateProject(UUID workspaceId, String workspace, String slug) {
        jdbc.update(
                "INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (workspace, slug) DO NOTHING",
                Uuid7.randomUuid(), workspaceId, workspace, slug);
        return jdbc.queryForObject(
                "SELECT id FROM projects WHERE workspace = ? AND slug = ?", UUID.class, workspace, slug);
    }

    private static void requireScope(Scope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
    }

    private static void requireArgs(Scope scope, SessionId fromSession, String summary,
            List<String> openQuestions, List<String> nextSteps) {
        requireScope(scope);
        if (fromSession == null) {
            throw new IllegalArgumentException("fromSession must not be null");
        }
        if (summary == null) {
            throw new IllegalArgumentException("summary must not be null");
        }
        if (openQuestions == null || nextSteps == null) {
            throw new IllegalArgumentException("openQuestions/nextSteps must not be null (use empty list)");
        }
    }

    // --- mapping -------------------------------------------------------------------------------

    private static final String COLUMNS =
            "id, workspace, project, from_session, status, summary, open_questions, next_steps, "
                    + "created_at, accepted_at";

    private static final RowMapper<Handoff> HANDOFF_MAPPER = (rs, rowNum) -> {
        Timestamp acceptedAt = rs.getTimestamp("accepted_at");
        return new Handoff(
                new HandoffId(rs.getObject("id", UUID.class)),
                Identity.ofProject(
                        com.agentmemory.core.WorkspaceId.of(rs.getString("workspace")),
                        com.agentmemory.core.ProjectId.of(rs.getString("project"))),
                new SessionId(rs.getObject("from_session", UUID.class)),
                HandoffStatus.fromWire(rs.getString("status")),
                rs.getString("summary"),
                toList(rs.getArray("open_questions")),
                toList(rs.getArray("next_steps")),
                rs.getTimestamp("created_at").toInstant(),
                acceptedAt == null ? null : acceptedAt.toInstant());
    };

    private static List<String> toList(java.sql.Array array) {
        if (array == null) {
            return List.of();
        }
        try {
            Object raw = array.getArray();
            if (raw instanceof String[] strings) {
                return List.of(strings);
            }
            Object[] objs = (Object[]) raw;
            return java.util.Arrays.stream(objs).map(o -> o == null ? "" : o.toString()).toList();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("failed to read text[] from handoff row", e);
        }
    }
}
