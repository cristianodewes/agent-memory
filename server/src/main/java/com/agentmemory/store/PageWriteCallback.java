package com.agentmemory.store;

/**
 * Extension point for #13 (wiki layer) to attach the atomic markdown-file write + git commit to the
 * <em>same logical operation</em> as a page-row write (issue #12 implementation note; DD-002:
 * markdown in {@code wiki/} is the authoritative body, this DB row is the derived index).
 *
 * <p>The callback runs <strong>inside the store's write transaction</strong>, after the new version
 * row has been inserted and the prior version superseded, but before the transaction commits. If it
 * throws, the whole operation rolls back — so the DB index and the wiki file never diverge
 * (invariants #3 "index commits with the data" and #10 "atomic writes"). #12 itself passes no
 * callback (DB-only); #13 will supply one that performs the tmp+rename+fsync write and stages the
 * commit.
 *
 * <p>It is a single-method functional interface so a caller that does not need a side effect can
 * simply pass {@code null} (treated as a no-op) or a lambda.
 */
@FunctionalInterface
public interface PageWriteCallback {

    /**
     * Invoked within the write transaction with the just-persisted page version.
     *
     * @param persisted the new {@code is_latest} page row as stored (id assigned, timestamps set).
     * @throws Exception to abort and roll back the entire write (row + side effect together).
     */
    void afterPageWritten(PageRecord persisted) throws Exception;
}
