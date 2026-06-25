package com.agentmemory.curate;

import com.agentmemory.core.MemoryLayer;
import com.agentmemory.recall.Scope;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The zero-cost SQL rules behind the curator (issue #29): cold episodic pages, stale slots, and
 * duplicate normalized titles. Pure reads over the {@code pages} table (latest, non-soft-deleted rows
 * only), {@code @Transactional(readOnly = true)}. The fourth rule — dangling cross-project links — is
 * served by the #28 {@code GraphService}, so it is not here.
 */
public class CuratorRepository {

    private final JdbcTemplate jdbc;

    public CuratorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Latest <em>episodic</em> pages that have gone cold: not accessed (nor created, if never accessed)
     * within {@code coldAfterDays}. Episodic memory is meant to age out, so a cold one is a forget /
     * consolidation candidate the curator surfaces.
     *
     * @param scope         the project.
     * @param coldAfterDays the age cutoff in days.
     * @return one finding per cold episodic page, ordered by path.
     */
    @Transactional(readOnly = true)
    public List<CuratorFinding> coldEpisodicPages(Scope scope, int coldAfterDays) {
        return jdbc.query(
                "SELECT path, "
                        + "  EXTRACT(DAY FROM (now() - COALESCE(last_accessed_at, created_at)))::int AS age_days "
                        + "FROM pages "
                        + "WHERE workspace = ? AND project = ? AND is_latest AND deleted_at IS NULL "
                        + "  AND layer = ? "
                        + "  AND COALESCE(last_accessed_at, created_at) < now() - make_interval(days => ?) "
                        + "ORDER BY path",
                (rs, n) -> new CuratorFinding(
                        CuratorRule.COLD_EPISODIC, rs.getString("path"),
                        "episodic page cold for " + rs.getInt("age_days") + "d (not accessed)"),
                scope.workspaceSlug(), scope.projectSlug(), MemoryLayer.EPISODIC.wire(), coldAfterDays);
    }

    /**
     * Latest {@code _slots/} pages not updated within {@code staleAfterDays}. A slot is a pinned,
     * long-lived page (#26); one that has not changed in a long time may be out of date.
     *
     * @param scope          the project.
     * @param staleAfterDays the age cutoff in days.
     * @return one finding per stale slot, ordered by path.
     */
    @Transactional(readOnly = true)
    public List<CuratorFinding> staleSlots(Scope scope, int staleAfterDays) {
        return jdbc.query(
                "SELECT path, EXTRACT(DAY FROM (now() - updated_at))::int AS age_days "
                        + "FROM pages "
                        + "WHERE workspace = ? AND project = ? AND is_latest AND deleted_at IS NULL "
                        // literal underscore: escape it so LIKE does not treat it as a wildcard.
                        + "  AND path LIKE '\\_slots/%' ESCAPE '\\' "
                        + "  AND updated_at < now() - make_interval(days => ?) "
                        + "ORDER BY path",
                (rs, n) -> new CuratorFinding(
                        CuratorRule.STALE_SLOT, rs.getString("path"),
                        "slot not updated for " + rs.getInt("age_days") + "d"),
                scope.workspaceSlug(), scope.projectSlug(), staleAfterDays);
    }

    /**
     * Groups of latest pages in the project that share a normalized (trimmed, lower-cased) title —
     * likely duplicates to merge. One finding per group, attached to the first colliding path, listing
     * the whole group.
     *
     * @param scope the project.
     * @return one finding per duplicate-title group, ordered by normalized title.
     */
    @Transactional(readOnly = true)
    public List<CuratorFinding> duplicateTitles(Scope scope) {
        // string_agg with a newline delimiter avoids JDBC array handling; paths never contain newlines.
        List<String[]> groups = jdbc.query(
                "SELECT lower(btrim(title)) AS norm, string_agg(path, E'\\n' ORDER BY path) AS paths "
                        + "FROM pages "
                        + "WHERE workspace = ? AND project = ? AND is_latest AND deleted_at IS NULL "
                        + "  AND btrim(title) <> '' "
                        + "GROUP BY lower(btrim(title)) HAVING count(*) > 1 "
                        + "ORDER BY norm",
                (rs, n) -> new String[] {rs.getString("norm"), rs.getString("paths")},
                scope.workspaceSlug(), scope.projectSlug());

        List<CuratorFinding> findings = new ArrayList<>(groups.size());
        for (String[] g : groups) {
            String norm = g[0];
            String[] paths = g[1].split("\n");
            findings.add(new CuratorFinding(
                    CuratorRule.DUPLICATE_TITLE, paths[0],
                    "duplicate title \"" + norm + "\" across " + paths.length + " pages: "
                            + String.join(", ", paths)));
        }
        return findings;
    }
}
