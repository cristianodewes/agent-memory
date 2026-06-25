package com.agentmemory.timetravel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gathers the existing-history signals a {@code bootstrap} feeds to the LLM (issue #34): the recent
 * {@code git log}, the README, {@code docs/} headers, source-module file headers, and project rule
 * files ({@code CLAUDE.md}, {@code AGENTS.md}, {@code .cursorrules}). It is read-only and strictly
 * bounded — every section is capped so the assembled digest fits a single LLM pass regardless of repo
 * size (large files are truncated, the file count is limited, deep trees are walked shallowly).
 *
 * <p>Best-effort by design: a missing {@code .git}, an unreadable file or a non-repo path yields an
 * empty section rather than an error, so bootstrap degrades gracefully on a sparse project.
 */
final class RepoDigest {

    private static final Logger log = LoggerFactory.getLogger(RepoDigest.class);

    /** Recent commits to include from {@code git log}. */
    private static final int MAX_COMMITS = 80;
    /** Per-file character cap (READMEs/rule files can be long). */
    private static final int MAX_FILE_CHARS = 8_000;
    /** Characters of each source file's leading header comment to sample. */
    private static final int MODULE_HEADER_CHARS = 600;
    /** Max source-module headers to sample. */
    private static final int MAX_MODULE_HEADERS = 40;
    /** Max markdown docs to include from docs/. */
    private static final int MAX_DOCS = 20;
    /** Overall digest character ceiling (a backstop over the per-section caps). */
    private static final int MAX_DIGEST_CHARS = 60_000;

    private static final List<String> README_NAMES =
            List.of("README.md", "README.rst", "README.txt", "README");
    private static final List<String> RULE_FILES =
            List.of("CLAUDE.md", "AGENTS.md", ".cursorrules", ".windsurfrules", "CONTRIBUTING.md");
    private static final List<String> SOURCE_EXTENSIONS =
            List.of(".java", ".go", ".py", ".ts", ".tsx", ".js", ".rs", ".kt", ".rb", ".c", ".h",
                    ".cpp", ".cs");

    private final Path repoRoot;

    RepoDigest(Path repoRoot) {
        this.repoRoot = repoRoot.toAbsolutePath().normalize();
    }

    /** Assemble the bounded, human-readable digest the LLM summarizes. */
    String assemble() {
        StringBuilder sb = new StringBuilder();
        section(sb, "GIT LOG (recent commits)", gitLog());
        section(sb, "README", readme());
        section(sb, "PROJECT RULES", ruleFiles());
        section(sb, "DOCS", docs());
        section(sb, "MODULE HEADERS", moduleHeaders());
        String digest = sb.toString();
        if (digest.length() > MAX_DIGEST_CHARS) {
            digest = digest.substring(0, MAX_DIGEST_CHARS) + "\n…(digest truncated)…\n";
        }
        return digest;
    }

    // --- sections --------------------------------------------------------------------------------

    private String gitLog() {
        Path dotGit = repoRoot.resolve(".git");
        if (!Files.exists(dotGit)) {
            return "";
        }
        try (Repository repo = new FileRepositoryBuilder().setGitDir(dotGit.toFile()).build();
                Git git = new Git(repo)) {
            if (repo.resolve("HEAD") == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (RevCommit c : git.log().setMaxCount(MAX_COMMITS).call()) {
                sb.append(c.getName(), 0, 10).append(' ')
                        .append(c.getShortMessage().strip()).append('\n');
                n++;
            }
            log.debug("bootstrap gathered {} commits from {}", n, repoRoot);
            return sb.toString();
        } catch (IOException | GitAPIException e) {
            log.debug("bootstrap could not read git log at {}: {}", repoRoot, e.toString());
            return "";
        }
    }

    private String readme() {
        for (String name : README_NAMES) {
            Path p = repoRoot.resolve(name);
            if (Files.isRegularFile(p)) {
                return readCapped(p);
            }
        }
        return "";
    }

    private String ruleFiles() {
        StringBuilder sb = new StringBuilder();
        for (String name : RULE_FILES) {
            Path p = repoRoot.resolve(name);
            if (Files.isRegularFile(p)) {
                String body = readCapped(p);
                if (!body.isBlank()) {
                    sb.append("### ").append(name).append('\n').append(body).append("\n\n");
                }
            }
        }
        return sb.toString();
    }

    private String docs() {
        Path docsDir = repoRoot.resolve("docs");
        if (!Files.isDirectory(docsDir)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> walk = Files.walk(docsDir, 4)) {
            List<Path> md = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .sorted()
                    .limit(MAX_DOCS)
                    .toList();
            for (Path p : md) {
                sb.append("### ").append(repoRoot.relativize(p).toString().replace('\\', '/'))
                        .append('\n').append(firstChars(readCapped(p), 1_500)).append("\n\n");
            }
        } catch (IOException e) {
            return sb.toString();
        }
        return sb.toString();
    }

    private String moduleHeaders() {
        StringBuilder sb = new StringBuilder();
        try (Stream<Path> walk = Files.walk(repoRoot, 8)) {
            List<Path> sources = walk.filter(Files::isRegularFile)
                    .filter(RepoDigest::isSourceFile)
                    .filter(p -> !isUnderHidden(p))
                    .sorted()
                    .limit(MAX_MODULE_HEADERS)
                    .toList();
            for (Path p : sources) {
                String header = firstChars(readCapped(p), MODULE_HEADER_CHARS).strip();
                if (!header.isBlank()) {
                    sb.append("### ").append(repoRoot.relativize(p).toString().replace('\\', '/'))
                            .append('\n').append(header).append("\n\n");
                }
            }
        } catch (IOException e) {
            return sb.toString();
        }
        return sb.toString();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static boolean isSourceFile(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return SOURCE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private boolean isUnderHidden(Path p) {
        Path rel = repoRoot.relativize(p);
        for (Path seg : rel) {
            String s = seg.toString();
            if (s.startsWith(".") || s.equals("node_modules") || s.equals("target")
                    || s.equals("build") || s.equals("vendor") || s.equals("dist")) {
                return true;
            }
        }
        return false;
    }

    private static String readCapped(Path p) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return firstChars(text, MAX_FILE_CHARS);
        } catch (IOException e) {
            return "";
        } catch (OutOfMemoryError e) {
            throw new UncheckedIOException(new IOException("file too large to read: " + p));
        }
    }

    private static String firstChars(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n…(truncated)…";
    }

    private static void section(StringBuilder sb, String title, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        sb.append("===== ").append(title).append(" =====\n").append(body.strip()).append("\n\n");
    }
}
