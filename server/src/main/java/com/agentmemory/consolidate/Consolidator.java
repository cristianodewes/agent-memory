package com.agentmemory.consolidate;

import com.agentmemory.core.Identity;
import com.agentmemory.core.Observation;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.SessionId;
import com.agentmemory.llm.ChatMessage;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.ChatResponse;
import com.agentmemory.llm.JsonSchema;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.mcp.McpReadRepository;
import com.agentmemory.mcp.MemoryWriteService;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM consolidation (issue #19): promotes a session's durable, reusable knowledge into long-lived
 * wiki pages across the {@code concepts/}/{@code decisions/}/{@code gotchas/}/{@code procedures/}
 * folders — the richer half of "compile, don't retrieve", distinct from the single
 * {@code sessions/<id>.md} synthesis ({@link SessionSynthesizer}, #18).
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>Read the session's <em>sanitized</em> observations ({@link SessionObservationReader}) and
 *       render them to a transcript (budget-bounded; consolidation is a distillation, not a faithful
 *       replay, so an oversized session is truncated rather than map-reduced).</li>
 *   <li>One structured-JSON LLM call constrained to {@link ConsolidatedPages#SCHEMA_JSON}
 *       (invariant #7; required LLM, no rule-based fallback — DD-005/#13), parsed + validated
 *       ({@link ConsolidatedPagesParser}). Existing durable page paths are passed for context so the
 *       model updates a page rather than duplicating it.</li>
 *   <li><strong>Atomic multi-page fan-out</strong>: the pages are written via
 *       {@link MemoryWriteService#writePages} — all rows + all files + one git commit in one
 *       transaction, all-or-nothing (#10/#3). Each supersedes any prior version at its path (#12);
 *       forward {@code [[links]]} between the new pages resolve (#27).</li>
 * </ol>
 *
 * <p>{@code multiPage=false} keeps only the first page the model returned (the single-page request the
 * issue calls out); {@code multiPage=true} writes the whole fan-out.
 */
public class Consolidator {

    private static final Logger log = LoggerFactory.getLogger(Consolidator.class);

    /**
     * Character budget for the transcript fed to the consolidation call (~4 chars/token ⇒ ~12k tokens).
     * A session larger than this is truncated to the most recent budget worth of transcript — recent
     * activity is the most consolidation-relevant, and unlike synthesis (#18) we do not need a faithful
     * whole-session record here.
     */
    public static final int DEFAULT_CHAR_BUDGET = 48_000;

    /** How many existing durable page paths (per folder) to show the model as update context. */
    private static final int EXISTING_PATHS_PER_FOLDER = 50;

    private final SessionObservationReader reader;
    private final LlmProvider llm;
    private final MemoryWriteService writes;
    private final McpReadRepository reads;
    private final ConsolidationPrompts prompts;
    private final ConsolidatedPagesParser parser;
    private final int charBudget;

    public Consolidator(
            SessionObservationReader reader,
            LlmProvider llm,
            MemoryWriteService writes,
            McpReadRepository reads) {
        this(reader, llm, writes, reads, new ConsolidationPrompts(), new ConsolidatedPagesParser(),
                DEFAULT_CHAR_BUDGET);
    }

    Consolidator(
            SessionObservationReader reader,
            LlmProvider llm,
            MemoryWriteService writes,
            McpReadRepository reads,
            ConsolidationPrompts prompts,
            ConsolidatedPagesParser parser,
            int charBudget) {
        if (charBudget <= 0) {
            throw new IllegalArgumentException("charBudget must be > 0, was " + charBudget);
        }
        this.reader = reader;
        this.llm = llm;
        this.writes = writes;
        this.reads = reads;
        this.prompts = prompts;
        this.parser = parser;
        this.charBudget = charBudget;
    }

    /**
     * Consolidate {@code session} into durable pages in {@code scope}.
     *
     * @param scope     the project to write into; never null.
     * @param session   the session whose material to consolidate; never null.
     * @param multiPage whether to write the whole fan-out ({@code true}) or only the first page.
     * @param actor     who is performing the write (audit), e.g. {@link MemoryWriteService#ACTOR_MCP}.
     * @return the consolidation outcome (the written pages, or "no observations").
     * @throws ConsolidationException if the LLM reply cannot be parsed/validated or the write fails.
     */
    public ConsolidationOutcome consolidate(Scope scope, SessionId session, boolean multiPage, String actor) {
        List<ConsolidatedPages.Page> chosen = proposePages(scope, session, multiPage);
        if (chosen.isEmpty()) {
            return ConsolidationOutcome.noObservations(session);
        }

        List<MemoryWriteService.PageWrite> pageWrites = new ArrayList<>(chosen.size());
        for (ConsolidatedPages.Page p : chosen) {
            Identity id = Identity.ofPage(scope.workspace(), scope.project(), PagePath.of(p.path()));
            pageWrites.add(new MemoryWriteService.PageWrite(id, p.title(), p.body()));
        }

        // Atomic fan-out: all pages (rows + files + one commit) or none.
        List<PageRecord> written = writes.writePages(pageWrites, actor);
        log.info("consolidated session {} into {} durable page(s) (multiPage={})",
                session.value(), written.size(), multiPage);
        return ConsolidationOutcome.written(session, written);
    }

    /**
     * Distil {@code session} into its durable pages <em>without writing them</em> — the propose-only half
     * of {@link #consolidate}, for the auto-improve approval loop (issue #30), which stages each page in
     * {@code pending_writes} for human review + the eval gate instead of committing it directly. Same LLM
     * distillation and {@code multiPage} selection as {@link #consolidate}; only the atomic write is omitted.
     *
     * @param scope     the project the pages target; never null.
     * @param session   the session to distil; never null.
     * @param multiPage whether to return the whole fan-out ({@code true}) or only the first page.
     * @return the proposed durable pages, or an empty list when the session has no observations.
     * @throws ConsolidationException if the LLM reply cannot be parsed/validated.
     */
    public List<ConsolidatedPages.Page> proposePages(Scope scope, SessionId session, boolean multiPage) {
        if (scope == null || session == null) {
            throw new IllegalArgumentException("scope and session must not be null");
        }
        List<Observation> observations = reader.observationsFor(session);
        if (observations.isEmpty()) {
            log.debug("session {} has no observations; nothing to propose", session.value());
            return List.of();
        }
        ConsolidatedPages result = callLlm(scope, observations, multiPage);
        return multiPage ? result.pages() : List.of(result.pages().get(0)); // single-page: take the first
    }

    // --- LLM call -------------------------------------------------------------------------------

    private ConsolidatedPages callLlm(Scope scope, List<Observation> observations, boolean multiPage) {
        String transcript = ObservationTranscript.renderAll(observations);
        if (transcript.length() > charBudget) {
            // Keep the most recent budget worth of transcript (consolidation favours recent activity).
            transcript = transcript.substring(transcript.length() - charBudget);
        }
        String user = buildUserPrompt(scope, transcript, multiPage);
        List<ChatMessage> messages = List.of(
                ChatMessage.system(prompts.consolidationSystem()),
                ChatMessage.user(user));
        ChatRequest request = ChatRequest.structured(
                messages, new JsonSchema(ConsolidatedPages.SCHEMA_NAME, ConsolidatedPages.SCHEMA_JSON));
        ChatResponse response = llm.chat(request); // LlmException propagates (no rule-based fallback)
        return parser.parse(response.text());
    }

    /** Build the consolidation user prompt: existing durable pages (update context) + the transcript. */
    private String buildUserPrompt(Scope scope, String transcript, boolean multiPage) {
        StringBuilder sb = new StringBuilder();
        List<String> existing = existingDurablePaths(scope);
        if (!existing.isEmpty()) {
            sb.append("Existing durable pages in this project (prefer updating one of these over "
                    + "creating a near-duplicate):\n");
            for (String path : existing) {
                sb.append("- ").append(path).append('\n');
            }
            sb.append('\n');
        }
        if (multiPage) {
            sb.append("Consolidate the session below into one or more durable pages.\n\n");
        } else {
            sb.append("Consolidate the session below into a SINGLE durable page (emit exactly one "
                    + "page in the 'pages' array).\n\n");
        }
        sb.append("Session observations (chronological):\n\n").append(transcript);
        return sb.toString();
    }

    /** The current durable page paths (across the allowed folders) for the model's update context. */
    private List<String> existingDurablePaths(Scope scope) {
        List<String> out = new ArrayList<>();
        for (String folder : ConsolidatedPages.ALLOWED_FOLDERS) {
            out.addAll(reads.latestPathsUnder(scope, folder + "/", EXISTING_PATHS_PER_FOLDER));
        }
        return out;
    }
}
