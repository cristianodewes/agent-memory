package com.agentmemory.lifecycle;

import com.agentmemory.core.Identity;

/**
 * The outcome of a project lifecycle op (rename / move / purge, issue #33): the before/after identity
 * and what was touched, so the caller (the API + CLI) and the audit log have a precise record. Both
 * identities are project-scoped (no path).
 *
 * @param op            the op key, e.g. {@code "rename"}, {@code "move"}, {@code "purge"}.
 * @param before        the project identity before the op; never null.
 * @param after         the project identity after the op (equals {@code before} for purge, where the
 *                      project ceases to exist); never null.
 * @param pagesAffected page rows whose denormalized identity was rewritten (rename/move) or deleted
 *                      (purge).
 * @param linksRepointed link rows whose source/target slugs were rewritten (rename/move); 0 for purge.
 */
public record ProjectOpResult(
        String op,
        Identity before,
        Identity after,
        int pagesAffected,
        int linksRepointed) {

    public ProjectOpResult {
        if (op == null || op.isBlank()) {
            throw new IllegalArgumentException("op must not be blank");
        }
        if (before == null || after == null) {
            throw new IllegalArgumentException("before/after identity must not be null");
        }
    }
}
