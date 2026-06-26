package com.agentmemory.forget;

import com.agentmemory.core.MemoryLayer;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single-writer SQL behind the forget sweep (issue #25) over the {@code pages} table. The cold
 * decision itself is <em>not</em> in SQL — the retention curve ({@code exp}/{@code log}) lives in the
 * shared {@link com.agentmemory.store.RetentionScorer} (#24, "reuse the score; never re-derive") — so
 * this repository fetches the live latest rows a sweep must consider, then applies the score in Java
 * and soft-deletes the cold, non-exempt ones by id. Purge is pure SQL on the soft-delete age.
 *
 * <p><strong>Single writer (invariant #2).</strong> Every mutating method is {@code @Transactional};
 * soft-delete and purge run in the sweep's transaction alongside its audit row so they commit or roll
 * back together (invariant #3). A dry run never calls a mutating method.
 */
public class ForgetSweepRepository {

    private final JdbcTemplate jdbc;

    public ForgetSweepRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The live (not soft-deleted) latest pages in a project — the universe a sweep scores for
     * coldness. Reading them up front (rather than scoring in SQL) keeps the decay math in one place.
     *
     * @param workspace workspace slug.
     * @param project   project slug.
     * @return the candidate rows, each carrying the fields the score and exemption checks need.
     */
    @Transactional(readOnly = true)
    public List<Row> liveLatestPages(String workspace, String project) {
        return jdbc.query(
                "SELECT id, path, layer, access_count, last_accessed_at, created_at "
                        + "FROM pages "
                        + "WHERE workspace = ? AND project = ? AND is_latest AND deleted_at IS NULL",
                ROW_MAPPER, workspace, project);
    }

    /**
     * The one live (not soft-deleted) latest page version at an exact path — what a <em>targeted</em>
     * forget ({@link ForgetSweepService#forgetPage} for a curator {@code COLD_EPISODIC} finding, #101)
     * needs to soft-delete a specific page by id. Empty when no live page exists there (already forgotten
     * or never existed), which makes the forget action idempotent.
     *
     * @param workspace workspace slug.
     * @param project   project slug.
     * @param path      the exact project-relative page path.
     * @return the live latest row, or empty.
     */
    @Transactional(readOnly = true)
    public Optional<Row> findLiveLatest(String workspace, String project, String path) {
        return jdbc.query(
                "SELECT id, path, layer, access_count, last_accessed_at, created_at "
                        + "FROM pages "
                        + "WHERE workspace = ? AND project = ? AND path = ? AND is_latest "
                        + "  AND deleted_at IS NULL",
                ROW_MAPPER, workspace, project, path).stream().findFirst();
    }

    /**
     * Soft-delete a page: drop it from "latest" and stamp {@code deleted_at = now()} so it stops
     * surfacing in recall/listings while the row (and its markdown/git history) is retained for
     * recovery until purge. Only acts on a row that is currently latest and live, so a double sweep is
     * idempotent.
     *
     * @param id the page-version id to soft-delete.
     * @return {@code true} if a row was soft-deleted.
     */
    @Transactional
    public boolean softDelete(UUID id) {
        int n = jdbc.update(
                "UPDATE pages SET is_latest = false, deleted_at = now(), updated_at = now() "
                        + "WHERE id = ? AND is_latest AND deleted_at IS NULL",
                id);
        return n == 1;
    }

    /**
     * The soft-deleted pages eligible for purge: soft-deleted more than {@code hardDeleteAfterDays}
     * ago and not accessed since the soft-delete. Returned for both the dry-run preview and the
     * applied purge so the same predicate decides both.
     *
     * @param workspace workspace slug.
     * @param project   project slug.
     * @param hardDeleteAfterDays days a soft-delete must have aged before it is purgeable.
     * @return the purge-eligible rows.
     */
    @Transactional(readOnly = true)
    public List<Row> purgeEligible(String workspace, String project, int hardDeleteAfterDays) {
        return jdbc.query(
                "SELECT id, path, layer, access_count, last_accessed_at, created_at "
                        + "FROM pages "
                        + "WHERE workspace = ? AND project = ? AND deleted_at IS NOT NULL "
                        + "  AND deleted_at < now() - make_interval(days => ?) "
                        + "  AND (last_accessed_at IS NULL OR last_accessed_at <= deleted_at)",
                ROW_MAPPER, workspace, project, hardDeleteAfterDays);
    }

    /**
     * Purge (hard-delete) one soft-deleted page by id, guarding on the same age/no-access predicate so
     * a row touched (or recovered) between preview and apply is not purged out from under the caller.
     *
     * @param id                  the page-version id to purge.
     * @param hardDeleteAfterDays the purge-age threshold (re-checked at delete time).
     * @return {@code true} if a row was purged.
     */
    @Transactional
    public boolean purge(UUID id, int hardDeleteAfterDays) {
        int n = jdbc.update(
                "DELETE FROM pages "
                        + "WHERE id = ? AND deleted_at IS NOT NULL "
                        + "  AND deleted_at < now() - make_interval(days => ?) "
                        + "  AND (last_accessed_at IS NULL OR last_accessed_at <= deleted_at)",
                id, hardDeleteAfterDays);
        return n == 1;
    }

    // --- mapping -------------------------------------------------------------------------------

    /**
     * One {@code pages} row the sweep reasons about: enough to score it ({@link #layer},
     * {@link #accessCount}, {@link #createdAt}, {@link #lastAccessedAt}) and to identify/exempt it
     * ({@link #id}, {@link #path}).
     *
     * @param id             page-version id.
     * @param path           project-relative path.
     * @param layer          retention layer.
     * @param accessCount    recall-hit counter.
     * @param createdAt      version creation instant.
     * @param lastAccessedAt last recall access, or {@code null}.
     */
    public record Row(
            UUID id, String path, MemoryLayer layer, long accessCount,
            Instant createdAt, Instant lastAccessedAt) {}

    private static final RowMapper<Row> ROW_MAPPER = (rs, n) -> {
        java.sql.Timestamp last = rs.getTimestamp("last_accessed_at");
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("path"),
                MemoryLayer.fromWire(rs.getString("layer")),
                rs.getLong("access_count"),
                rs.getTimestamp("created_at").toInstant(),
                last == null ? null : last.toInstant());
    };
}
