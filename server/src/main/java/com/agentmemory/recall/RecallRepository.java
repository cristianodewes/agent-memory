package com.agentmemory.recall;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SQL arms of recall over the {@code pages} / {@code links} / {@code observations} tables (issue
 * #15). Each method returns ranked {@link Candidate}s, best-first; ordering is the arm's own
 * relevance signal and is what {@link RrfFusion} consumes (not the raw scores). All snippets are
 * generated in SQL via {@code ts_headline} with HTML {@code <mark>} delimiters so the result is
 * display-ready (issue #15: HTML-marked snippets).
 *
 * <p>Reads are {@code @Transactional(readOnly = true)}. Queries are scoped to a single
 * {@code (workspace, project)} and, for pages, to {@code is_latest} rows only — superseded versions
 * never surface in recall.
 */
public class RecallRepository {

    /**
     * {@code ts_headline} options: wrap matches in {@code <mark>…</mark>}, keep short single-fragment
     * snippets so a hit is a glanceable line, not a wall of text.
     */
    private static final String HEADLINE_OPTS =
            "StartSel=<mark>, StopSel=</mark>, MaxFragments=2, MaxWords=18, MinWords=5, "
                    + "FragmentDelimiter= … ";

    /**
     * SQL fragment parsing natural search words into an <strong>OR-combined</strong> tsquery (the
     * single {@code ?} binds the search text). {@code plainto_tsquery} stems and normalizes the text
     * under the {@code 'english'} config — kept in lockstep with the generated {@code search_vector},
     * which uses the same config — but <em>AND</em>-combines every lexeme, so a multi-term query
     * matches only rows containing <em>all</em> terms. In a PT/EN-mixed store that drops multi-term
     * recall to zero and forces the raw-observation fallback (issue #134). Rewriting the rendered
     * query's {@code &} conjunctions to {@code |} disjunctions makes <em>any</em> term match while
     * {@code ts_rank(search_vector, q)} still ranks rows matching more (and higher-weighted) lexemes
     * above those matching fewer — "retrieve broad, rerank narrow", with precision left to the
     * downstream RRF + cross-encoder + gates.
     *
     * <p>Safe on the edges: an empty/whitespace query yields an empty tsquery, so the {@code replace}
     * is a no-op and {@code @@} matches nothing exactly as before; {@code plainto_tsquery} only ever
     * emits {@code &} (never the phrase operator {@code <->}), so rewriting {@code &} → {@code |}
     * cannot corrupt the query.
     *
     * <p>This is a bare scalar expression: valid directly anywhere a tsquery is expected (e.g. a
     * {@code ts_headline} argument). To expose it as a query relation {@code q} in a {@code FROM}
     * clause it must be wrapped as {@code (SELECT … AS q) alias} — a cast expression is <em>not</em> a
     * valid function-in-{@code FROM} item, unlike the bare {@code plainto_tsquery(…)} call it replaces.
     */
    private static final String OR_TSQUERY =
            "replace(plainto_tsquery('english', ?)::text, '&', '|')::tsquery";

    private final JdbcTemplate jdbc;

    public RecallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // --- full-text arm over pages_fts ---------------------------------------------------------------

    /**
     * Full-text search over the latest pages in scope, ranked by {@code ts_rank} (title weighted
     * above body via the generated vector's {@code setweight}). The query text is parsed and then
     * OR-combined via {@link #OR_TSQUERY} so any term matches; {@code ts_rank} still ranks pages
     * matching more terms higher. Returns at most {@code limit} candidates.
     *
     * @param workspace workspace slug.
     * @param project   project slug.
     * @param text      the user's search text.
     * @param limit     max candidates.
     * @return ranked page candidates, best-first.
     */
    @Transactional(readOnly = true)
    public List<Candidate> ftsPages(String workspace, String project, String text, int limit) {
        return jdbc.query(
                "SELECT p.id::text AS id, p.path, p.title, "
                        + "       ts_rank(p.search_vector, q) AS rank, "
                        + "       ts_headline('english', p.body, q, ?) AS snippet "
                        + "FROM pages p, (SELECT " + OR_TSQUERY + " AS q) tsq "
                        + "WHERE p.workspace = ? AND p.project = ? AND p.is_latest "
                        + "  AND p.search_vector @@ q "
                        + "ORDER BY rank DESC, p.id DESC "
                        + "LIMIT ?",
                PAGE_CANDIDATE_MAPPER,
                HEADLINE_OPTS, text, workspace, project, limit);
    }

    // --- link-graph neighborhood arm ----------------------------------------------------------------

    /**
     * Expand a set of seed page ids by one hop over the {@code links} graph (both directions:
     * pages the seeds link to, and pages that link to the seeds) and rank the resulting neighbor
     * pages by how many edges connect them to the seed set — a page linked from several hits is more
     * relevant. Seeds themselves are excluded, results are confined to the latest pages in scope, and
     * each carries an {@code <mark>}-headlined snippet against the query text. Returns at most
     * {@code limit} candidates.
     *
     * @param workspace workspace slug.
     * @param project   project slug.
     * @param text      the search text (for the neighbor snippet headline).
     * @param seedIds   the FTS hit page-version ids to expand from; empty ⇒ no neighbors.
     * @param limit     max candidates.
     * @return ranked neighbor candidates, best-first (most edges to the seed set first).
     */
    @Transactional(readOnly = true)
    public List<Candidate> graphNeighbors(
            String workspace, String project, String text, List<String> seedIds, int limit) {
        if (seedIds == null || seedIds.isEmpty()) {
            return List.of();
        }
        String seedPlaceholders = seedIds.stream().map(s -> "?::uuid").collect(Collectors.joining(", "));

        // Edges from seeds (outbound) UNION edges into seeds (inbound), then group neighbors by edge
        // count. The seed list is interpolated as placeholders three times (outbound IN, inbound IN,
        // and the NOT IN exclusion), so build the argument list in that order.
        String sql =
                "WITH seeds(pid) AS (SELECT x FROM unnest(ARRAY[" + seedPlaceholders + "]) AS x), "
                        + "neighbors AS ( "
                        + "  SELECT l.to_page_id AS nid FROM links l "
                        + "    WHERE l.from_page_id IN (SELECT pid FROM seeds) AND l.to_page_id IS NOT NULL "
                        + "  UNION ALL "
                        + "  SELECT l.from_page_id AS nid FROM links l "
                        + "    WHERE l.to_page_id IN (SELECT pid FROM seeds) "
                        + ") "
                        + "SELECT p.id::text AS id, p.path, p.title, "
                        + "       count(*) AS edges, "
                        + "       ts_headline('english', p.body, " + OR_TSQUERY + ", ?) AS snippet "
                        + "FROM neighbors n "
                        + "JOIN pages p ON p.id = n.nid "
                        + "WHERE p.is_latest AND p.workspace = ? AND p.project = ? "
                        + "  AND p.id NOT IN (SELECT pid FROM seeds) "
                        + "GROUP BY p.id, p.path, p.title, p.body "
                        + "ORDER BY edges DESC, p.id DESC "
                        + "LIMIT ?";

        List<Object> args = new ArrayList<>();
        args.addAll(seedIds);                 // ARRAY[...] seed placeholders
        args.add(text);                       // headline tsquery
        args.add(HEADLINE_OPTS);              // headline options
        args.add(workspace);
        args.add(project);
        args.add(limit);
        return jdbc.query(sql, PAGE_CANDIDATE_MAPPER, args.toArray());
    }

    // --- raw-observation fallback arm ---------------------------------------------------------------

    /**
     * Bounded full-text search over raw {@code observations} in scope — the fallback arm used only
     * when the compiled-page arms return nothing (ARCHITECTURE §3.3). Ranked by {@code ts_rank} over
     * the payload, newest-first on ties. Each hit is labeled {@link HitSource#RAW_OBSERVATION} and
     * carries the observation {@code kind}; there is no page path.
     *
     * @param workspace workspace slug.
     * @param project   project slug.
     * @param text      the search text.
     * @param limit     hard upper bound on raw hits returned (the "bounded" in bounded fallback).
     * @return ranked raw-observation candidates, best-first.
     */
    @Transactional(readOnly = true)
    public List<Candidate> rawObservations(String workspace, String project, String text, int limit) {
        return jdbc.query(
                "SELECT o.id::text AS id, o.kind, "
                        + "       ts_rank(o.search_vector, q) AS rank, "
                        + "       ts_headline('english', o.payload, q, ?) AS snippet "
                        + "FROM observations o, (SELECT " + OR_TSQUERY + " AS q) tsq "
                        + "WHERE o.workspace = ? AND o.project = ? "
                        + "  AND o.search_vector @@ q "
                        + "ORDER BY rank DESC, o.created_at DESC, o.id DESC "
                        + "LIMIT ?",
                RAW_CANDIDATE_MAPPER,
                HEADLINE_OPTS, text, workspace, project, limit);
    }

    // --- scope enumeration (for global cross-project recall, #29) -----------------------------------

    /**
     * Every {@code (workspace, project)} that currently has at least one latest page — the scope set a
     * {@code global} cross-project query (issue #29) fans out over. Ordered {@code (workspace, project)}
     * for a deterministic enumeration. Projects with only superseded/soft-deleted pages are excluded
     * (nothing recallable lives there).
     *
     * @return the distinct latest-page scopes, ordered.
     */
    @Transactional(readOnly = true)
    public List<Scope> allScopes() {
        return jdbc.query(
                "SELECT DISTINCT workspace, project FROM pages WHERE is_latest "
                        + "ORDER BY workspace, project",
                (rs, n) -> Scope.of(rs.getString("workspace"), rs.getString("project")));
    }

    // --- mapping ------------------------------------------------------------------------------------

    /** Maps a page row to a PAGE candidate; the arm's order is the ranking, so score starts at 0. */
    private static final RowMapper<Candidate> PAGE_CANDIDATE_MAPPER = (rs, rowNum) -> {
        String id = rs.getString("id");
        RecallHit hit = new RecallHit(
                HitSource.PAGE,
                id,
                rs.getString("path"),
                rs.getString("title"),
                null,
                0.0,
                rowNum + 1,
                rs.getString("snippet"));
        return new Candidate(id, hit);
    };

    /** Maps an observation row to a RAW_OBSERVATION candidate (no path; title is the kind label). */
    private static final RowMapper<Candidate> RAW_CANDIDATE_MAPPER = (rs, rowNum) -> {
        String id = rs.getString("id");
        String kind = rs.getString("kind");
        RecallHit hit = new RecallHit(
                HitSource.RAW_OBSERVATION,
                id,
                null,
                "observation: " + kind,
                kind,
                0.0,
                rowNum + 1,
                rs.getString("snippet"));
        return new Candidate(id, hit);
    };
}
