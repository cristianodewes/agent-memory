package com.agentmemory.curate;

import java.util.List;

/**
 * The outcome of a {@code memory_lint} run (issue #29): the curator's rule findings plus any LLM
 * contradiction findings, and — when not a dry run — where the report was persisted under
 * {@code _lint/}.
 *
 * @param workspace        the project's workspace slug.
 * @param project          the project slug.
 * @param ruleFindings     the zero-cost curator rule findings (never null).
 * @param contradictions   the LLM contradiction findings (empty when the LLM pass was skipped).
 * @param written          {@code true} iff the report was persisted as a {@code _lint/} page.
 * @param lintPath         the project-relative path of the written {@code _lint/} page, or {@code null}
 *                         on a dry run / when nothing was written.
 */
public record LintReport(
        String workspace,
        String project,
        List<CuratorFinding> ruleFindings,
        List<Contradiction> contradictions,
        boolean written,
        String lintPath) {

    public LintReport {
        ruleFindings = ruleFindings == null ? List.of() : List.copyOf(ruleFindings);
        contradictions = contradictions == null ? List.of() : List.copyOf(contradictions);
    }

    /** @return the total number of findings (rule findings + contradictions). */
    public int total() {
        return ruleFindings.size() + contradictions.size();
    }

    /** @return {@code true} when nothing was flagged. */
    public boolean isClean() {
        return total() == 0;
    }
}
