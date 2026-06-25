package com.agentmemory.consolidate;

import com.agentmemory.core.Identity;
import com.agentmemory.core.Observation;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.Session;
import com.agentmemory.core.SessionId;
import com.agentmemory.llm.ChatMessage;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.ChatResponse;
import com.agentmemory.llm.JsonSchema;
import com.agentmemory.links.WikiLinkService;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.store.PageRecord;
import com.agentmemory.store.PageRepository;
import com.agentmemory.wiki.WikiWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compiles a finished session's raw observations into a durable {@code sessions/<id>.md} wiki page
 * (issue #18) — the first "compile, don't retrieve" step. Always LLM-driven; there is no rule-based
 * fallback (DD-005, invariant #13).
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>Load the session's <em>sanitized</em> observations ({@link SessionObservationReader}).</li>
 *   <li><strong>Token budget</strong>: render them to a transcript; if it exceeds the configured
 *       character budget, summarize each over-budget chunk with the LLM (map), then synthesize from
 *       the concatenated chunk summaries (reduce) so a long session still fits the model context.</li>
 *   <li><strong>Synthesize</strong>: one structured-JSON LLM call constrained to
 *       {@link SynthesizedSession#SCHEMA_JSON} (invariant #7), parsed + validated
 *       ({@link SessionSynthesisParser}).</li>
 *   <li><strong>Render + write</strong>: render to markdown ({@link SessionMarkdownRenderer}) and
 *       write {@code sessions/<id>.md} through {@link PageRepository#create} with the
 *       {@link WikiWriter} callback, so the DB row and the markdown file + git commit land in one
 *       atomic operation (#12/#13), then maintain its wikilink graph via
 *       {@link WikiLinkService#syncPageLinks} (#27) so any {@code [[links]]} in the synthesized page
 *       become live edges.</li>
 * </ol>
 *
 * <h2>Idempotency</h2>
 * The session page path is deterministic ({@code sessions/<sessionId>.md}). Each synthesis stamps the
 * observation count it was built from into the page (a {@code <!-- obs:N -->} marker). Re-running
 * {@link #synthesize} for a session whose latest page already reflects the current observation count
 * is a no-op: no LLM call, no new page version. This makes re-firing {@code session-end} /
 * {@code pre-compact} idempotent (the acceptance criterion). A session that has gained observations
 * since (or has none yet) is (re)synthesized.
 */
public class SessionSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(SessionSynthesizer.class);

    /**
     * Default per-chunk character budget for the map step. ~4 chars/token ⇒ ~12k tokens of transcript
     * per chunk, comfortably inside any current model context while keeping chunk count low. Sessions
     * whose whole transcript fits this are synthesized in a single pass (no map step).
     */
    public static final int DEFAULT_CHAR_BUDGET = 48_000;

    /**
     * Safety cap on map-reduce collapse rounds for a pathologically long session. Each round shrinks
     * the working text by roughly the summarization ratio, so a handful of rounds collapses any
     * realistic session; the cap only guards against non-convergence (e.g. a provider that echoes its
     * input), after which a budget-bounded reduce input is synthesized rather than looping forever.
     */
    static final int MAX_REDUCE_ROUNDS = 4;

    private final SessionObservationReader reader;
    private final LlmProvider llm;
    private final PageRepository pages;
    private final WikiWriter wikiWriter;
    private final WikiLinkService links;
    private final SynthesisPrompts prompts;
    private final SessionSynthesisParser parser;
    private final SessionMarkdownRenderer renderer;
    private final int charBudget;

    public SessionSynthesizer(
            SessionObservationReader reader,
            LlmProvider llm,
            PageRepository pages,
            WikiWriter wikiWriter,
            WikiLinkService links) {
        this(reader, llm, pages, wikiWriter, links, new SynthesisPrompts(),
                new SessionSynthesisParser(), new SessionMarkdownRenderer(), DEFAULT_CHAR_BUDGET);
    }

    SessionSynthesizer(
            SessionObservationReader reader,
            LlmProvider llm,
            PageRepository pages,
            WikiWriter wikiWriter,
            WikiLinkService links,
            SynthesisPrompts prompts,
            SessionSynthesisParser parser,
            SessionMarkdownRenderer renderer,
            int charBudget) {
        if (charBudget <= 0) {
            throw new IllegalArgumentException("charBudget must be > 0, was " + charBudget);
        }
        this.reader = reader;
        this.llm = llm;
        this.pages = pages;
        this.wikiWriter = wikiWriter;
        this.links = links;
        this.prompts = prompts;
        this.parser = parser;
        this.renderer = renderer;
        this.charBudget = charBudget;
    }

    /**
     * The deterministic page path for a session's synthesized page: {@code sessions/<sessionId>.md}.
     *
     * @param sessionId the session id.
     * @return the page path.
     */
    public static PagePath pagePathFor(SessionId sessionId) {
        return PagePath.of("sessions/" + sessionId.value() + ".md");
    }

    /**
     * Synthesize (or refresh) the session page for {@code sessionId}. Idempotent per session: a no-op
     * when the latest page already reflects the current observation count.
     *
     * @param sessionId the session to consolidate; never null.
     * @return the synthesis outcome (written / skipped / no observations).
     */
    public SynthesisOutcome synthesize(SessionId sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
        Session session = reader.findSession(sessionId).orElse(null);
        List<Observation> observations = reader.observationsFor(sessionId);
        if (observations.isEmpty()) {
            log.debug("session {} has no observations; nothing to synthesize", sessionId);
            return SynthesisOutcome.noObservations(sessionId);
        }

        Identity pageIdentity = pageIdentity(sessionId, session, observations);
        int obsCount = observations.size();
        String fingerprint = fingerprint(observations);

        // Idempotency: if a page already exists for this session and its stamped fingerprint matches
        // the current observations, re-running is a no-op (no LLM call, no new version). The
        // fingerprint is count + the latest observation timestamp, so a change that keeps the count
        // equal (a delete + a new capture) is still detected as "stale" and re-synthesized.
        Optional<PageRecord> existing = pages.readLatest(pageIdentity);
        if (existing.isPresent() && fingerprint.equals(synthFingerprint(existing.get().page().body()))) {
            log.debug("session {} already synthesized (fingerprint {}); skipping", sessionId, fingerprint);
            return SynthesisOutcome.skipped(sessionId, existing.get());
        }

        // Token budget: synthesize directly when the whole transcript fits, otherwise map-reduce.
        String transcript = ObservationTranscript.renderAll(observations);
        SynthesizedSession synthesis = transcript.length() <= charBudget
                ? synthesizeFromTranscript(transcript)
                : synthesizeFromChunks(observations);

        String body = renderer.render(session, synthesis) + fingerprintMarkerLine(fingerprint);
        String commitMessage = "consolidate: " + pageIdentity.page().value();
        PageRecord written = pages.create(
                pageIdentity, synthesis.title(), body, wikiWriter.callbackFor(commitMessage));
        // Maintain the wikilink graph for the synthesized page (#27): record its outgoing [[links]]
        // and re-point any inbound links now that this page exists.
        links.syncPageLinks(written);
        log.info("synthesized session {} -> {} ({} observations)",
                sessionId, pageIdentity.page().value(), obsCount);
        return SynthesisOutcome.written(sessionId, written, synthesis);
    }

    // --- LLM synthesis ---------------------------------------------------------------------------

    /** One structured-JSON synthesis call over a transcript (or the joined chunk summaries). */
    private SynthesizedSession synthesizeFromTranscript(String transcript) {
        List<ChatMessage> messages = List.of(
                ChatMessage.system(prompts.synthesisSystem()),
                ChatMessage.user("Session observations (chronological):\n\n" + transcript));
        ChatRequest request = ChatRequest.structured(
                messages, new JsonSchema(SynthesizedSession.SCHEMA_NAME, SynthesizedSession.SCHEMA_JSON));
        ChatResponse response = llm.chat(request); // LlmException propagates (no rule-based fallback)
        return parser.parse(response.text());
    }

    /**
     * Map-reduce for a long session: summarize each over-budget chunk (map), then synthesize the
     * final page from the concatenated chunk summaries (reduce). Crucially, the reduce input is itself
     * bounded: if the joined summaries still exceed the budget (a session with very many chunks), they
     * are re-chunked and re-summarized — collapsed iteratively — until they fit, so the final
     * synthesis call never overflows the model context. A round cap guards against pathological
     * non-convergence (e.g. a provider echoing input back); on hitting it, the budget-truncated join
     * is synthesized rather than looping forever.
     */
    private SynthesizedSession synthesizeFromChunks(List<Observation> observations) {
        String transcript = ObservationTranscript.renderAll(observations);
        int round = 0;
        while (transcript.length() > charBudget && round < MAX_REDUCE_ROUNDS) {
            List<String> chunks = ObservationTranscript.chunkText(transcript, charBudget);
            log.debug("collapse round {}: summarizing {} chunks (transcript {} chars > budget {})",
                    round, chunks.size(), transcript.length(), charBudget);
            List<String> summaries = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                List<ChatMessage> messages = List.of(
                        ChatMessage.system(prompts.chunkSummarySystem()),
                        ChatMessage.user(
                                "Session chunk " + (i + 1) + " of " + chunks.size() + ":\n\n"
                                        + chunks.get(i)));
                ChatResponse response = llm.chat(ChatRequest.text(messages));
                summaries.add("Chunk " + (i + 1) + ":\n" + response.text().strip());
            }
            transcript = String.join("\n\n", summaries);
            round++;
        }
        if (transcript.length() > charBudget) {
            // Did not converge within the round cap; synthesize from a budget-bounded prefix so the
            // final call still fits (better a slightly clipped reduce than an oversized request).
            log.warn("session summaries did not fit the budget after {} collapse rounds; "
                    + "synthesizing from a truncated reduce input", MAX_REDUCE_ROUNDS);
            transcript = transcript.substring(0, charBudget);
        }
        return synthesizeFromTranscript(transcript);
    }

    // --- identity / idempotency fingerprint ------------------------------------------------------

    /** Build the page identity from the session (preferred) or, failing that, the observations. */
    private static Identity pageIdentity(
            SessionId sessionId, Session session, List<Observation> observations) {
        Identity scope = session != null ? session.identity() : observations.get(0).identity();
        return Identity.ofPage(scope.workspace(), scope.project(), pagePathFor(sessionId));
    }

    private static final String MARKER_PREFIX = "<!-- synth:";

    /**
     * A freshness fingerprint for a session's observations: {@code <count>@<latest-created-at-millis>}.
     * Count alone would miss a change that keeps the count equal (a delete plus a new capture); adding
     * the latest timestamp catches that, since any newly captured observation has a later
     * {@code created_at}. Observations are loaded oldest-first, so the last one carries the max time.
     */
    private static String fingerprint(List<Observation> observations) {
        long latestMillis = observations.get(observations.size() - 1).createdAt().toEpochMilli();
        return observations.size() + "@" + latestMillis;
    }

    /** The hidden marker line appended to the body recording the fingerprint synthesized from. */
    private static String fingerprintMarkerLine(String fingerprint) {
        return "\n" + MARKER_PREFIX + fingerprint + " -->\n";
    }

    /**
     * Extract the fingerprint an existing page was synthesized from, or {@code null} if the marker is
     * absent/malformed (treated as "stale" so the page is rebuilt). The trailing occurrence wins, so a
     * stray earlier marker in human-edited content cannot shadow the authoritative one.
     */
    static String synthFingerprint(String body) {
        if (body == null) {
            return null;
        }
        int start = body.lastIndexOf(MARKER_PREFIX);
        if (start < 0) {
            return null;
        }
        int end = body.indexOf("-->", start);
        if (end < 0) {
            return null;
        }
        String value = body.substring(start + MARKER_PREFIX.length(), end).strip();
        return value.isEmpty() ? null : value;
    }
}
