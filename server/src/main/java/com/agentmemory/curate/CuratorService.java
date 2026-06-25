package com.agentmemory.curate;

import com.agentmemory.graph.DanglingRef;
import com.agentmemory.graph.GraphService;
import com.agentmemory.recall.Scope;
import java.util.ArrayList;
import java.util.List;

/**
 * The rule-based curator (issue #29, ARCHITECTURE §5.1): a zero-cost maintenance pass that flags pages
 * needing attention without any LLM call, so it is free and deterministic to run. Four rules:
 *
 * <ol>
 *   <li><strong>Cold episodic</strong> and <strong>stale slots</strong> and
 *       <strong>duplicate titles</strong> — pure SQL over {@code pages} ({@link CuratorRepository}).</li>
 *   <li><strong>Dangling cross-project links</strong> — the stale, unresolved links whose target lives
 *       in another project, read from the #28 {@link GraphService} dangling-reference lint (built on
 *       the unified link graph). Benign deferred links and same-project dangles are excluded; this rule
 *       is specifically about broken <em>cross-project</em> references.</li>
 * </ol>
 *
 * <p>Report-only: {@link #curate(Scope)} returns a {@link CuratorReport} and changes nothing. Persisting
 * the report (staging) and the LLM contradiction pass are layered on by {@code memory_lint}.
 */
public class CuratorService {

    /** Default: an episodic page not accessed for this many days is cold. */
    public static final int DEFAULT_COLD_AFTER_DAYS = 30;

    /** Default: a {@code _slots/} page not updated for this many days is stale. */
    public static final int DEFAULT_STALE_SLOT_AFTER_DAYS = 60;

    /** Hard cap on dangling links scanned per run (the lint is a sample, not an exhaustive dump). */
    private static final int DANGLING_CAP = 500;

    private final CuratorRepository repo;
    private final GraphService graph;
    private final int coldAfterDays;
    private final int staleSlotAfterDays;

    public CuratorService(CuratorRepository repo, GraphService graph) {
        this(repo, graph, DEFAULT_COLD_AFTER_DAYS, DEFAULT_STALE_SLOT_AFTER_DAYS);
    }

    public CuratorService(
            CuratorRepository repo, GraphService graph, int coldAfterDays, int staleSlotAfterDays) {
        if (coldAfterDays <= 0 || staleSlotAfterDays <= 0) {
            throw new IllegalArgumentException("cold/stale thresholds must be > 0 days");
        }
        this.repo = repo;
        this.graph = graph;
        this.coldAfterDays = coldAfterDays;
        this.staleSlotAfterDays = staleSlotAfterDays;
    }

    /**
     * Run every rule over one project and collect the findings (report-only).
     *
     * @param scope the project to curate; never null.
     * @return the maintenance report (possibly clean).
     */
    public CuratorReport curate(Scope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        List<CuratorFinding> findings = new ArrayList<>();
        findings.addAll(repo.coldEpisodicPages(scope, coldAfterDays));
        findings.addAll(repo.staleSlots(scope, staleSlotAfterDays));
        findings.addAll(repo.duplicateTitles(scope));
        findings.addAll(danglingCrossProject(scope));
        return new CuratorReport(scope.workspaceSlug(), scope.projectSlug(), findings);
    }

    /** The stale, cross-project dangling links sourced from this project (from the #28 lint). */
    private List<CuratorFinding> danglingCrossProject(Scope scope) {
        List<CuratorFinding> out = new ArrayList<>();
        for (DanglingRef d : graph.danglingReport(scope, DANGLING_CAP)) {
            if (!d.dangling()) {
                continue; // only stale (dangling); a recent deferred forward-link is benign
            }
            if (!isCrossProject(scope, d)) {
                continue; // this rule is specifically about cross-project breakage
            }
            out.add(new CuratorFinding(
                    CuratorRule.DANGLING_CROSS_PROJECT, d.fromPath(),
                    "dangling cross-project link -> " + d.targetId()
                            + " (unresolved " + (d.ageSeconds() / 86_400) + "d)"));
        }
        return out;
    }

    /**
     * A dangling link is cross-project when its target's {@code (workspace, project)} differs from the
     * source project. {@code targetId} is {@code workspace/project/path}; the path itself contains
     * slashes, so only the first two segments are the target's workspace and project.
     */
    private static boolean isCrossProject(Scope scope, DanglingRef d) {
        String[] seg = d.targetId().split("/", 3);
        if (seg.length < 3) {
            return false;
        }
        return !(seg[0].equals(scope.workspaceSlug()) && seg[1].equals(scope.projectSlug()));
    }
}
