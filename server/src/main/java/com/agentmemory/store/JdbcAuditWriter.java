package com.agentmemory.store;

import com.agentmemory.core.Identity;
import com.agentmemory.core.Uuid7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link JdbcTemplate}-backed {@link AuditWriter} over {@code audit_log} (V8). One {@code INSERT} per
 * mutation; the {@code detail} column is {@code jsonb}, so the supplied JSON string is cast in SQL.
 * Runs {@code @Transactional} so an audit row joins the caller's surrounding transaction (a lifecycle
 * op's DB changes and its audit row commit or roll back together — invariant #3).
 */
public class JdbcAuditWriter implements AuditWriter {

    private final JdbcTemplate jdbc;

    public JdbcAuditWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
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
                "INSERT INTO audit_log (id, workspace, project, path, action, entity_type, detail) "
                        + "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))",
                Uuid7.randomUuid(),
                subject.workspace().value(),
                subject.project().value(),
                path,
                action,
                entityType,
                detail);
    }
}
