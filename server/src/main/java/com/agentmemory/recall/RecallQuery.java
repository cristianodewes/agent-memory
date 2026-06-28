package com.agentmemory.recall;

/**
 * A recall request: the free-text {@code text} to search for, the {@link Scope} to search within,
 * and how many fused hits to return. The query text is parsed by Postgres
 * ({@code plainto_tsquery('english', …)}, then OR-combined so any term matches — see
 * {@link RecallRepository}) on both the pages and the raw-observation arms, so callers pass natural
 * words, not {@code tsquery} operators.
 *
 * @param text  the search text; never null/blank.
 * @param scope the {@code (workspace, project)} to search; never null.
 * @param limit max number of fused hits to return; must be {@code > 0}.
 */
public record RecallQuery(String text, Scope scope, int limit) {

    /** A sensible default page of results when a caller does not specify one. */
    public static final int DEFAULT_LIMIT = 10;

    public RecallQuery {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("recall query text must not be null or blank");
        }
        if (scope == null) {
            throw new IllegalArgumentException("recall query scope must not be null");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("recall query limit must be > 0, was " + limit);
        }
    }

    /**
     * Build a query with the {@link #DEFAULT_LIMIT}.
     *
     * @param text  search text.
     * @param scope the scope to search.
     * @return the query.
     */
    public static RecallQuery of(String text, Scope scope) {
        return new RecallQuery(text, scope, DEFAULT_LIMIT);
    }
}
