package com.agentmemory.autoimprove;

import com.agentmemory.core.SessionId;
import com.agentmemory.recall.Scope;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The approval gate (issue #30): records every proposed durable-knowledge edit in {@code pending_writes}
 * and decides its fate by {@link AutoImproveProperties#requireApproval()} — hold it as {@code proposed}
 * for human review, or apply it immediately through the normal write path (the default). The audit trail
 * is the {@code pending_writes} row itself.
 *
 * <p>Applying goes through the injected {@link ProposalApplier} seam (production: the #19/#20 write
 * service), so the gate's decision logic is independent of the write stack. The optional eval-gate (#31)
 * result lands in the row's {@code eval_result} slot; that wiring is added when the proposal/eval engines
 * are integrated (this gate leaves it null).
 */
public class AutoImproveGate {

    private static final Logger log = LoggerFactory.getLogger(AutoImproveGate.class);

    private final JdbcPendingWriteRepository pending;
    private final ProposalApplier applier;
    private final AutoImproveProperties props;
    private final JsonMapper json = JsonMapper.builder().build();

    public AutoImproveGate(
            JdbcPendingWriteRepository pending, ProposalApplier applier, AutoImproveProperties props) {
        this.pending = pending;
        this.applier = applier;
        this.props = props;
    }

    /**
     * Put one proposed edit through the gate.
     *
     * @param scope   the project; never null.
     * @param session the session it came from, or null.
     * @param write   the proposed edit; never null.
     * @return the recorded proposal id and its resulting status ({@code PROPOSED} when held,
     *     {@code APPLIED} when auto-applied).
     * @throws RuntimeException if the auto-apply write fails — the proposal row is left {@code proposed}
     *     (not lost), and the caller (scheduler) records the session as failed for a bounded retry.
     */
    public Decision submit(Scope scope, SessionId session, ProposedWrite write) {
        if (scope == null || write == null) {
            throw new IllegalArgumentException("scope and write must not be null");
        }
        UUID id = pending.propose(scope, session == null ? null : session.value(), write);
        if (props.requireApproval()) {
            log.debug("auto-improve: holding proposal {} for {} (require_approval=true)", id, write.path());
            return new Decision(id, PendingWriteStatus.PROPOSED);
        }
        // Default: apply through the normal write path, then record it applied.
        applier.apply(scope, write);
        pending.markApplied(id, null);
        log.info("auto-improve: applied proposal {} ({})", id, write.path());
        return new Decision(id, PendingWriteStatus.APPLIED);
    }

    /** Recent proposals in a project (newest first) — the {@code auto-improve-report} surface. */
    public List<PendingWriteRecord> report(Scope scope, int limit) {
        return pending.recent(scope, limit);
    }

    /**
     * Approve a held proposal: apply it through the normal write path and mark it {@code applied}. The
     * human-review counterpart of an auto-applied submit — same write path, just gated on an explicit
     * decision. Only a {@code proposed} row can be approved.
     *
     * @param id the proposal id.
     * @return the updated record.
     * @throws IllegalArgumentException if no proposal has that id.
     * @throws IllegalStateException    if the proposal is not in the {@code proposed} state.
     * @throws RuntimeException         if the apply fails — the row is left {@code proposed} (not lost).
     */
    public PendingWriteRecord approve(UUID id) {
        PendingWriteRecord rec = require(id, PendingWriteStatus.PROPOSED, "approved");
        applier.apply(Scope.of(rec.workspace(), rec.project()), toProposedWrite(rec));
        pending.markApplied(id, null);
        log.info("auto-improve: approved + applied proposal {} ({})", id, rec.path());
        return pending.findById(id).orElseThrow();
    }

    /**
     * Reject a held proposal: mark it {@code rejected}, leaving memory untouched. Only a {@code proposed}
     * row can be rejected.
     *
     * @param id the proposal id.
     * @return the updated record.
     * @throws IllegalArgumentException if no proposal has that id.
     * @throws IllegalStateException    if the proposal is not in the {@code proposed} state.
     */
    public PendingWriteRecord reject(UUID id) {
        PendingWriteRecord rec = require(id, PendingWriteStatus.PROPOSED, "rejected");
        pending.markRejected(id, null);
        log.info("auto-improve: rejected proposal {} ({})", id, rec.path());
        return pending.findById(id).orElseThrow();
    }

    private PendingWriteRecord require(UUID id, PendingWriteStatus expected, String verb) {
        PendingWriteRecord rec = pending.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("no such proposal: " + id));
        if (rec.status() != expected) {
            throw new IllegalStateException(
                    "proposal " + id + " is '" + rec.status().db() + "', only a '" + expected.db()
                            + "' proposal can be " + verb);
        }
        return rec;
    }

    /** Rebuild the {@link ProposedWrite} from a stored row: {@code path}/{@code kind}/{@code rationale} are columns; {@code title}/{@code body} live in the {@code proposal} JSON. */
    private ProposedWrite toProposedWrite(PendingWriteRecord rec) {
        JsonNode p = json.readTree(rec.proposal());
        String title = str(p == null ? null : p.get("title"), rec.path());
        String body = str(p == null ? null : p.get("body"), "");
        return new ProposedWrite(rec.path(), title, body, rec.kind(), rec.rationale());
    }

    private static String str(JsonNode node, String fallback) {
        return node != null && node.isString() ? node.stringValue() : fallback;
    }

    /** The gate's decision for one proposal. */
    public record Decision(UUID id, PendingWriteStatus status) {
        public boolean applied() {
            return status == PendingWriteStatus.APPLIED;
        }

        public boolean held() {
            return status == PendingWriteStatus.PROPOSED;
        }
    }
}
