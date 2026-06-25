package com.agentmemory.curate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The outcome of a rule-based curator run over one project (issue #29): the scope, the findings, and a
 * by-rule tally. Report-only — producing this does not change the store; {@code memory_lint} decides
 * whether to persist it (under {@code _lint/}) and whether to add the LLM contradiction pass.
 *
 * @param workspace the project's workspace slug.
 * @param project   the project slug.
 * @param findings  every finding, grouped/ordered by rule then path (never null).
 */
public record CuratorReport(String workspace, String project, List<CuratorFinding> findings) {

    public CuratorReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /** @return the total number of findings across all rules. */
    public int total() {
        return findings.size();
    }

    /** @return {@code true} when no rule fired (a clean project). */
    public boolean isClean() {
        return findings.isEmpty();
    }

    /** @return the count of findings per {@link CuratorRule} (rules with zero findings are omitted). */
    public Map<CuratorRule, Long> countsByRule() {
        return findings.stream()
                .collect(Collectors.groupingBy(CuratorFinding::rule, Collectors.counting()));
    }
}
