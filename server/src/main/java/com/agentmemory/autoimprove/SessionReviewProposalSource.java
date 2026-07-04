package com.agentmemory.autoimprove;

import com.agentmemory.consolidate.ObservationTranscript;
import com.agentmemory.consolidate.SessionObservationReader;
import com.agentmemory.consolidate.SessionSynthesizer;
import com.agentmemory.core.Identity;
import com.agentmemory.core.Observation;
import com.agentmemory.core.SessionId;
import com.agentmemory.llm.ChatMessage;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.ChatResponse;
import com.agentmemory.llm.JsonSchema;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.llm.LlmRateLimitGate;
import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.RecallQuery;
import com.agentmemory.recall.RecallService;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The LLM-backed session-review engine (issue #150): extracts durable, reusable knowledge from each
 * freshly-finished session into candidate wiki pages ({@code concepts/}, {@code decisions/},
 * {@code gotchas/}, {@code procedures/}, {@code _rules/}), and stages the survivors through the
 * approval gate. The "librarian" next to the #29 curator's "janitor".
 *
 * <h2>Pipeline (per session)</h2>
 * <ol>
 *   <li><strong>Material</strong>: prefer the already-synthesized {@code sessions/<id>.md} page (one
 *       cheap read, no LLM); fall back to a budget-bounded raw transcript when the page does not exist
 *       yet. Sessions under {@code review.min-observations} are skipped (claim consumed — a trivial
 *       session IS reviewed, its verdict is "nothing durable").</li>
 *   <li><strong>Phase A — extraction</strong>: one structured-JSON call constrained to
 *       {@link #EXTRACTION_SCHEMA_JSON} (invariant #7). The prompt treats session material as
 *       untrusted data (#136).</li>
 *   <li><strong>Phase B — adversarial verification</strong>: proposals under
 *       {@code review.verify-threshold} confidence face a second, cheap "refute this" call; only
 *       confirmed ones survive. This filters episodic residue BEFORE it is staged — unlike the
 *       ai-memory loop, which stages first and relies on the (optional) eval gate.</li>
 *   <li><strong>Dedup via recall</strong>: each survivor is searched against the project's existing
 *       durable pages ({@link RecallService}, the plain hybrid arm — no LLM); a strong same-folder hit
 *       converts the proposal into an update of that page (supersession, never a duplicate), weaker
 *       hits become {@code [[wikilinks]]} so the graph grows.</li>
 * </ol>
 *
 * <h2>Rate-limit + budget awareness (#148/#150)</h2>
 * {@link #ready(int)} returns {@code false} — stopping the tick for this source without consuming
 * claims — while the shared {@link LlmRateLimitGate} is paused, the per-tick LLM budget
 * ({@code review.max-llm-calls-per-tick}) is spent, or outside the configured quiet-hours window with
 * a small backlog. A 429 mid-review fails that one session's claim (bounded retry) and raises the
 * gate, which then stops the rest of the tick at the next {@code ready()} check.
 *
 * <h2>Report</h2>
 * Rejections and dedup conversions are aggregated per scope during the tick and flushed by
 * {@link #endTick()} as one {@code _pending/auto-improve-report.md} page per affected scope (a stable,
 * versioned path — history via git/time-travel), so the operator can see what the loop decided
 * without reading logs.
 */
public final class SessionReviewProposalSource implements ProposalSource {

    private static final Logger log = LoggerFactory.getLogger(SessionReviewProposalSource.class);

    /** This source's claim namespace ({@code auto_improve_session_review.source}, V16). */
    public static final String SOURCE_ID = "review";

    /** Where the tick report is staged; stable so versions supersede instead of proliferating. */
    static final String REPORT_PATH = "_pending/auto-improve-report.md";

    /** Char budget for fallback raw-transcript material (mirrors the synthesizer's default). */
    private static final int MATERIAL_CHAR_BUDGET = SessionSynthesizer.DEFAULT_CHAR_BUDGET;

    /** Max proposals accepted from one extraction call (the prompt also asks for at most 3). */
    private static final int MAX_PROPOSALS_PER_SESSION = 3;

    /**
     * Dedup thresholds, derived from the hybrid recall's RRF math so they can never drift into dead
     * code: a fused score is the sum of {@code 1/(k+rank)} contributions per arm
     * ({@link com.agentmemory.recall.RrfFusion}, k = {@value com.agentmemory.recall.RrfFusion#DEFAULT_K}),
     * so a single-arm rank-1 hit scores {@code 1/(k+1)} and multi-arm agreement scores above it.
     * UPDATE requires more than one arm's worth of top-rank evidence (~1.5x a single-arm rank-1);
     * RELATED requires roughly a single-arm top-3 hit.
     */
    static final double DEDUP_UPDATE_SCORE =
            1.5 / (com.agentmemory.recall.RrfFusion.DEFAULT_K + 1);

    /** Dedup hits at/above this score (below update strength) become related wikilinks. */
    static final double DEDUP_RELATED_SCORE =
            0.9 / (com.agentmemory.recall.RrfFusion.DEFAULT_K + 1);

    static final String EXTRACTION_SCHEMA_NAME = "session_review_proposals";
    static final String EXTRACTION_SCHEMA_JSON = """
            {
              "type": "object",
              "required": ["proposals"],
              "additionalProperties": false,
              "properties": {
                "proposals": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["kind", "title", "body", "confidence", "evidence"],
                    "additionalProperties": false,
                    "properties": {
                      "kind": {"type": "string", "enum": ["concept", "decision", "gotcha", "procedure", "rule"]},
                      "title": {"type": "string"},
                      "body": {"type": "string"},
                      "confidence": {"type": "number"},
                      "evidence": {"type": "string"}
                    }
                  }
                }
              }
            }""";

    static final String VERIFY_SCHEMA_NAME = "session_review_verdict";
    static final String VERIFY_SCHEMA_JSON = """
            {
              "type": "object",
              "required": ["durable", "reason"],
              "additionalProperties": false,
              "properties": {
                "durable": {"type": "boolean"},
                "reason": {"type": "string"}
              }
            }""";

    private final SessionObservationReader reader;
    private final com.agentmemory.store.PageRepository pages;
    private final LlmProvider llm;
    private final Supplier<RecallService> recall;
    private final LlmRateLimitGate rateLimitGate;
    private final AutoImproveProperties props;
    private final ReviewPrompts prompts;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    // Per-tick state; the scheduler drives one tick at a time on one thread (beginTick resets).
    private final AtomicInteger remainingLlmCalls = new AtomicInteger();
    private final Map<Scope, List<String>> tickNotes = new LinkedHashMap<>();

    public SessionReviewProposalSource(
            SessionObservationReader reader,
            com.agentmemory.store.PageRepository pages,
            LlmProvider llm,
            Supplier<RecallService> recall,
            LlmRateLimitGate rateLimitGate,
            AutoImproveProperties props) {
        this(reader, pages, llm, recall, rateLimitGate, props, new ReviewPrompts(), Clock.systemUTC());
    }

    /** Visible for tests: inject prompts and a fixed clock (quiet-hours). */
    SessionReviewProposalSource(
            SessionObservationReader reader,
            com.agentmemory.store.PageRepository pages,
            LlmProvider llm,
            Supplier<RecallService> recall,
            LlmRateLimitGate rateLimitGate,
            AutoImproveProperties props,
            ReviewPrompts prompts,
            Clock clock) {
        this.reader = reader;
        this.pages = pages;
        this.llm = llm;
        this.recall = recall;
        this.rateLimitGate = rateLimitGate;
        this.props = props;
        this.prompts = prompts;
        this.clock = clock;
    }

    @Override
    public String id() {
        return SOURCE_ID;
    }

    @Override
    public void beginTick() {
        remainingLlmCalls.set(props.review().maxLlmCallsPerTick());
        tickNotes.clear();
    }

    @Override
    public boolean ready(int backlog) {
        if (!props.review().enabled()) {
            return false;
        }
        if (rateLimitGate.isPaused()) {
            // #148's lesson: never burn a doomed call into an exhausted quota window. The sessions
            // stay due (no claim consumed) and the next tick retries after the pause.
            log.debug("session review: rate-limit gate paused; deferring the rest of this tick");
            return false;
        }
        if (remainingLlmCalls.get() < 1 + MAX_PROPOSALS_PER_SESSION) {
            // A session costs up to 1 extraction + MAX_PROPOSALS verification calls; starting one
            // without the worst-case budget would force dropping unverified proposals mid-session.
            // Deferring the whole session keeps "estourou, o resto fica para o próximo tick" true.
            log.debug("session review: per-tick LLM budget (nearly) spent; deferring the rest of this tick");
            return false;
        }
        return withinQuietHours(backlog);
    }

    @Override
    public List<ProposedWrite> proposalsFor(Scope scope, SessionId session) {
        List<Observation> observations = reader.observationsFor(session);
        if (observations.size() < props.review().minObservations()) {
            log.debug("session review: session {} has {} observation(s) (< {}); nothing durable to extract",
                    session.value(), observations.size(), props.review().minObservations());
            return List.of(); // claim consumed on purpose: a trivial session IS reviewed
        }
        String material = sessionMaterial(scope, session, observations);
        List<Candidate> candidates = extract(material);
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<ProposedWrite> out = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.confidence() < props.review().verifyThreshold() && !verify(scope, c)) {
                continue; // rejected by the adversarial pass (already noted for the report)
            }
            out.add(toWrite(scope, session, c));
        }
        log.info("session review: session {} in {}/{} -> {} extracted, {} staged",
                session.value(), scope.workspaceSlug(), scope.projectSlug(), candidates.size(), out.size());
        return out;
    }

    @Override
    public List<ScopedWrite> endTick() {
        if (tickNotes.isEmpty()) {
            return List.of();
        }
        List<ScopedWrite> reports = new ArrayList<>(tickNotes.size());
        tickNotes.forEach((scope, notes) -> {
            StringBuilder body = new StringBuilder("_Generated by auto-improve (session review, #150)._\n\n");
            body.append("## Decisions this tick (").append(notes.size()).append(")\n\n");
            notes.forEach(n -> body.append("- ").append(n).append('\n'));
            reports.add(new ScopedWrite(scope, new ProposedWrite(
                    REPORT_PATH, "Auto-improve review report", body.toString(),
                    ProposalKinds.PAGE_EDIT,
                    "session-review tick report: rejected/converted proposals for operator visibility")));
        });
        tickNotes.clear();
        return reports;
    }

    // --- phase A: extraction -----------------------------------------------------------------------

    private List<Candidate> extract(String material) {
        ChatResponse response = chat(ChatRequest.structured(
                List.of(
                        ChatMessage.system(prompts.reviewSystem()),
                        ChatMessage.user("Session material (untrusted data):\n\n" + material)),
                new JsonSchema(EXTRACTION_SCHEMA_NAME, EXTRACTION_SCHEMA_JSON)));
        JsonNode root = json.readTree(response.text());
        JsonNode proposals = root.path("proposals");
        if (!proposals.isArray()) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        for (JsonNode p : proposals) {
            String kind = p.path("kind").asString("");
            String title = p.path("title").asString("").strip();
            String body = p.path("body").asString("").strip();
            double confidence = clamp01(p.path("confidence").asDouble(0));
            String evidence = p.path("evidence").asString("").strip();
            if (folderFor(kind) == null || title.isBlank() || body.isBlank()) {
                continue; // malformed entry: drop silently, the schema should prevent this
            }
            out.add(new Candidate(kind, title, body, confidence, evidence));
            if (out.size() >= MAX_PROPOSALS_PER_SESSION) {
                break;
            }
        }
        return out;
    }

    // --- phase B: adversarial verification ----------------------------------------------------------

    private boolean verify(Scope scope, Candidate c) {
        if (remainingLlmCalls.get() <= 0) {
            // Budget ran out mid-session: dropping is the conservative choice (an unverified
            // low-confidence proposal must not reach the wiki), and the report says why.
            note(scope, "DROPPED (budget) — unverified low-confidence proposal \"" + c.title() + "\"");
            return false;
        }
        ChatResponse response = chat(ChatRequest.structured(
                List.of(
                        ChatMessage.system(prompts.verifySystem()),
                        ChatMessage.user("Candidate page (untrusted data):\n\nkind: " + c.kind()
                                + "\ntitle: " + c.title() + "\n\n" + c.body())),
                new JsonSchema(VERIFY_SCHEMA_NAME, VERIFY_SCHEMA_JSON)));
        JsonNode verdict = json.readTree(response.text());
        boolean durable = verdict.path("durable").asBoolean(false);
        if (!durable) {
            note(scope, "REJECTED (verify) — \"" + c.title() + "\": "
                    + verdict.path("reason").asString("no reason"));
        }
        return durable;
    }

    // --- dedup + write shaping -----------------------------------------------------------------------

    private ProposedWrite toWrite(Scope scope, SessionId session, Candidate c) {
        String folder = folderFor(c.kind());
        String path = folder + slug(c.title()) + ".md";
        List<RecallHit> hits = dedupHits(scope, c);

        RecallHit update = hits.stream()
                .filter(h -> h.score() >= DEDUP_UPDATE_SCORE && h.path() != null
                        && h.path().startsWith(folder))
                .findFirst()
                .orElse(null);
        if (update != null) {
            path = update.path();
            note(scope, "CONVERTED to update — \"" + c.title() + "\" supersedes [[" + update.path() + "]]");
        } else if (pages.readLatest(Identity.ofPage(
                scope.workspace(), scope.project(), com.agentmemory.core.PagePath.of(path))).isPresent()) {
            // Slug collision with a page dedup did NOT select: superseding it would silently replace
            // unrelated content. Disambiguate with a stable per-session suffix instead.
            String suffix = session.value().toString().substring(0, 8);
            path = folder + slug(c.title()) + "-" + suffix + ".md";
            note(scope, "DISAMBIGUATED — \"" + c.title() + "\" collided with an existing page; staged as "
                    + path);
        }

        StringBuilder body = new StringBuilder(c.body());
        List<String> related = hits.stream()
                .filter(h -> h.path() != null && !h.path().equals(update == null ? null : update.path()))
                .filter(h -> h.score() >= DEDUP_RELATED_SCORE)
                .map(RecallHit::path)
                .distinct()
                .limit(3)
                .toList();
        if (!related.isEmpty()) {
            body.append("\n\nRelated: ");
            related.forEach(p -> body.append("[[").append(p).append("]] "));
        }
        body.append("\n\n---\n_Auto-improve review (#150): session ").append(session.value())
                .append(", confidence ").append(String.format(Locale.ROOT, "%.2f", c.confidence()))
                .append(". Evidence: ").append(c.evidence().isBlank() ? "n/a" : c.evidence())
                .append("_\n");

        String rationale = (update != null ? "updates existing page (dedup); " : "")
                + "extracted from session " + session.value() + " with confidence "
                + String.format(Locale.ROOT, "%.2f", c.confidence());
        return new ProposedWrite(path, c.title(), body.toString(), ProposalKinds.PAGE_EDIT, rationale);
    }

    /** Search existing durable pages for equivalents; empty when recall is unavailable (dedup skipped). */
    private List<RecallHit> dedupHits(Scope scope, Candidate c) {
        RecallService service = recall.get();
        if (service == null) {
            return List.of();
        }
        try {
            String query = c.title() + "\n" + c.body().substring(0, Math.min(400, c.body().length()));
            return service.search(new RecallQuery(query, scope, 5)).hits().stream()
                    .filter(h -> h.path() != null && isDurablePath(h.path()))
                    .toList();
        } catch (RuntimeException e) {
            log.debug("session review: dedup recall failed ({}); staging without dedup", e.toString());
            return List.of();
        }
    }

    // --- material ------------------------------------------------------------------------------------

    /**
     * The text the reviewer reads: the synthesized {@code sessions/<id>.md} page when it exists (a
     * single cheap read — the consolidation pipeline already paid the LLM cost), else a budget-bounded
     * raw transcript. Deliberately does NOT trigger synthesis — that is the consolidation pipeline's
     * job, and this loop must stay out-of-band.
     */
    private String sessionMaterial(Scope scope, SessionId session, List<Observation> observations) {
        Identity pageId = Identity.ofPage(
                scope.workspace(), scope.project(), SessionSynthesizer.pagePathFor(session));
        Optional<PageRecord> page = pages.readLatest(pageId);
        if (page.isPresent() && !page.get().page().body().isBlank()) {
            return page.get().page().body();
        }
        String transcript = ObservationTranscript.renderAll(observations);
        if (transcript.length() <= MATERIAL_CHAR_BUDGET) {
            return transcript;
        }
        // Block-boundary truncation with an explicit marker, so the model neither sees a dangling
        // half-observation nor mistakes the cut for the end of the session.
        String head = ObservationTranscript.chunkText(transcript, MATERIAL_CHAR_BUDGET).get(0);
        return head + "\n\n[... transcript truncated: session continues beyond the review budget ...]";
    }

    // --- helpers -------------------------------------------------------------------------------------

    /** One budgeted LLM call; the budget is decremented per call (extraction and verification alike). */
    private ChatResponse chat(ChatRequest request) {
        remainingLlmCalls.decrementAndGet();
        return llm.chat(request);
    }

    private void note(Scope scope, String message) {
        tickNotes.computeIfAbsent(scope, s -> new ArrayList<>()).add(message);
    }

    /** Whether the engine may run now: inside the window, or backlog large enough to override it. */
    private boolean withinQuietHours(int backlog) {
        String window = props.review().quietHours();
        if (window.isBlank()) {
            return true;
        }
        int dash = window.indexOf('-');
        if (dash <= 0) {
            log.warn("session review: malformed quiet-hours '{}' (expected HH:mm-HH:mm); ignoring", window);
            return true;
        }
        LocalTime start;
        LocalTime end;
        try {
            start = LocalTime.parse(window.substring(0, dash).strip());
            end = LocalTime.parse(window.substring(dash + 1).strip());
        } catch (DateTimeParseException e) {
            log.warn("session review: malformed quiet-hours '{}' (expected HH:mm-HH:mm); ignoring", window);
            return true;
        }
        if (start.equals(end)) {
            return true; // "00:00-00:00" and friends: a zero-length window means "always" not "never"
        }
        LocalTime now = LocalTime.now(clock);
        boolean inside = start.isBefore(end)
                ? !now.isBefore(start) && now.isBefore(end)
                : !now.isBefore(start) || now.isBefore(end); // window wraps midnight
        if (inside) {
            return true;
        }
        if (backlog > props.review().quietHoursBacklog()) {
            log.debug("session review: outside quiet-hours but backlog {} > {}; running anyway",
                    backlog, props.review().quietHoursBacklog());
            return true;
        }
        return false;
    }

    static String folderFor(String kind) {
        return switch (kind) {
            case "concept" -> "concepts/";
            case "decision" -> "decisions/";
            case "gotcha" -> "gotchas/";
            case "procedure" -> "procedures/";
            case "rule" -> "_rules/";
            default -> null;
        };
    }

    private static boolean isDurablePath(String path) {
        return path.startsWith("concepts/") || path.startsWith("decisions/")
                || path.startsWith("gotchas/") || path.startsWith("procedures/")
                || path.startsWith("_rules/");
    }

    /** Lowercase-hyphen slug for a page filename, bounded and never blank. */
    static String slug(String title) {
        String s = title.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (s.length() > 60) {
            s = s.substring(0, 60).replaceAll("-+$", "");
        }
        return s.isBlank() ? "note" : s;
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /** One extracted candidate page, pre-verification. */
    record Candidate(String kind, String title, String body, double confidence, String evidence) {}
}
