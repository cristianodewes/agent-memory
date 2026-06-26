package com.agentmemory.autoimprove;

import com.agentmemory.recall.Scope;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Scope-level state for the curator-action loop (issue #101): enumerates the projects the loop audits.
 * The curator is scope-level (it audits a whole project), so — unlike the per-finished-session
 * {@link AutoImproveScheduler}, whose work unit is a {@code sessions} row — this loop's work unit is a
 * <em>project that has pages</em>. Dedupe of already-pending actions is handled separately by
 * {@link JdbcPendingWriteRepository#openActionKeys}, keeping each row-owner the single writer of its table.
 */
public class CuratorActionRepository {

    private final JdbcTemplate jdbc;

    public CuratorActionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The distinct projects that currently have at least one live page — the universe a scope-level
     * curator audit considers. Ordered for stable iteration and capped so one tick's work is bounded.
     *
     * @param limit the most scopes to return.
     * @return the scopes to audit, possibly empty.
     */
    @Transactional(readOnly = true)
    public List<Scope> scopesWithPages(int limit) {
        return jdbc.query(
                "SELECT DISTINCT workspace, project FROM pages "
                        + "WHERE is_latest AND deleted_at IS NULL "
                        + "ORDER BY workspace, project LIMIT ?",
                (rs, n) -> Scope.of(rs.getString("workspace"), rs.getString("project")),
                Math.max(1, limit));
    }
}
