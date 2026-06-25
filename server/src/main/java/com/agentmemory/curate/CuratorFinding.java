package com.agentmemory.curate;

/**
 * One maintenance finding from a curator run (issue #29): which {@link CuratorRule} fired, the page it
 * concerns, and a short human-readable detail. Plain data — the curator is report-only; acting on a
 * finding (forget, merge, fix a link) is a separate, deliberate step.
 *
 * @param rule   the rule that produced this finding.
 * @param path   the project-relative path of the page the finding is about (the source page for a
 *               dangling-link finding); never null.
 * @param detail a short explanation (e.g. "cold for 47d", "duplicate of concepts/recall.md",
 *               "→ platform:concepts/auth unresolved 31d"); never null.
 */
public record CuratorFinding(CuratorRule rule, String path, String detail) {

    public CuratorFinding {
        if (rule == null) {
            throw new IllegalArgumentException("finding.rule must not be null");
        }
        if (path == null) {
            throw new IllegalArgumentException("finding.path must not be null");
        }
        if (detail == null) {
            throw new IllegalArgumentException("finding.detail must not be null");
        }
    }
}
