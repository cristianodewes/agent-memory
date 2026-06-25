package com.agentmemory.llmrecall;

import com.agentmemory.recall.HitSource;
import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The working, additive {@link AccessReinforcer}: for the {@link HitSource#PAGE} hits a query
 * returned, it increments {@code pages.access_count} and stamps {@code pages.last_accessed_at = now()}
 * on those exact page rows. Those V3 columns ("Access/decay columns feed the decay-reinforcement bump
 * on recall") are what issue #24's decay math reads; this is the <em>write</em> side of that bump,
 * provided here so #21's acceptance ("access reinforcement fires on returned hits") holds even before
 * #24 lands. When #24 publishes its own reinforcer the {@code @ConditionalOnMissingBean} guard in
 * {@link LlmRecallConfiguration} lets it take over, so the two never double-count.
 *
 * <p><strong>Additive and id-scoped.</strong> The update is a single {@code UPDATE … WHERE id IN (…)}
 * keyed by the page-version ids recall actually returned (the {@link RecallHit#id()} of a PAGE hit is
 * the page-version UUID), guarded by {@code workspace}/{@code project} so a stale/forged id from
 * another scope can never be bumped. The ids are bound as {@code ?::uuid} placeholders — the same
 * driver-agnostic idiom {@code RecallRepository.graphNeighbors} uses — rather than a bound array. It
 * only ever increments — never resets or decays — so running it is safe regardless of what #24 later
 * layers on top.
 *
 * <p><strong>Best-effort (never fails recall).</strong> Reinforcement is a side effect of a read; any
 * failure is caught and logged at debug, and the method returns normally. The bump runs in its own
 * {@code @Transactional} unit so it cannot poison a caller's transaction.
 */
public class JdbcAccessReinforcer implements AccessReinforcer {

    private static final Logger log = LoggerFactory.getLogger(JdbcAccessReinforcer.class);

    private final JdbcTemplate jdbc;

    public JdbcAccessReinforcer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void reinforce(Scope scope, List<RecallHit> hits) {
        if (scope == null || hits == null || hits.isEmpty()) {
            return;
        }
        // Collect the page-version ids of the PAGE hits (raw-observation fallback hits have no page).
        // Validate each as a UUID so a non-UUID id (which cannot match the uuid column) is dropped
        // before it reaches the DB as a bad ::uuid cast; keep the canonical text for binding.
        List<String> pageIds = new ArrayList<>(hits.size());
        for (RecallHit h : hits) {
            if (h.source() != HitSource.PAGE || h.id() == null) {
                continue;
            }
            try {
                pageIds.add(UUID.fromString(h.id()).toString());
            } catch (IllegalArgumentException ignored) {
                // Not a UUID — cannot be a pages.id; skip silently.
            }
        }
        if (pageIds.isEmpty()) {
            return;
        }

        try {
            String placeholders = pageIds.stream().map(s -> "?::uuid").collect(Collectors.joining(", "));
            // Args order: workspace, project, then each id placeholder.
            List<Object> args = new ArrayList<>(pageIds.size() + 2);
            args.add(scope.workspaceSlug());
            args.add(scope.projectSlug());
            args.addAll(pageIds);
            jdbc.update(
                    "UPDATE pages SET access_count = access_count + 1, last_accessed_at = now() "
                            + "WHERE workspace = ? AND project = ? AND id IN (" + placeholders + ")",
                    args.toArray());
        } catch (RuntimeException e) {
            // A read's side effect must never fail the read. Log and move on.
            log.debug("access reinforcement skipped for {}/{} ({} hits): {}",
                    scope.workspaceSlug(), scope.projectSlug(), pageIds.size(), e.toString());
        }
    }
}
