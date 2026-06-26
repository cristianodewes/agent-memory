package com.agentmemory.autoimprove;

import com.agentmemory.core.Uuid7;
import com.agentmemory.recall.Scope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link JdbcTemplate}-backed store for self-improvement proposals over the pre-existing
 * {@code pending_writes} table (V8; issue #30). Owns the approval-gate row lifecycle — insert a proposal,
 * transition it to applied/rejected, attach the optional eval-gate (#31) result — and the read side for
 * the report. The {@code proposal}/{@code eval_result} columns are {@code jsonb}, written via a SQL cast
 * (matching {@code JdbcAuditWriter}).
 */
public class JdbcPendingWriteRepository {

    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    public JdbcPendingWriteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Record a proposed edit as {@code proposed} (the held/initial state).
     *
     * @return the new proposal's id.
     */
    public UUID propose(Scope scope, UUID sessionId, ProposedWrite write) {
        UUID id = Uuid7.randomUuid();
        jdbc.update(
                "INSERT INTO pending_writes "
                        + "(id, workspace, project, path, status, kind, proposal, rationale, session_id) "
                        + "VALUES (?, ?, ?, ?, 'proposed', ?, CAST(? AS jsonb), ?, ?)",
                id,
                scope.workspaceSlug(),
                scope.projectSlug(),
                write.path(),
                write.kind(),
                proposalJson(write),
                write.rationale(),
                sessionId);
        return id;
    }

    /** Mark a proposal {@code applied} (decided + applied now), attaching the optional eval result. */
    public void markApplied(UUID id, String evalResultJson) {
        jdbc.update(
                "UPDATE pending_writes SET status = 'applied', decided_at = now(), applied_at = now(), "
                        + "eval_result = CAST(? AS jsonb) WHERE id = ?",
                evalResultJson, id);
    }

    /** Mark a proposal {@code rejected} (decided now), attaching the optional eval result. */
    public void markRejected(UUID id, String evalResultJson) {
        jdbc.update(
                "UPDATE pending_writes SET status = 'rejected', decided_at = now(), "
                        + "eval_result = CAST(? AS jsonb) WHERE id = ?",
                evalResultJson, id);
    }

    /** The most recent proposals in a project (newest first) — the report surface. */
    public List<PendingWriteRecord> recent(Scope scope, int limit) {
        return jdbc.query(
                "SELECT * FROM pending_writes WHERE workspace = ? AND project = ? "
                        + "ORDER BY created_at DESC LIMIT ?",
                MAPPER, scope.workspaceSlug(), scope.projectSlug(), Math.max(1, limit));
    }

    public Optional<PendingWriteRecord> findById(UUID id) {
        return jdbc.query("SELECT * FROM pending_writes WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /**
     * The {@code kind|path} keys of proposals in a project that are still open or terminal-without-effect
     * — {@code proposed}, {@code approved} or {@code rejected}. The scope-level curator-action loop (#101)
     * uses this to stay quiescent: a finding whose corrective action is already pending (or was rejected)
     * is not re-proposed every tick. {@code applied} proposals are deliberately excluded — a forget /
     * link-fix that landed removes the finding, so it cannot recur; if it somehow does, re-proposing is
     * correct.
     *
     * @param scope the project.
     * @return the set of {@code kind + '|' + path} keys to skip; never null.
     */
    public Set<String> openActionKeys(Scope scope) {
        List<String> rows = jdbc.query(
                "SELECT kind, path FROM pending_writes "
                        + "WHERE workspace = ? AND project = ? AND path IS NOT NULL "
                        + "  AND status IN ('proposed', 'approved', 'rejected')",
                (rs, n) -> rs.getString("kind") + '|' + rs.getString("path"),
                scope.workspaceSlug(), scope.projectSlug());
        return new HashSet<>(rows);
    }

    private String proposalJson(ProposedWrite write) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("path", write.path());
        p.put("title", write.title());
        p.put("body", write.body());
        if (!write.params().isEmpty()) {
            p.put("params", write.params()); // action arguments (e.g. link.fix target) — #101
        }
        return json.writeValueAsString(p);
    }

    private static final RowMapper<PendingWriteRecord> MAPPER = (ResultSet rs, int rowNum) ->
            new PendingWriteRecord(
                    rs.getObject("id", UUID.class),
                    rs.getString("workspace"),
                    rs.getString("project"),
                    rs.getString("path"),
                    PendingWriteStatus.fromDb(rs.getString("status")),
                    rs.getString("kind"),
                    rs.getString("proposal"),
                    rs.getString("rationale"),
                    rs.getString("eval_result"),
                    rs.getObject("session_id", UUID.class),
                    instant(rs, "created_at"),
                    instant(rs, "decided_at"),
                    instant(rs, "applied_at"));

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
