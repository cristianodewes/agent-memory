package com.agentmemory.timetravel;

import java.nio.file.Path;
import java.util.Map;

/**
 * The outcome of an online {@code backup} (issue #34): the tarball that was written and the per-table
 * row counts it captured. The backup runs while the source database stays writable.
 *
 * @param archive   the absolute path of the {@code .tar.gz} written.
 * @param rowCounts rows captured per table, in backup order.
 * @param totalRows the sum across all tables.
 * @param bytes     the size of the written archive in bytes.
 */
public record BackupResult(Path archive, Map<String, Integer> rowCounts, int totalRows, long bytes) {
}
