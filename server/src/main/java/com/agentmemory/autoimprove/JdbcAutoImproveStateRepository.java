package com.agentmemory.autoimprove;

import com.agentmemory.core.SessionId;
import com.agentmemory.recall.Scope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * {@link JdbcTemplate}-backed scheduler state for the auto-improve loop (issue #30): the per-project
 * first-run watermark and per-session review claims (V14). These are what make the scheduler safe to run
 * repeatedly — it never retro-reviews history, never double-reviews a session, and gives up after a
 * bounded number of attempts.
 */
public class JdbcAutoImproveStateRepository {

    private final JdbcTemplate jdbc;

    public JdbcAutoImproveStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The project's first-run watermark, establishing it (at {@code now()}) on first call. Sessions
     * finished at/before this instant are not auto-reviewed, so enabling auto-improve on an existing
     * project doesn't retro-review its whole history.
     *
     * @return the established cutoff (stable across calls).
     */
    public Instant watermark(Scope scope) {
        jdbc.update(
                "INSERT INTO auto_improve_watermark (workspace, project) VALUES (?, ?) "
                        + "ON CONFLICT (workspace, project) DO NOTHING",
                scope.workspaceSlug(), scope.projectSlug());
        Timestamp ts = jdbc.queryForObject(
                "SELECT established_at FROM auto_improve_watermark WHERE workspace = ? AND project = ?",
                Timestamp.class, scope.workspaceSlug(), scope.projectSlug());
        return ts.toInstant();
    }

    /**
     * Establish the first-run watermark for every project that has at least one finished session, once
     * (idempotent). Run at the start of a tick so a project seen for the first time gets its cutoff at
     * {@code now()} and its pre-existing history is not retro-reviewed; projects that already have a
     * watermark are left untouched. A single bulk statement — the scheduler does not enumerate projects.
     */
    public void establishWatermarks() {
        jdbc.update(
                "INSERT INTO auto_improve_watermark (workspace, project) "
                        + "SELECT DISTINCT workspace, project FROM sessions WHERE ended_at IS NOT NULL "
                        + "ON CONFLICT (workspace, project) DO NOTHING");
    }

    /**
     * The finished sessions due for review across all projects: those finished strictly after their
     * project's {@link #establishWatermarks() watermark} that are either not yet reviewed or are
     * {@code failed} but still under the attempt cap (so a permanently-failing session stops being fed).
     * Oldest-finished first, capped at {@code limit}. {@code claimed}/{@code done} sessions are excluded,
     * so concurrent ticks don't double-feed and a completed review is never re-fed.
     *
     * @param maxAttempts the attempt cap that gates retry of {@code failed} sessions.
     * @param limit       the most sessions to return (a tick's bounded work).
     * @return due sessions, oldest finished first.
     */
    public List<DueSession> dueSessions(int maxAttempts, int limit) {
        return jdbc.query(
                "SELECT s.id, s.workspace, s.project "
                        + "FROM sessions s "
                        + "JOIN auto_improve_watermark w "
                        + "  ON w.workspace = s.workspace AND w.project = s.project "
                        + "LEFT JOIN auto_improve_session_review r ON r.session_id = s.id "
                        + "WHERE s.ended_at IS NOT NULL "
                        + "  AND s.ended_at > w.established_at "
                        + "  AND (r.session_id IS NULL OR (r.status = 'failed' AND r.attempts < ?)) "
                        + "ORDER BY s.ended_at ASC "
                        + "LIMIT ?",
                DUE_MAPPER, maxAttempts, Math.max(1, limit));
    }

    private static final RowMapper<DueSession> DUE_MAPPER = (rs, n) -> new DueSession(
            Scope.of(rs.getString("workspace"), rs.getString("project")),
            new SessionId(rs.getObject("id", UUID.class)));

    /**
     * Atomically claim a session for review. A brand-new session is claimed; an already-{@code done}
     * session is never re-claimed; a previously-{@code claimed}/{@code failed} session is re-claimed only
     * while under the attempt cap (so a permanently-failing session stops retrying).
     *
     * @return {@code true} if the caller acquired the claim and should review the session.
     */
    public boolean claim(Scope scope, SessionId session, int maxAttempts) {
        List<Integer> attempts = jdbc.query(
                "INSERT INTO auto_improve_session_review "
                        + "(session_id, workspace, project, status, attempts) "
                        + "VALUES (?, ?, ?, 'claimed', 1) "
                        + "ON CONFLICT (session_id) DO UPDATE SET "
                        + "attempts = auto_improve_session_review.attempts + 1, "
                        + "claimed_at = now(), status = 'claimed' "
                        + "WHERE auto_improve_session_review.status <> 'done' "
                        + "AND auto_improve_session_review.attempts < ? "
                        + "RETURNING attempts",
                (rs, n) -> rs.getInt("attempts"),
                session.value(), scope.workspaceSlug(), scope.projectSlug(), maxAttempts);
        return !attempts.isEmpty();
    }

    /** Mark a claimed session's review finished successfully (terminal — never re-reviewed). */
    public void markDone(SessionId session) {
        jdbc.update(
                "UPDATE auto_improve_session_review SET status = 'done', finished_at = now(), "
                        + "last_error = NULL WHERE session_id = ?",
                session.value());
    }

    /** Mark a claimed session's review failed (retryable while under the attempt cap), recording why. */
    public void markFailed(SessionId session, String error) {
        jdbc.update(
                "UPDATE auto_improve_session_review SET status = 'failed', finished_at = now(), "
                        + "last_error = ? WHERE session_id = ?",
                error, session.value());
    }
}
