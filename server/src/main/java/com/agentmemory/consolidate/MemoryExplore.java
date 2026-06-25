package com.agentmemory.consolidate;

import com.agentmemory.llm.ChatMessage;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.ChatResponse;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.mcp.McpReadRepository;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import java.util.List;
import java.util.Optional;

/**
 * {@code memory_explore} (issue #19): a calibrated PROSE digest of a project's compiled memory. Builds
 * the same structured snapshot {@code memory_briefing} (#17) exposes — counts, recent-activity windows,
 * the {@code _rules/}/{@code _slots/} listings, and the most recent pages — then adds <em>one</em> LLM
 * call to turn it into a short narrative whose verbosity scales with how long it has been since the
 * project's last activity (fresh ⇒ a line; stale ⇒ a fuller catch-up).
 *
 * <p>Distinct from {@code memory_briefing}, which is the raw JSON snapshot with no LLM call; explore is
 * the prose layer on top. Required LLM (DD-005/#13): the digest is always model-written.
 */
public class MemoryExplore {

    /** A project is "stale" (warrants a fuller catch-up) past this many days since last activity. */
    static final long STALE_DAYS = 30;

    /** How many recent pages to include in the snapshot the model summarizes. */
    private static final int RECENT_PAGES = 12;

    private final LlmProvider llm;
    private final McpReadRepository reads;
    private final PageRepository pages;
    private final ConsolidationPrompts prompts;

    public MemoryExplore(LlmProvider llm, McpReadRepository reads, PageRepository pages) {
        this(llm, reads, pages, new ConsolidationPrompts());
    }

    MemoryExplore(LlmProvider llm, McpReadRepository reads, PageRepository pages,
            ConsolidationPrompts prompts) {
        this.llm = llm;
        this.reads = reads;
        this.pages = pages;
        this.prompts = prompts;
    }

    /**
     * Produce a calibrated prose digest for {@code scope}.
     *
     * @param scope the project; never null.
     * @return the digest result (the prose + the staleness tier the snapshot was built at).
     */
    public ExploreResult explore(Scope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        Optional<Long> daysSince = reads.daysSinceLastActivity(scope);
        String snapshot = buildSnapshot(scope, daysSince);

        List<ChatMessage> messages = List.of(
                ChatMessage.system(prompts.exploreSystem()),
                ChatMessage.user(snapshot));
        ChatResponse response = llm.chat(ChatRequest.text(messages)); // free-text; LlmException propagates
        String prose = response.text() == null ? "" : response.text().strip();
        return new ExploreResult(scope.workspaceSlug(), scope.projectSlug(), tier(daysSince), prose);
    }

    /** Build the compact text snapshot the model calibrates its digest from. */
    private String buildSnapshot(Scope scope, Optional<Long> daysSince) {
        McpReadRepository.Counts c = reads.counts(scope);
        long last7 = reads.observationsInLastDays(scope, 7);
        long last30 = reads.observationsInLastDays(scope, 30);
        List<String> rules = reads.latestPathsUnder(scope, "_rules/", 50);
        List<String> slots = reads.latestPathsUnder(scope, "_slots/", 50);
        List<PageRecord> recent = pages.listLatest(scope.workspace(), scope.project())
                .stream().limit(RECENT_PAGES).toList();

        StringBuilder sb = new StringBuilder();
        sb.append("Project: ").append(scope.workspaceSlug()).append('/').append(scope.projectSlug())
                .append('\n');
        sb.append("Activity: ")
                .append(daysSince.map(d -> d + " day(s) since last activity").orElse("no activity yet"))
                .append(" (staleness: ").append(tier(daysSince)).append(")\n");
        sb.append("Counts: ").append(c.pages()).append(" pages, ").append(c.observations())
                .append(" observations, ").append(c.sessions()).append(" sessions, ")
                .append(c.links()).append(" links\n");
        sb.append("Observations last 7d: ").append(last7).append("; last 30d: ").append(last30).append('\n');
        if (!rules.isEmpty()) {
            sb.append("Rules: ").append(String.join(", ", rules)).append('\n');
        }
        if (!slots.isEmpty()) {
            sb.append("Slots: ").append(String.join(", ", slots)).append('\n');
        }
        sb.append("\nMost recent pages (newest first):\n");
        if (recent.isEmpty()) {
            sb.append("(no pages yet)\n");
        } else {
            for (PageRecord p : recent) {
                sb.append("- ").append(p.page().path().value()).append(" — ")
                        .append(p.page().title()).append('\n');
            }
        }
        return sb.toString();
    }

    /** The staleness tier label used both in the snapshot and returned to the caller. */
    private static String tier(Optional<Long> daysSince) {
        if (daysSince.isEmpty()) {
            return "new";
        }
        long d = daysSince.get();
        if (d <= 2) {
            return "fresh";
        }
        return d >= STALE_DAYS ? "stale" : "warm";
    }

    /**
     * The result of an explore: the resolved scope, the staleness tier the digest was calibrated at,
     * and the model's prose.
     *
     * @param workspace the workspace slug.
     * @param project   the project slug.
     * @param staleness the tier: {@code new} / {@code fresh} / {@code warm} / {@code stale}.
     * @param digest    the calibrated prose digest.
     */
    public record ExploreResult(String workspace, String project, String staleness, String digest) {}
}
