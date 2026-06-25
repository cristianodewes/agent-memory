package com.agentmemory.timetravel;

import com.agentmemory.lifecycle.ProcessLock;
import com.agentmemory.timetravel.BackupTables.Column;
import com.agentmemory.timetravel.BackupTables.Table;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * JDBC-based online backup/restore (issue #34). Implemented over {@link JdbcTemplate} rather than a
 * native {@code pg_dump} so it is self-contained and portable (no client tools on the server image)
 * and so the backup can run inside a single read transaction for a consistent snapshot while the
 * source database stays writable.
 *
 * <h2>Backup format</h2>
 * A gzip-compressed {@link TarArchive ustar tarball} containing one {@code <table>.jsonl} per
 * {@link BackupTables backed-up table} (one JSON object per row, every value carried as its canonical
 * Postgres text form) plus a {@code manifest.json} (format version, creation time, per-table counts).
 *
 * <h2>Consistency</h2>
 * All tables are read inside one {@code REPEATABLE READ}, read-only transaction, so the snapshot is
 * internally consistent (FKs line up) even though concurrent writers keep modifying the live tables.
 *
 * <h2>Restore</h2>
 * Destructive: it performs the live-process check (invariant #9) via {@link ProcessLock} and refuses
 * while a live holder is present unless {@code force}. When it proceeds it truncates the target tables
 * (reverse FK order) and reloads them (forward FK order) inside one write transaction with constraints
 * deferred. {@code page_embeddings} references the <em>derived</em> {@code pages} table (which is not
 * part of a DB-only backup — it is rebuilt from the wiki by reindex, DD-002); its rows are therefore
 * reloaded with an {@code EXISTS(pages)} guard so a restore never trips that FK when pages are absent.
 */
public class DefaultBackupService implements BackupService {

    private static final Logger log = LoggerFactory.getLogger(DefaultBackupService.class);

    /** Bumped if the archive layout changes incompatibly. */
    static final int FORMAT_VERSION = 1;

    private static final String MANIFEST_ENTRY = "manifest.json";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readOnlyTx;
    private final TransactionTemplate writeTx;
    private final Path dataDir;
    private final ObjectMapper json = JsonMapper.builder().build();

    public DefaultBackupService(JdbcTemplate jdbc, PlatformTransactionManager txManager, Path dataDir) {
        this.jdbc = jdbc;
        // A read-only, REPEATABLE READ transaction gives backup a consistent snapshot of all tables at
        // once while concurrent writers keep going (Spring sets the isolation + read-only on the JDBC
        // connection at BEGIN — no raw SET TRANSACTION needed).
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
        this.readOnlyTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.writeTx = new TransactionTemplate(txManager);
        this.dataDir = dataDir.toAbsolutePath().normalize();
    }

    // --- backup ----------------------------------------------------------------------------------

    @Override
    public BackupResult backup(Path target) {
        Path archive = target.toAbsolutePath().normalize();
        Map<String, String> entries = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        // One REPEATABLE READ read-only transaction → a consistent snapshot while writers proceed.
        readOnlyTx.executeWithoutResult(status -> {
            for (Table table : BackupTables.ALL) {
                List<String> lines = readTableAsJsonl(table);
                entries.put(table.name() + ".jsonl", String.join("\n", lines));
                counts.put(table.name(), lines.size());
            }
        });

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        entries.put(MANIFEST_ENTRY, manifestJson(counts, total));

        try {
            Files.createDirectories(archive.getParent());
            // tmp + rename so a partially-written archive never masquerades as a valid backup.
            Path tmp = Files.createTempFile(archive.getParent(), ".backup-", ".tmp");
            try {
                try (OutputStream raw = Files.newOutputStream(tmp);
                        OutputStream buffered = new BufferedOutputStream(raw);
                        GZIPOutputStream gz = new GZIPOutputStream(buffered)) {
                    TarArchive.write(gz, entries);
                }
                Files.move(tmp, archive, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
            long bytes = Files.size(archive);
            log.info("backup wrote {} ({} rows across {} tables, {} bytes)",
                    archive, total, counts.size(), bytes);
            return new BackupResult(archive, Map.copyOf(counts), total, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write backup archive " + archive, e);
        }
    }

    private List<String> readTableAsJsonl(Table table) {
        List<Column> cols = table.columns();
        return jdbc.query(table.selectSql(), rs -> {
            List<String> lines = new ArrayList<>();
            while (rs.next()) {
                ObjectNode row = json.createObjectNode();
                for (int i = 0; i < cols.size(); i++) {
                    String value = rs.getString(i + 1); // canonical Postgres text form (null-safe)
                    if (value == null) {
                        row.putNull(cols.get(i).name());
                    } else {
                        row.put(cols.get(i).name(), value);
                    }
                }
                lines.add(json.writeValueAsString(row));
            }
            return lines;
        });
    }

    private String manifestJson(Map<String, Integer> counts, int total) {
        ObjectNode manifest = json.createObjectNode();
        manifest.put("format", "agent-memory-backup");
        manifest.put("version", FORMAT_VERSION);
        manifest.put("createdAt", java.time.Instant.now().toString());
        manifest.put("totalRows", total);
        ObjectNode tables = manifest.putObject("tables");
        counts.forEach((name, count) -> tables.put(name, count.intValue()));
        ArrayNode order = manifest.putArray("order");
        BackupTables.ALL.forEach(t -> order.add(t.name()));
        return json.writeValueAsString(manifest);
    }

    // --- restore ---------------------------------------------------------------------------------

    @Override
    public RestoreResult restore(Path source, boolean force) {
        // Invariant #9: refuse a destructive restore while a live process holds the data dir.
        if (!force) {
            Optional<Long> holder = ProcessLock.detectAnyLiveHolder(dataDir);
            if (holder.isPresent()) {
                log.warn("restore refused: live data-dir holder pid {}", holder.get());
                return RestoreResult.refused(holder.get());
            }
        }

        Map<String, String> entries = readArchive(source);
        Map<String, List<ObjectNode>> rowsByTable = parseEntries(entries);

        Map<String, Integer> counts = new LinkedHashMap<>();
        writeTx.executeWithoutResult(status -> {
            // One multi-table TRUNCATE ... CASCADE clears the backed-up tables atomically; CASCADE also
            // clears the derived pages/links (rebuilt later by reindex from the git-backed wiki, DD-002).
            // Then reload parents-first (forward FK order) so every FK is satisfiable as rows land — our
            // migrations' FKs are NOT DEFERRABLE, so ordering, not SET CONSTRAINTS, keeps the reload
            // valid. The lone cross-set FK (page_embeddings.page_id → the now-empty derived pages) is
            // handled by reloadTable's EXISTS(pages) guard.
            jdbc.execute("TRUNCATE TABLE " + truncateList() + " CASCADE");
            for (Table t : BackupTables.ALL) {
                List<ObjectNode> rows = rowsByTable.getOrDefault(t.name(), List.of());
                int inserted = reloadTable(t, rows);
                counts.put(t.name(), inserted);
            }
        });

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        log.info("restore reloaded {} rows across {} tables from {}", total, counts.size(), source);
        return RestoreResult.done(Map.copyOf(counts), total);
    }

    private int reloadTable(Table table, List<ObjectNode> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        List<Column> cols = table.columns();
        // page_embeddings.page_id → pages(id): pages are derived (rebuilt by reindex), not in a DB-only
        // backup, so guard each insert with EXISTS(pages) to stay FK-safe when pages are absent.
        boolean guardPages = table.name().equals("page_embeddings");
        String sql = guardPages ? guardedInsertSql(table) : table.insertSql();

        int inserted = 0;
        for (ObjectNode row : rows) {
            Object[] args = new Object[cols.size()];
            for (int i = 0; i < cols.size(); i++) {
                JsonNode v = row.get(cols.get(i).name());
                args[i] = (v == null || v.isNull()) ? null : v.stringValue();
            }
            inserted += jdbc.update(sql, args);
        }
        if (guardPages && inserted < rows.size()) {
            log.info("page_embeddings: reloaded {}/{} rows (skipped rows whose page is absent "
                    + "until a reindex rebuilds pages)", inserted, rows.size());
        }
        return inserted;
    }

    /** The comma-separated list of backed-up tables for a single multi-table TRUNCATE. */
    private static String truncateList() {
        StringBuilder sb = new StringBuilder();
        for (Table t : BackupTables.ALL) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(t.name());
        }
        return sb.toString();
    }

    /** An {@code INSERT ... SELECT ... WHERE EXISTS(pages)} variant for the embeddings FK guard. */
    private static String guardedInsertSql(Table table) {
        List<Column> cols = table.columns();
        StringBuilder colList = new StringBuilder();
        StringBuilder valList = new StringBuilder();
        int pageIdIdx = -1;
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                colList.append(", ");
                valList.append(", ");
            }
            colList.append(cols.get(i).name());
            valList.append(cols.get(i).insertPlaceholder());
            if (cols.get(i).name().equals("page_id")) {
                pageIdIdx = i;
            }
        }
        // The page_id placeholder is "?::uuid"; reuse it in the EXISTS guard against the same bound arg.
        String pagePlaceholder = cols.get(pageIdIdx).insertPlaceholder();
        return "INSERT INTO " + table.name() + " (" + colList + ") "
                + "SELECT " + valList + " WHERE EXISTS (SELECT 1 FROM pages p WHERE p.id = "
                + pagePlaceholder + ")";
    }

    private Map<String, String> readArchive(Path source) {
        try (var raw = Files.newInputStream(source);
                var gz = new GZIPInputStream(raw)) {
            return TarArchive.read(gz);
        } catch (IOException e) {
            throw new TimeTravelException("could not read backup archive " + source, e);
        }
    }

    private Map<String, List<ObjectNode>> parseEntries(Map<String, String> entries) {
        // Validate the manifest so an arbitrary/incompatible file is rejected before we truncate.
        String manifest = entries.get(MANIFEST_ENTRY);
        if (manifest == null) {
            throw new TimeTravelException("not an agent-memory backup: missing " + MANIFEST_ENTRY);
        }
        JsonNode m = json.readTree(manifest);
        if (m.path("version").asInt(-1) != FORMAT_VERSION) {
            throw new TimeTravelException(
                    "unsupported backup format version " + m.path("version").asInt(-1)
                            + " (expected " + FORMAT_VERSION + ")");
        }

        Map<String, List<ObjectNode>> byTable = new LinkedHashMap<>();
        for (Table t : BackupTables.ALL) {
            String body = entries.get(t.name() + ".jsonl");
            List<ObjectNode> rows = new ArrayList<>();
            if (body != null && !body.isBlank()) {
                for (String line : body.split("\n")) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node = json.readTree(line);
                    if (node instanceof ObjectNode obj) {
                        rows.add(obj);
                    }
                }
            }
            byTable.put(t.name(), rows);
        }
        return byTable;
    }

    /** Reads a backup archive's manifest without restoring — used for the archive size check in tests. */
    static byte[] tarBytes(Map<String, String> entries) {
        try {
            return TarArchive.toBytes(entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Decompress a {@code .tar.gz} backup into its entry map (test/inspection helper). */
    static Map<String, String> readEntries(byte[] gzippedTar) {
        try (var in = new GZIPInputStream(new ByteArrayInputStream(gzippedTar))) {
            return TarArchive.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
