package com.agentmemory.timetravel;

import com.agentmemory.core.Identity;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.lifecycle.ProcessLock;
import com.agentmemory.llm.ChatMessage;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.ChatResponse;
import com.agentmemory.llm.JsonSchema;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.WikiWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Default {@link BootstrapService}: gathers the repo digest ({@link RepoDigest}), runs one
 * structured-output LLM pass to compile seed pages, and writes them through {@link PageRepository}
 * with the {@link WikiWriter} callback (issue #34).
 *
 * <p>A single {@link LlmProvider#chat} call is made (the "one-shot" requirement). The reply is held to
 * {@link SeedPage#SCHEMA_JSON}; each returned page is validated ({@link PagePath} rejects traversal /
 * malformed paths) before it is written. Writing reuses the consolidation/store write path, so each
 * seed page is a normal versioned page with an atomic file + git commit.
 */
public class DefaultBootstrapService implements BootstrapService {

    private static final Logger log = LoggerFactory.getLogger(DefaultBootstrapService.class);

    /** Hard cap on seed pages written from a single pass (a runaway model cannot flood the wiki). */
    private static final int MAX_SEED_PAGES = 24;

    private static final String SYSTEM_PROMPT = """
            You are bootstrapping the long-term memory of a software project that predates this memory
            system. You are given a digest of the project's existing history: recent git log, the
            README, project rule files, docs and source-module headers.

            Compile this into a small set of durable, high-signal seed pages (typically 3-8) that an
            engineer joining the project would want: an architecture/overview page, key concepts or
            subsystems, important conventions/rules, and notable decisions evident from the history.

            Rules:
            - Write Markdown bodies (no frontmatter — it is added for you).
            - Use relative page paths like "concepts/architecture.md", "decisions/overview.md",
              "conventions/rules.md". Paths must be relative, use forward slashes, end in .md, and
              contain no "..".
            - Prefer a few well-organized pages over many thin ones. Do not invent facts not supported
              by the digest. If the digest is sparse, produce fewer pages.
            - Each page needs a concise title and a useful, self-contained body.
            """;

    private final LlmProvider llm;
    private final PageRepository pages;
    private final WikiWriter wikiWriter;
    private final Path dataDir;
    private final ObjectMapper json = JsonMapper.builder().build();

    public DefaultBootstrapService(LlmProvider llm, PageRepository pages, WikiWriter wikiWriter,
                                   Path dataDir) {
        this.llm = llm;
        this.pages = pages;
        this.wikiWriter = wikiWriter;
        this.dataDir = dataDir.toAbsolutePath().normalize();
    }

    @Override
    public BootstrapResult bootstrap(String workspace, String project, Path repoRoot) {
        Identity target = Identity.ofProject(WorkspaceId.of(workspace), ProjectId.of(project));

        // Invariant #9: refuse if a FOREIGN live process holds the data dir (a second instance may be
        // writing). The running server itself (our own pid) is fine — bootstrap writes through the
        // normal serialized page path.
        long ownPid = ProcessHandle.current().pid();
        Optional<Long> foreign = ProcessLock.detectLiveHolder(dataDir, ownPid);
        if (foreign.isPresent()) {
            log.warn("bootstrap refused: foreign live data-dir holder pid {}", foreign.get());
            return BootstrapResult.refused(target, foreign.get());
        }

        String digest = new RepoDigest(repoRoot).assemble();
        if (digest.isBlank()) {
            log.info("bootstrap: repo {} yielded an empty digest; nothing to seed", repoRoot);
            return new BootstrapResult(target, List.of(), true,
                    "no usable history found at " + repoRoot + "; no seed pages written");
        }

        List<SeedPage> seeds = compileSeedPages(digest);
        List<String> written = writeSeedPages(workspace, project, seeds);
        log.info("bootstrap wrote {} seed page(s) for {}/{} from {}",
                written.size(), workspace, project, repoRoot);
        return new BootstrapResult(target, List.copyOf(written), true,
                "wrote " + written.size() + " seed page(s)");
    }

    // --- LLM pass --------------------------------------------------------------------------------

    private List<SeedPage> compileSeedPages(String digest) {
        List<ChatMessage> messages = List.of(
                ChatMessage.system(SYSTEM_PROMPT),
                ChatMessage.user("Project digest:\n\n" + digest));
        ChatRequest request = ChatRequest.structured(
                messages, new JsonSchema(SeedPage.SCHEMA_NAME, SeedPage.SCHEMA_JSON));
        ChatResponse response = llm.chat(request); // LlmException propagates (LLM is required, DD-005)
        return parseSeedPages(response.text());
    }

    private List<SeedPage> parseSeedPages(String replyJson) {
        JsonNode root;
        try {
            root = json.readTree(replyJson);
        } catch (RuntimeException e) {
            throw new TimeTravelException("bootstrap LLM reply was not valid JSON", e);
        }
        JsonNode pagesNode = root.path("pages");
        if (!pagesNode.isArray()) {
            throw new TimeTravelException("bootstrap LLM reply missing a 'pages' array");
        }
        List<SeedPage> out = new ArrayList<>();
        for (JsonNode p : pagesNode) {
            String path = textOrBlank(p, "path");
            String title = textOrBlank(p, "title");
            String body = textOrBlank(p, "body");
            if (path.isBlank() || title.isBlank()) {
                continue; // skip malformed entries rather than failing the whole pass
            }
            out.add(new SeedPage(path, title, body));
        }
        return out;
    }

    // --- write path ------------------------------------------------------------------------------

    private List<String> writeSeedPages(String workspace, String project, List<SeedPage> seeds) {
        WorkspaceId ws = WorkspaceId.of(workspace);
        ProjectId proj = ProjectId.of(project);
        Set<String> writtenPaths = new LinkedHashSet<>();
        int count = 0;
        for (SeedPage seed : seeds) {
            if (count >= MAX_SEED_PAGES) {
                log.warn("bootstrap: seed-page cap {} reached; ignoring extra pages", MAX_SEED_PAGES);
                break;
            }
            PagePath path;
            try {
                path = PagePath.of(seed.path());
            } catch (RuntimeException e) {
                log.warn("bootstrap: skipping seed page with invalid path '{}': {}",
                        seed.path(), e.getMessage());
                continue;
            }
            if (!writtenPaths.add(path.value())) {
                continue; // de-duplicate repeated paths from the model
            }
            Identity identity = Identity.ofPage(ws, proj, path);
            String message = "bootstrap: " + path.value();
            pages.create(identity, seed.title(), seed.body(), wikiWriter.callbackFor(message));
            count++;
        }
        return new ArrayList<>(writtenPaths);
    }

    private static String textOrBlank(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isString() ? v.stringValue() : "";
    }
}
