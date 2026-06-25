package com.agentmemory.wiki;

import com.agentmemory.core.Identity;
import java.nio.file.Path;

/**
 * Maps the typed 3-tuple {@link Identity} to a concrete file path under {@code wiki/}, following the
 * data-dir layout (ARCHITECTURE §4.1): {@code wiki/<workspace>/<project>/<page-path>}. The page path
 * is already normalized and traversal-free (see {@code core.PagePath} / {@code PathNormalizer}); we
 * additionally assert the resolved file stays under its project directory as defense in depth, so a
 * bug upstream can never write outside the wiki tree (invariant #10 lives next door to this).
 */
public final class WikiPaths {

    private final Path wikiDir;

    public WikiPaths(Path wikiDir) {
        this.wikiDir = wikiDir.toAbsolutePath().normalize();
    }

    /** @return the wiki root ({@code <data_dir>/wiki}). */
    public Path wikiDir() {
        return wikiDir;
    }

    /** @return {@code wiki/<workspace>/<project>} for a page-scoped identity. */
    public Path projectDir(Identity identity) {
        requirePageScoped(identity);
        return wikiDir.resolve(identity.workspace().value()).resolve(identity.project().value());
    }

    /**
     * Resolve the absolute file path for a page-scoped identity.
     *
     * @param identity page-scoped identity (path required).
     * @return {@code wiki/<workspace>/<project>/<page-path>}, guaranteed under {@link #wikiDir}.
     */
    public Path resolve(Identity identity) {
        Path projectDir = projectDir(identity);
        Path file = projectDir.resolve(identity.page().value()).normalize();
        if (!file.startsWith(projectDir.normalize())) {
            throw new IllegalArgumentException(
                    "resolved page path escapes its project directory: " + identity.page().value());
        }
        return file;
    }

    /**
     * Inverse of {@link #resolve}: given an absolute file under {@code wiki/}, recover the
     * {@code (workspace, project, page-path)} components, or {@code null} if it does not sit at the
     * expected {@code wiki/<ws>/<project>/<path...>} depth (e.g. a stray top-level file).
     *
     * @param file an absolute path somewhere under {@link #wikiDir}.
     * @return the relative components {@code [workspace, project, pagePath]}, or {@code null}.
     */
    public String[] componentsOf(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        if (!abs.startsWith(wikiDir)) {
            return null;
        }
        Path rel = wikiDir.relativize(abs);
        if (rel.getNameCount() < 3) {
            return null; // need at least workspace/project/<file>
        }
        String workspace = rel.getName(0).toString();
        String project = rel.getName(1).toString();
        Path pagePath = rel.subpath(2, rel.getNameCount());
        // Page paths are stored with forward slashes regardless of OS.
        String page = pagePath.toString().replace('\\', '/');
        return new String[] {workspace, project, page};
    }

    private static void requirePageScoped(Identity identity) {
        if (identity == null || !identity.isPageScoped()) {
            throw new IllegalArgumentException("wiki path requires a page-scoped identity");
        }
    }
}
