package com.agentmemory.store;

import com.agentmemory.core.ActorResolver;
import com.agentmemory.core.Identity;
import com.agentmemory.core.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link JdbcTemplate}-backed {@link AuditWriter} over {@code audit_log} (V8). One {@code INSERT} per
 * mutation; the {@code detail} column is {@code jsonb}, so the supplied JSON string is cast in SQL.
 * Runs {@code @Transactional} so an audit row joins the caller's surrounding transaction (a lifecycle
 * op's DB changes and its audit row commit or roll back together — invariant #3).
 *
 * <p>Multi-user attribution (issue #39): the row is stamped with the {@code actor} — the authenticated
 * user — resolved from the {@link ActorResolver} at write time. These mutations run on the request
 * thread, so the resolver's security-context read is valid (unlike the async capture path, which
 * threads the actor explicitly). {@code null} in single-user/loopback mode.
 */
public class JdbcAuditWriter implements AuditWriter {

    private final JdbcTemplate jdbc;
    private final ActorResolver actors;

    /** Unattributed writer (no actor) — for pure-unit tests and DB-only contexts without security. */
    public JdbcAuditWriter(JdbcTemplate jdbc) {
        this(jdbc, ActorResolver.NONE);
    }

    public JdbcAuditWriter(JdbcTemplate jdbc, ActorResolver actors) {
        this.jdbc = jdbc;
        this.actors = (actors == null) ? ActorResolver.NONE : actors;
    }

    @Override
    @Transactional
    public void record(Identity subject, String action, String entityType, String detailJson) {
        if (subject == null) {
            throw new IllegalArgumentException("audit subject identity must not be null");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("audit action must not be blank");
        }
        String detail = (detailJson == null || detailJson.isBlank()) ? "{}" : detailJson;
        String path = subject.isPageScoped() ? subject.page().value() : null;
        jdbc.update(
                "INSERT INTO audit_log "
                        + "(id, workspace, project, path, action, entity_type, actor, detail) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))",
                Uuid7.randomUuid(),
                subject.workspace().value(),
                subject.project().value(),
                path,
                action,
                entityType,
                actors.currentActor(),
                detail);
    }
}
