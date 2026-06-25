package com.agentmemory.store;

import com.agentmemory.core.Identity;
import com.agentmemory.core.MemoryLayer;
import com.agentmemory.core.Page;
import com.agentmemory.core.PageId;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.Uuid7;
import com.agentmemory.core.WorkspaceId;
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
 * {@link JdbcTemplate}-backed {@link PageRepository} over the {@code pages} table (issue #12).
 *
 * <p><strong>Atomicity (invariants #3/#10).</strong> {@link #create} is {@code @Transactional}: the
 * supersede of the prior version, the insert of the new version, and the DB-generated {@code
 * search_vector} FTS column all commit together. The FTS column is {@code GENERATED ALWAYS ... STORED}
 * (V3), so Postgres computes it during the {@code INSERT} — there is no separate, post-commit
 * indexing step that could diverge.
 *
 * <p><strong>Single-writer per path (DD-006).</strong> A transaction-scoped Postgres advisory lock
 * keyed by {@code (workspace, project, path)} serializes concurrent {@code create}s for the
 * <em>same</em> page while letting different paths proceed in parallel. This closes both races a
 * naive implementation has: a lost update (two supersedes interleaving) and two first-versions
 * racing to claim {@code is_latest}. The {@code pages_latest_unique} partial index remains the hard
 * backstop. We supersede (flip {@code is_latest=false}) <em>before</em> inserting the new latest row
 * so that non-deferred unique index is never transiently violated.
 *
 * <p>Page <em>body</em> authority stays in the wiki (#13, DD-002); {@link PageWriteCallback} lets
 * #13 attach its markdown/git write to this same transaction.
 */
public class JdbcPageRepository implements PageRepository {

    private final JdbcTemplate jdbc;

    public JdbcPageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public PageRecord create(Identity identity, String title, String body) {
        return create(identity, title, body, null);
    }

    @Override
    @Transactional
    public PageRecord create(Identity identity, String title, String body, PageWriteCallback callback) {
        requirePageScoped(identity);
        if (title == null) {
            throw new IllegalArgumentException("page title must not be null");
        }
        if (body == null) {
            throw new IllegalArgumentException("page body must not be null");
        }

        String workspace = identity.workspace().value();
        String project = identity.project().value();
        String path = identity.page().value();

        // Serialize concurrent writers to THIS path (different paths run in parallel). Held until
        // the transaction commits/rolls back, and bound to this transaction connection (one per
        // @Transactional method via Spring), so it is correctly scoped. The lock key is hashtext
        // over the three coordinates joined by chr(31) (ASCII unit separator) built in SQL, so a
        // delimiter that cannot occur inside a slug or normalized path keeps distinct paths from
        // colliding into one key. pg_advisory_xact_lock returns void; consume the single row.
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtext(? || chr(31) || ? || chr(31) || ?))",
                (ResultSetExtractor<Void>) rs -> { rs.next(); return null; },
                workspace, project, path);

        UUID workspaceId = getOrCreateWorkspace(workspace);
        UUID projectId = getOrCreateProject(workspaceId, workspace, project);

        // Supersede the current latest (if any) BEFORE inserting the new latest row, so the
        // partial-unique (one latest per path) index is never transiently violated.
        UUID supersedes = jdbc.query(
                "SELECT id FROM pages "
                        + "WHERE workspace = ? AND project = ? AND path = ? AND is_latest "
                        + "FOR UPDATE",
                rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
                workspace, project, path);
        if (supersedes != null) {
            jdbc.update(
                    "UPDATE pages SET is_latest = false, updated_at = now() WHERE id = ?",
                    supersedes);
        }

        // Classify the page into its retention layer from the path (#24). Deterministic, no LLM —
        // the layer selects the decay regime the score (#15 recall) and sweep (#25) apply.
        MemoryLayer layer = LayerClassifier.classify(identity.page());

        UUID newId = Uuid7.randomUuid();
        PageRecord persisted = jdbc.queryForObject(
                "INSERT INTO pages "
                        + "(id, workspace_id, project_id, workspace, project, path, title, body, "
                        + " is_latest, supersedes, layer, access_count, last_accessed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?, 0, NULL) "
                        + "RETURNING id, workspace, project, path, title, body, is_latest, "
                        + "          supersedes, layer, access_count, last_accessed_at, created_at, updated_at",
                PAGE_RECORD_MAPPER,
                newId, workspaceId, projectId, workspace, project, path, title, body,
                supersedes, layer.wire());

        if (callback != null) {
            try {
                callback.afterPageWritten(persisted);
            } catch (Exception e) {
                // Roll the whole write back so the DB row and the wiki side effect stay in lockstep.
                // PageWriteException is a RuntimeException, so it triggers the @Transactional rollback;
                // wrapping both checked and runtime failures gives callers a single type to catch.
                throw new PageWriteException(
                        "page write side effect failed; rolling back row for " + path, e);
            }
        }
        return persisted;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PageRecord> readLatest(Identity identity) {
        requirePageScoped(identity);
        try {
            PageRecord record = jdbc.queryForObject(
                    SELECT_COLUMNS
                            + "WHERE workspace = ? AND project = ? AND path = ? AND is_latest",
                    PAGE_RECORD_MAPPER,
                    identity.workspace().value(), identity.project().value(), identity.page().value());
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<PageRecord> listLatest(WorkspaceId workspace, ProjectId project) {
        if (workspace == null || project == null) {
            throw new IllegalArgumentException("workspace and project must not be null");
        }
        return jdbc.query(
                SELECT_COLUMNS
                        + "WHERE workspace = ? AND project = ? AND is_latest "
                        + "ORDER BY updated_at DESC, id DESC",
                PAGE_RECORD_MAPPER,
                workspace.value(), project.value());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PageRecord> findById(PageId id) {
        if (id == null) {
            throw new IllegalArgumentException("page id must not be null");
        }
        try {
            PageRecord record = jdbc.queryForObject(
                    SELECT_COLUMNS + "WHERE id = ?", PAGE_RECORD_MAPPER, id.value());
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    // --- decay reinforcement (#24) -------------------------------------------------------------

    @Override
    @Transactional
    public Optional<PageRecord> reinforce(PageId id) {
        if (id == null) {
            throw new IllegalArgumentException("page id must not be null");
        }
        // Atomically bump the access counter and stamp the access time, returning the post-bump row
        // so a recall hit reflects its own reinforcement immediately. now() is the transaction start
        // time (statement-consistent), which is the access instant we want. No-op (empty) when the
        // id does not exist — the caller (recall) simply skips a hit it cannot reinforce.
        try {
            PageRecord updated = jdbc.queryForObject(
                    "UPDATE pages SET access_count = access_count + 1, last_accessed_at = now() "
                            + "WHERE id = ? "
                            + "RETURNING id, workspace, project, path, title, body, is_latest, "
                            + "          supersedes, layer, access_count, last_accessed_at, "
                            + "          created_at, updated_at",
                    PAGE_RECORD_MAPPER,
                    id.value());
            return Optional.ofNullable(updated);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    @Transactional
    public int dropWorkingFromLatest(WorkspaceId workspace, ProjectId project) {
        if (workspace == null || project == null) {
            throw new IllegalArgumentException("workspace and project must not be null");
        }
        // Working-layer pages are session scratch: at session end they are dropped from "latest" so
        // they stop surfacing in recall/listings, but the rows are NOT deleted — the knowledge still
        // exists as raw observations and as superseded history (#24 acceptance). Flipping is_latest
        // off is exactly the supersede mechanic, minus a replacement row.
        return jdbc.update(
                "UPDATE pages SET is_latest = false, updated_at = now() "
                        + "WHERE workspace = ? AND project = ? AND is_latest AND layer = ?",
                workspace.value(), project.value(), MemoryLayer.WORKING.wire());
    }

    // --- workspace/project get-or-create -------------------------------------------------------

    /**
     * Get-or-create the workspace by slug, returning its surrogate id. The slug is the UNIQUE natural
     * key (V2); {@code ON CONFLICT DO NOTHING} + select makes this idempotent and concurrency-safe
     * under the path advisory lock (and harmless without it).
     */
    private UUID getOrCreateWorkspace(String slug) {
        jdbc.update(
                "INSERT INTO workspaces (id, slug) VALUES (?, ?) ON CONFLICT (slug) DO NOTHING",
                Uuid7.randomUuid(), slug);
        return jdbc.queryForObject("SELECT id FROM workspaces WHERE slug = ?", UUID.class, slug);
    }

    /** Get-or-create the project by {@code (workspace, slug)} (the V2 unique pair). */
    private UUID getOrCreateProject(UUID workspaceId, String workspace, String slug) {
        jdbc.update(
                "INSERT INTO projects (id, workspace_id, workspace, slug) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (workspace, slug) DO NOTHING",
                Uuid7.randomUuid(), workspaceId, workspace, slug);
        return jdbc.queryForObject(
                "SELECT id FROM projects WHERE workspace = ? AND slug = ?",
                UUID.class, workspace, slug);
    }

    // --- mapping -------------------------------------------------------------------------------

    private static final String SELECT_COLUMNS =
            "SELECT id, workspace, project, path, title, body, is_latest, supersedes, "
                    + "       layer, access_count, last_accessed_at, created_at, updated_at "
                    + "FROM pages ";

    private static final RowMapper<PageRecord> PAGE_RECORD_MAPPER = (rs, rowNum) -> {
        UUID supersedes = rs.getObject("supersedes", UUID.class);
        Page page = new Page(
                new PageId(rs.getObject("id", UUID.class)),
                Identity.ofPage(
                        WorkspaceId.of(rs.getString("workspace")),
                        ProjectId.of(rs.getString("project")),
                        PagePath.of(rs.getString("path"))),
                rs.getString("title"),
                rs.getString("body"),
                rs.getBoolean("is_latest"),
                supersedes == null ? null : new PageId(supersedes),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
        Timestamp lastAccessed = rs.getTimestamp("last_accessed_at");
        return new PageRecord(
                page,
                MemoryLayer.fromWire(rs.getString("layer")),
                rs.getLong("access_count"),
                lastAccessed == null ? null : lastAccessed.toInstant());
    };

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static void requirePageScoped(Identity identity) {
        if (identity == null || !identity.isPageScoped()) {
            throw new IllegalArgumentException("identity must be page-scoped (path required)");
        }
    }
}
