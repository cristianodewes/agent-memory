package com.agentmemory.timetravel;

import com.agentmemory.core.Identity;
import java.nio.file.Path;

/**
 * Seeds a project's memory from its <em>existing</em> history (issue #34; Survey §2.11) — for
 * projects whose codebase predates agent-memory. It collects {@code git log}, the README,
 * {@code docs/}, source-module headers and project rule files ({@link RepoDigest}), asks the LLM to
 * compile them into a handful of seed wiki pages in a <strong>single structured pass</strong>
 * (invariant #7, DD-005), and writes those pages through the normal page-write path (DB row + atomic
 * markdown file + git commit, #12/#13).
 *
 * <p>Bootstrap mutates state (it writes pages), so it performs the live-process check (invariant #9):
 * it refuses when a <em>foreign</em> agent-memory process holds the data dir (a second instance might
 * be writing concurrently). It does not refuse for the running server itself — bootstrap is normally
 * invoked against the live server and writes through the same serialized page path as any other write.
 */
public interface BootstrapService {

    /**
     * Bootstrap seed pages for {@code (workspace, project)} from the repository at {@code repoRoot}.
     *
     * @param workspace the target workspace slug.
     * @param project   the target project slug.
     * @param repoRoot  the source repository directory to mine for history.
     * @return the seed pages written (or a refusal when a foreign live holder is present).
     */
    BootstrapResult bootstrap(String workspace, String project, Path repoRoot);
}
