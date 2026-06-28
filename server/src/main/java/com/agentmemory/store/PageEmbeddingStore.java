package com.agentmemory.store;

import com.agentmemory.core.PageId;
import com.agentmemory.llm.EmbeddingResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC persistence for the {@code page_embeddings} table — the storage half of the pgvector
 * semantic-recall arm (issue #16; ARCHITECTURE §4.2, §3.3; DD-004). It writes one vector per
 * {@code (page version, provider, model)} with the denormalized {@code {provider, model, dim}}
 * triple beside it (invariant #8), and reads the nearest latest pages to a query vector by cosine
 * distance using the {@code <=>} operator against the HNSW index from migration V7.
 *
 * <h2>Dimension contract</h2>
 * The {@code embedding} column is a {@code vector(}{@value #EMBEDDING_DIM}{@code )} fixed at DDL time
 * (V7), and a {@code CHECK (dim = }{@value #EMBEDDING_DIM}{@code )} pins the denormalized {@code dim}
 * to that width. {@link #EMBEDDING_DIM} mirrors that single source of truth here so callers can guard
 * a provider/model whose width differs (a provider swap) <em>before</em> attempting a write that the
 * column would reject — see {@link #upsert} and {@link com.agentmemory.recall.PageEmbeddingService}.
 *
 * <h2>Re-embed semantics</h2>
 * {@link #upsert} is {@code INSERT ... ON CONFLICT (page_id, provider, model) DO UPDATE}, so
 * re-embedding the same page version with the same provider/model overwrites the prior vector rather
 * than erroring on the {@code page_embeddings_unique_per_model} constraint; a different provider or
 * model is stored as an additional row. After writing, the page's {@code embedding_id} is pointed at
 * the row (the deferred FK declared NULLable in V3) so a page advertises whether it has an embedding.
 *
 * <p>Stateless given its {@link JdbcTemplate}; reads are {@code readOnly}. No third-party pgvector
 * binding is used — vectors cross the wire as the pgvector text literal {@code [v0,v1,…]} cast to
 * {@code vector}, which is the format the extension's input parser accepts.
 */
public class PageEmbeddingStore {

    /**
     * The fixed {@code pgvector} column width and the {@code dim} a stored embedding must declare —
     * the embedding-dim contract from migration V7 (Voyage {@code voyage-3}, #6). A vector of any
     * other width cannot be stored; callers detect the mismatch via {@link #EMBEDDING_DIM} and skip.
     */
    public static final int EMBEDDING_DIM = 1024;

    private final JdbcTemplate jdbc;

    public PageEmbeddingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Store (or replace) the embedding for one page version. Idempotent per
     * {@code (pageId, provider, model)}: a repeat overwrites the vector; a different provider/model
     * adds a row. The page's {@code embedding_id} is set to the written row.
     *
     * @param pageId    the page-version id the vector describes; never null.
     * @param embedding the embedding with its denormalized {@code {provider, model, dim}}; never null.
     * @throws IllegalArgumentException if the embedding's {@code dim} is not {@link #EMBEDDING_DIM}
     *     (the caller is expected to have checked the embedder width and skipped — this is the loud
     *     backstop so a wrong-width vector never silently corrupts the column).
     */
    @Transactional
    public void upsert(PageId pageId, EmbeddingResult embedding) {
        if (pageId == null) {
            throw new IllegalArgumentException("pageId must not be null");
        }
        if (embedding == null) {
            throw new IllegalArgumentException("embedding must not be null");
        }
        if (embedding.dim() != EMBEDDING_DIM) {
            throw new IllegalArgumentException(
                    "embedding dim (" + embedding.dim() + ") does not match the page_embeddings column "
                            + "width (" + EMBEDDING_DIM + "); the vector cannot be stored. Reconfigure the "
                            + "embeddings model/dimension or migrate the column width.");
        }
        UUID page = pageId.value();
        // Encode with a finiteness guard: pgvector's input parser rejects NaN/Infinity, and a raw
        // CAST failure would surface as an opaque DataAccessException. Fail here with a clear message
        // (the service layer's catch turns this into a graceful skip; a direct caller sees the cause).
        String literal = toVectorLiteral(embedding.vector(), true);
        UUID id = com.agentmemory.core.Uuid7.randomUuid();

        // One vector per (page, provider, model). ON CONFLICT overwrites so a re-embed replaces in
        // place; RETURNING id gives us whichever row now holds the vector (inserted or updated) to
        // point pages.embedding_id at. created_at is refreshed on overwrite so it reflects the vector.
        UUID rowId = jdbc.queryForObject(
                "INSERT INTO page_embeddings (id, page_id, provider, model, dim, embedding, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, CAST(? AS vector), now()) "
                        + "ON CONFLICT (page_id, provider, model) DO UPDATE "
                        + "SET dim = EXCLUDED.dim, embedding = EXCLUDED.embedding, created_at = now() "
                        + "RETURNING id",
                (rs, n) -> rs.getObject("id", UUID.class),
                id, page, embedding.provider(), embedding.model(), embedding.dim(), literal);

        jdbc.update("UPDATE pages SET embedding_id = ? WHERE id = ?", rowId, page);
    }

    /**
     * Rank the latest pages in scope by cosine distance of their embedding to {@code queryVector},
     * nearest first. Only embeddings produced by the given {@code provider}/{@code model} are
     * considered, so vectors from different models (which live in the same physical space only by
     * width, not by meaning) are never compared — a provider swap reads only its own vectors. Pages
     * with no matching embedding are simply absent (graceful: they remain reachable via FTS/graph).
     *
     * @param workspace   workspace slug.
     * @param project     project slug.
     * @param queryVector the embedded query; its length must be {@link #EMBEDDING_DIM}.
     * @param provider    the embedding provider whose vectors to search (e.g. {@code voyage}).
     * @param model       the embedding model whose vectors to search (e.g. {@code voyage-3}).
     * @param limit       max page ids to return; must be {@code > 0}.
     * @return ranked {@link VectorHit}s, nearest first (lowest cosine distance).
     */
    @Transactional(readOnly = true)
    public List<VectorHit> nearestLatest(
            String workspace,
            String project,
            float[] queryVector,
            String provider,
            String model,
            int limit) {
        if (queryVector == null || queryVector.length != EMBEDDING_DIM) {
            throw new IllegalArgumentException(
                    "query vector must be " + EMBEDDING_DIM + "-dim, was "
                            + (queryVector == null ? "null" : queryVector.length));
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0, was " + limit);
        }
        String literal = toVectorLiteral(queryVector, true);
        // Cosine distance via <=> (matches the vector_cosine_ops HNSW index in V7). Restrict to the
        // latest pages in scope and to the active provider/model so only comparable vectors compete.
        // Tie-break on id for deterministic ordering when two vectors are equidistant.
        return jdbc.query(
                "SELECT p.id::text AS id, "
                        + "       (e.embedding <=> CAST(? AS vector)) AS distance "
                        + "FROM page_embeddings e "
                        + "JOIN pages p ON p.id = e.page_id "
                        + "WHERE p.is_latest AND p.workspace = ? AND p.project = ? "
                        + "  AND e.provider = ? AND e.model = ? "
                        + "ORDER BY distance ASC, p.id DESC "
                        + "LIMIT ?",
                VECTOR_HIT_MAPPER,
                literal, workspace, project, provider, model, limit);
    }

    /**
     * Fetch the stored embedding vector for each of the given page-version ids under one
     * {@code provider}/{@code model}, in a single bounded query (the read counterpart to
     * {@link #upsert}). Unlike {@link #nearestLatest} this does <em>not</em> embed or rank — it is a
     * direct by-id lookup used by the recall MMR diversity pass (issue #141) to read the candidate
     * pool's own vectors, so the {@code IN} list is bounded to the candidate count (≤ K) and there is
     * no network call. Only vectors under the active {@code provider}/{@code model} are returned, so
     * incomparable vectors from a different model are never mixed in; ids with no matching embedding
     * are simply absent from the result (the caller treats them as diversity-neutral).
     *
     * <p>The {@code embedding} column is read as its pgvector text literal ({@code embedding::text})
     * and parsed back to a {@code float[]} — the inverse of {@link #toVectorLiteral} — so no
     * third-party pgvector type binding is needed (mirroring the write side). A non-UUID id is skipped
     * defensively (it cannot reference a page version, so it has no embedding).
     *
     * @param pageIds  the candidate page-version ids (canonical UUID text); bounded to the pool.
     * @param provider the embedding provider whose vectors to read (e.g. {@code voyage}).
     * @param model    the embedding model whose vectors to read (e.g. {@code voyage-3}).
     * @return page-version id → its embedding vector, for those ids that have one under provider/model.
     */
    @Transactional(readOnly = true)
    public Map<String, float[]> fetchByPageIds(List<String> pageIds, String provider, String model) {
        if (pageIds == null || pageIds.isEmpty() || provider == null || model == null) {
            return Map.of();
        }
        // Parse to UUIDs (page-version ids), skipping any non-UUID id defensively — a candidate that is
        // not a page id simply has no embedding to fetch.
        List<UUID> ids = new ArrayList<>(pageIds.size());
        for (String s : pageIds) {
            UUID u = tryParseUuid(s);
            if (u != null) {
                ids.add(u);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = ids.stream().map(x -> "?").collect(Collectors.joining(", "));
        List<Object> args = new ArrayList<>(ids.size() + 2);
        args.add(provider);
        args.add(model);
        args.addAll(ids);

        Map<String, float[]> out = new HashMap<>(ids.size() * 2);
        // A void block lambda binds to RowCallbackHandler (per-row), accumulating into the map; it is
        // unambiguous against the ResultSetExtractor overload (which must return a value).
        jdbc.query(
                "SELECT page_id::text AS id, embedding::text AS vec "
                        + "FROM page_embeddings "
                        + "WHERE provider = ? AND model = ? AND page_id IN (" + placeholders + ")",
                rs -> {
                    out.put(rs.getString("id"), parseVectorLiteral(rs.getString("vec")));
                },
                args.toArray());
        return out;
    }

    /** Count stored embeddings for a page version (any provider/model) — for tests/diagnostics. */
    @Transactional(readOnly = true)
    public int countFor(PageId pageId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM page_embeddings WHERE page_id = ?", Integer.class, pageId.value());
        return n == null ? 0 : n;
    }

    // --- mapping / encoding --------------------------------------------------------------------

    /** One ranked vector-search result: the page-version id and its cosine distance to the query. */
    public record VectorHit(String pageId, double distance) {}

    private static final RowMapper<VectorHit> VECTOR_HIT_MAPPER =
            (rs, n) -> new VectorHit(rs.getString("id"), rs.getDouble("distance"));

    /**
     * Encode a float vector as the pgvector text literal {@code [v0,v1,…]}. {@link Float#toString}
     * round-trips exactly and the extension parses this form; building it here keeps us free of a
     * third-party pgvector type binding (zero new dependencies, mirroring the #6 HTTP layer).
     *
     * @param vector        the components.
     * @param requireFinite when {@code true}, reject a non-finite ({@code NaN}/{@code Infinity})
     *     component with an {@link IllegalArgumentException} — pgvector's parser would otherwise fail
     *     the {@code CAST} with an opaque error. Pass {@code true} for any literal headed to the DB.
     */
    static String toVectorLiteral(float[] vector, boolean requireFinite) {
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (requireFinite && !Float.isFinite(vector[i])) {
                throw new IllegalArgumentException(
                        "vector component [" + i + "] is not finite (" + vector[i] + "); pgvector accepts "
                                + "only finite reals. The embedding cannot be stored or queried.");
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Float.toString(vector[i]));
        }
        return sb.append(']').toString();
    }

    /**
     * Parse a pgvector text literal {@code [v0,v1,…]} back into a {@code float[]} — the inverse of
     * {@link #toVectorLiteral}, used to read the {@code embedding} column via {@code embedding::text}.
     * Tolerant of surrounding whitespace and the optional brackets; an empty/blank literal yields an
     * empty array. {@link Float#parseFloat} round-trips the {@link Float#toString} form (incl. scientific
     * notation) written on the way in.
     */
    static float[] parseVectorLiteral(String literal) {
        if (literal == null) {
            return new float[0];
        }
        String s = literal.trim();
        if (s.startsWith("[")) {
            s = s.substring(1);
        }
        if (s.endsWith("]")) {
            s = s.substring(0, s.length() - 1);
        }
        s = s.trim();
        if (s.isEmpty()) {
            return new float[0];
        }
        String[] parts = s.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i].trim());
        }
        return v;
    }

    /** @return the parsed {@link UUID}, or {@code null} if {@code s} is not a valid UUID. */
    private static UUID tryParseUuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
