package com.agentmemory.eval;

/**
 * One self-improvement proposal handed to the {@link EvalGate} for validation (issue #31). This is the
 * minimal, gate-facing view of a pending write — intentionally decoupled from the auto-improve loop's
 * internal {@code pending_writes} record (#30), which maps its richer type onto this at the call site so
 * the two features do not share a type.
 *
 * @param path   the target page path (e.g. {@code _rules/security.md}); drives prefix selection. Required.
 * @param title  the proposed page title (never null; empty allowed).
 * @param body   the proposed page body/markdown (never null; empty allowed).
 * @param action the write action — {@code upsert} (default) or {@code delete}.
 */
public record EvalProposal(String path, String title, String body, String action) {

    public EvalProposal {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("eval proposal path must not be null or blank");
        }
        path = path.strip();
        title = title == null ? "" : title;
        body = body == null ? "" : body;
        action = (action == null || action.isBlank()) ? "upsert" : action.strip();
    }

    /** An {@code upsert} (create/update) proposal. */
    public static EvalProposal upsert(String path, String title, String body) {
        return new EvalProposal(path, title, body, "upsert");
    }
}
