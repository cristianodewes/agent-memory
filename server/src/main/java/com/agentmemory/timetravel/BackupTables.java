package com.agentmemory.timetravel;

import java.util.List;

/**
 * The fixed set of DB tables a {@code backup} captures and a {@code restore} reloads (issue #34),
 * each described faithfully enough to round-trip its column types through text without a native
 * {@code pg_dump}.
 *
 * <p><strong>What is backed up.</strong> The <em>primary</em> capture and operational state that the
 * markdown wiki does <em>not</em> hold: {@code workspaces}/{@code projects} (identity),
 * {@code sessions}/{@code observations} (the raw capture log), {@code handoffs},
 * {@code audit_log}/{@code pending_writes}, and {@code page_embeddings} (expensive-to-recompute
 * vectors). The purely <em>derived</em> {@code pages} and {@code links} are deliberately excluded:
 * they are rebuildable from the git-backed wiki by {@code reindex} (#14, DD-002), so backing them up
 * would be redundant and could drift from the wiki.
 *
 * <p><strong>Order matters.</strong> The list is in FK-dependency order (parents first): a restore
 * truncates and reloads in this order so foreign keys are satisfiable, and truncates in reverse.
 * {@code page_embeddings} references {@code pages}, which is <em>not</em> backed up — so a restore
 * reloads embeddings only after the caller has reindexed the wiki to recreate {@code pages} (the
 * restore tolerates a missing parent by deferring constraints; see {@link DefaultBackupService}).
 *
 * <p><strong>Type fidelity.</strong> Every column round-trips through <em>text</em>: the value is
 * read with {@code ResultSet.getString} (its canonical Postgres text form — a uuid, a timestamptz, a
 * {@code text[]} literal {@code {a,b}}, a jsonb document, a {@code vector} literal {@code [..]}) and
 * re-bound as text on insert with an explicit {@code ?::<type>} cast, so the driver never has to
 * guess the parameter type and the value is reproduced byte-for-byte. The array/jsonb/vector columns
 * additionally cast to {@code ::text} on read because those types do not come back as a usable string
 * from {@code getString} without it.
 */
final class BackupTables {

    private BackupTables() {
    }

    /** A column carried in a backup: its name, the SELECT expression, and the INSERT placeholder. */
    record Column(String name, String selectExpr, String insertPlaceholder) {

        /**
         * A scalar column ({@code uuid}, {@code text}, {@code timestamptz}, {@code integer}): read via
         * {@code getString} (no SELECT cast needed) and re-bound as {@code ?::<pgType>} so the text is
         * coerced back to the column type explicitly.
         */
        static Column plain(String name, String pgType) {
            return new Column(name, name, "?::" + pgType);
        }

        /**
         * A column whose type does not stringify from {@code getString} directly ({@code text[]},
         * {@code jsonb}, {@code vector}): cast to {@code ::text} on read and {@code ?::<pgType>} on
         * write.
         */
        static Column cast(String name, String pgType) {
            return new Column(name, name + "::text AS " + name, "?::" + pgType);
        }
    }

    /** A backed-up table: its name and its ordered columns. */
    record Table(String name, List<Column> columns) {

        String selectSql() {
            StringBuilder sb = new StringBuilder("SELECT ");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(columns.get(i).selectExpr());
            }
            return sb.append(" FROM ").append(name).toString();
        }

        String insertSql() {
            StringBuilder cols = new StringBuilder();
            StringBuilder vals = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) {
                    cols.append(", ");
                    vals.append(", ");
                }
                cols.append(columns.get(i).name());
                vals.append(columns.get(i).insertPlaceholder());
            }
            return "INSERT INTO " + name + " (" + cols + ") VALUES (" + vals + ")";
        }
    }

    /**
     * The tables to back up, in FK-dependency (parents-first) order. A restore reloads in this order
     * and truncates in reverse.
     */
    static final List<Table> ALL = List.of(
            new Table("workspaces", List.of(
                    Column.plain("id", "uuid"),
                    Column.plain("slug", "text"),
                    Column.plain("created_at", "timestamptz"),
                    Column.plain("updated_at", "timestamptz"))),
            new Table("projects", List.of(
                    Column.plain("id", "uuid"),
                    Column.plain("workspace_id", "uuid"),
                    Column.plain("workspace", "text"),
                    Column.plain("slug", "text"),
                    Column.plain("created_at", "timestamptz"),
                    Column.plain("updated_at", "timestamptz"))),
            new Table("sessions", List.of(
                    Column.plain("id", "uuid"),
                    Column.plain("workspace_id", "uuid"),
                    Column.plain("project_id", "uuid"),
                    Column.plain("workspace", "text"),
                    Column.plain("project", "text"),
                    Column.plain("agent", "text"),
                    Column.plain("started_at", "timestamptz"),
                    Column.plain("ended_at", "timestamptz"))),
            new Table("observations", List.of(
                    Column.plain("id", "uuid"),
                    Column.plain("session_id", "uuid"),
                    Column.plain("workspace_id", "uuid"),
                    Column.plain("project_id", "uuid"),
                    Column.plain("workspace", "text"),
                    Column.plain("project", "text"),
                    Column.plain("kind", "text"),
                    Column.plain("source_event", "text"),
                    Column.plain("extension", "text"),
                    Column.plain("payload", "text"),
                    Column.plain("created_at", "timestamptz"))),
            new Table("handoffs", List.of(
                    Column.plain("id", "uuid"),
                    Column.plain("workspace_id", "uuid"),
                    Column.plain("project_id", "uuid"),
                    Column.plain("workspace", "text"),
                    Column.plain("project", "text"),
                    Column.plain("from_session", "uuid"),
                    Column.plain("status", "text"),
                    Column.plain("summary", "text"),
                    Column.cast("open_questions", "text[]"),
                    Column.cast("next_steps", "text[]"),
                    Column.plain("created_at", "timestamptz"),
                    Column.plain("accepted_at", "timestamptz"))),
            new Table("audit_log", List.of(
                    Column.plain("id", "uuid"),
                    Column.plain("workspace", "text"),
                    Column.plain("project", "text"),
                    Column.plain("path", "text"),
                    Column.plain("action", "text"),
                    Column.plain("entity_type", "text"),
                    Column.plain("entity_id", "uuid"),
                    Column.cast("detail", "jsonb"),
                    Column.plain("at", "timestamptz"))),
            new Table("pending_writes", List.of(
                    Column.plain("id", "uuid"),
                    Column.plain("workspace", "text"),
                    Column.plain("project", "text"),
                    Column.plain("path", "text"),
                    Column.plain("status", "text"),
                    Column.plain("kind", "text"),
                    Column.cast("proposal", "jsonb"),
                    Column.plain("rationale", "text"),
                    Column.cast("eval_result", "jsonb"),
                    Column.plain("created_at", "timestamptz"),
                    Column.plain("decided_at", "timestamptz"),
                    Column.plain("applied_at", "timestamptz"))),
            new Table("page_embeddings", List.of(
                    Column.plain("id", "uuid"),
                    Column.plain("page_id", "uuid"),
                    Column.plain("provider", "text"),
                    Column.plain("model", "text"),
                    Column.plain("dim", "integer"),
                    Column.cast("embedding", "vector"),
                    Column.plain("created_at", "timestamptz"))));
}
