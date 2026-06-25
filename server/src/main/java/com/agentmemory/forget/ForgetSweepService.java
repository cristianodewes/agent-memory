package com.agentmemory.forget;

import com.agentmemory.config.AgentMemoryProperties;
import com.agentmemory.core.Identity;
import com.agentmemory.core.MemoryLayer;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.store.AuditWriter;
import com.agentmemory.store.RetentionScorer;
import com.agentmemory.wiki.WikiWriter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The forget sweep (issue #25, ARCHITECTURE §5.1): the eviction pass that keeps the store healthy.
 * Two stages, both honoring the same retention math (#24) and exemptions:
 *
 * <ol>
 *   <li><strong>Soft-delete</strong> — a live latest page whose retention {@linkplain RetentionScorer
 *       score is cold} (≤ {@code coldThreshold}) and that is <em>not exempt</em> is dropped from
 *       "latest" and marked {@code deleted_at=now()}. The markdown + git history stay (DD-002), so it
 *       is recoverable until purge.</li>
 *   <li><strong>Purge</strong> — a soft-delete that has stayed cold for {@code hardDeleteAfterDays}
 *       without being accessed is hard-deleted: its index rows are removed and its wiki file removed +
 *       committed so a later reindex cannot resurrect it.</li>
 * </ol>
 *
 * <h2>Exemptions (never swept)</h2>
 * <ul>
 *   <li><strong>Semantic layer</strong> — timeless distilled knowledge does not age out.</li>
 *   <li><strong>Slots</strong> — pages under the reserved {@code _slots/} prefix are auto-pinned
 *       (#26). Detected by path here; this is the same rule {@code wiki.Slots.isExemptFromSweep}
 *       encodes — once #26 lands this delegates to that single seam. (Explicit {@code pinned}
 *       frontmatter on a non-slot page is not yet persisted to {@code pages}; honoring it is a
 *       follow-up once the column exists, tracked with #26.)</li>
 *   <li><strong>Recently accessed</strong> — a page touched within {@code recentlyAccessedDays}
 *       survives even if its score is cold.</li>
 * </ul>
 *
 * <h2>dry_run</h2>
 * {@link #sweep(WorkspaceId, ProjectId, boolean) sweep(.., dryRun=true)} computes and returns exactly
 * what <em>would</em> be soft-deleted/purged without calling any mutating method — an accurate
 * preview. A live run wraps all mutations + the audit row in one transaction (single-writer, invariant
 * #2/#3); never bypasses the writer.
 */
public class ForgetSweepService {

    private static final Logger log = LoggerFactory.getLogger(ForgetSweepService.class);

    /** Reserved slot prefix (mirrors {@code wiki.PageKind.SLOT} / {@code Slots.PREFIX}). */
    static final String SLOT_PREFIX = "_slots/";

    /** Audit action key for a sweep (see {@code audit_log.action} examples). */
    static final String AUDIT_ACTION = "forget.sweep";

    private final ForgetSweepRepository repo;
    private final RetentionScorer scorer;
    private final WikiWriter wikiWriter;
    private final AuditWriter audit;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AgentMemoryProperties.Decay decay;

    public ForgetSweepService(
            ForgetSweepRepository repo,
            RetentionScorer scorer,
            WikiWriter wikiWriter,
            AuditWriter audit,
            PlatformTransactionManager txManager,
            Clock clock,
            AgentMemoryProperties.Decay decay) {
        this.repo = repo;
        this.scorer = scorer;
        this.wikiWriter = wikiWriter;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.decay = decay;
    }

    /**
     * Run (or preview) a forget sweep over one project.
     *
     * @param workspace the workspace coordinate; never null.
     * @param project   the project coordinate; never null.
     * @param dryRun    {@code true} to preview without mutating.
     * @return a {@link SweepReport} of what was (or would be) soft-deleted and purged.
     */
    public SweepReport sweep(WorkspaceId workspace, ProjectId project, boolean dryRun) {
        if (workspace == null || project == null) {
            throw new IllegalArgumentException("workspace and project must not be null");
        }
        String ws = workspace.value();
        String proj = project.value();

        // --- compute the plan (no mutation) -----------------------------------------------------
        Instant now = clock.instant();
        List<SweepCandidate> toSoftDelete = new ArrayList<>();
        List<ForgetSweepRepository.Row> softDeleteRows = new ArrayList<>();
        int exemptSkipped = 0;

        for (ForgetSweepRepository.Row row : repo.liveLatestPages(ws, proj)) {
            double score = scorer.score(row.layer(), row.accessCount(), row.createdAt(), row.lastAccessedAt());
            boolean cold = score <= decay.coldThreshold();
            if (!cold) {
                continue;
            }
            if (isExempt(row, now)) {
                exemptSkipped++;
                continue;
            }
            toSoftDelete.add(new SweepCandidate(row.path(), row.layer().wire(), score));
            softDeleteRows.add(row);
        }

        List<ForgetSweepRepository.Row> purgeRows =
                repo.purgeEligible(ws, proj, decay.hardDeleteAfterDays());
        List<SweepCandidate> toPurge = new ArrayList<>(purgeRows.size());
        for (ForgetSweepRepository.Row row : purgeRows) {
            toPurge.add(new SweepCandidate(row.path(), row.layer().wire(), 0.0));
        }

        if (dryRun) {
            return new SweepReport(true, toSoftDelete, toPurge, exemptSkipped);
        }

        // --- apply atomically (single writer + audit in one transaction) ------------------------
        int finalExemptSkipped = exemptSkipped;
        return tx.execute(statusTx -> {
            List<SweepCandidate> softDeleted = new ArrayList<>();
            for (ForgetSweepRepository.Row row : softDeleteRows) {
                if (repo.softDelete(row.id())) {
                    softDeleted.add(new SweepCandidate(
                            row.path(), row.layer().wire(),
                            scorer.score(row.layer(), row.accessCount(), row.createdAt(), row.lastAccessedAt())));
                }
            }
            List<SweepCandidate> purged = new ArrayList<>();
            for (ForgetSweepRepository.Row row : purgeRows) {
                if (repo.purge(row.id(), decay.hardDeleteAfterDays())) {
                    // Remove the wiki file + commit so a reindex cannot resurrect a purged page.
                    Identity id = pageIdentity(ws, proj, row.path());
                    try {
                        wikiWriter.deleteAndCommit(id, "purge: " + row.path());
                    } catch (Exception e) {
                        throw new IllegalStateException(
                                "wiki purge side effect failed; rolling back purge of " + row.path(), e);
                    }
                    purged.add(new SweepCandidate(row.path(), row.layer().wire(), 0.0));
                }
            }
            // One audit row summarizing the sweep, filed under the project (path null = project-scoped).
            audit.record(
                    Identity.ofProject(WorkspaceId.of(ws), ProjectId.of(proj)),
                    AUDIT_ACTION, "page",
                    "{\"softDeleted\":" + softDeleted.size()
                            + ",\"purged\":" + purged.size()
                            + ",\"exemptSkipped\":" + finalExemptSkipped + "}");
            log.info("forget sweep {}/{}: soft-deleted {}, purged {}, exempt {}",
                    ws, proj, softDeleted.size(), purged.size(), finalExemptSkipped);
            return new SweepReport(false, softDeleted, purged, finalExemptSkipped);
        });
    }

    /**
     * Whether a cold page must be spared. Exempt when it is semantic-layer, a slot (by reserved
     * prefix), or was accessed within the recency window.
     */
    private boolean isExempt(ForgetSweepRepository.Row row, Instant now) {
        if (row.layer() == MemoryLayer.SEMANTIC) {
            return true;
        }
        if (row.path() != null && row.path().startsWith(SLOT_PREFIX)) {
            return true; // slot: auto-pinned (#26); == wiki.Slots.isExemptFromSweep once merged
        }
        if (decay.recentlyAccessedDays() > 0 && row.lastAccessedAt() != null) {
            Instant cutoff = now.minus(Duration.ofDays(decay.recentlyAccessedDays()));
            return row.lastAccessedAt().isAfter(cutoff);
        }
        return false;
    }

    private static Identity pageIdentity(String ws, String proj, String path) {
        return Identity.ofPage(WorkspaceId.of(ws), ProjectId.of(proj), PagePath.of(path));
    }
}
