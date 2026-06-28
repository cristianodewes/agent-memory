package com.agentmemory.llmrecall;

import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.RecallQuery;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.RecallService;
import com.agentmemory.recall.Scope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Builds the concise, relevance-gated, bounded memory block a {@code UserPromptSubmit} hook injects
 * into the agent's context (issue #21 acceptance: "injection endpoint returns a concise,
 * relevance-gated bounded block suitable for a hook"). It runs the LLM-assisted {@link RecallService}
 * for the prompt text, then curates the result for direct pasting:
 *
 * <ul>
 *   <li><strong>Relevance gate</strong>: hits scoring below a fraction
 *       ({@link LlmRecallProperties.Injection#minScore()}) of the <em>top</em> hit's score are dropped;
 *       if nothing clears the gate the block is <em>empty</em> (the hook injects nothing), so a
 *       low-signal prompt is never padded with marginal memories. The gate is relative (scale-invariant)
 *       so it behaves identically whether the scores are normalized LLM relevances or raw RRF scores.
 *       When (and only when) a calibrated cross-encoder produced the scores
 *       ({@link com.agentmemory.recall.RecallResult#calibrated()}), an additional <em>absolute</em> floor
 *       ({@link LlmRecallProperties.Injection#minScoreAbsolute()}) empties the block whenever even the top
 *       hit scores below it — the calibrated way to say "nothing relevant here" on a low-signal prompt
 *       (issue #130, Fase 2). The absolute floor is never applied to uncalibrated RRF/LLM scores.</li>
 *   <li><strong>Bounded</strong>: at most {@link LlmRecallProperties.Injection#maxHits()} hits, and the
 *       rendered text is hard-capped at {@link LlmRecallProperties.Injection#maxChars()} characters so
 *       the hook can rely on a predictable, small paste.</li>
 *   <li><strong>Raw fallback excluded</strong>: only compiled-page hits are injected; the
 *       low-confidence raw-observation fallback is never auto-pasted into a prompt.</li>
 * </ul>
 *
 * <p>The block is plain markdown: a short header plus one bullet per hit. Each bullet is
 * <strong>path-first</strong> — it leads with the page path so the agent can {@code memory_read_page}
 * the source — then annotates the hit's {@link com.agentmemory.core.MemoryLayer layer}, its recency
 * ("atualizado há Nd", from {@code updated_at}), and its relevance score, and ends with the snippet
 * (issue #140): {@code - path · layer · atualizado há 3d · rel 0.91 — snippet}. The snippet is stored
 * page content (already sanitized at ingest, #6); the renderer strips the {@code <mark>} headline tags
 * so the injected text is clean prose. Stateless given its collaborators.
 *
 * <h2>Synthesized brief (issue #135, Fase 3)</h2>
 * When an optional {@link BriefSynthesizer} is wired and enabled, and the gate approved on
 * <em>calibrated</em> cross-encoder scores, the curated block is upgraded from raw bullets to a single
 * short "what you need to know" paragraph with path citations — one minimal-effort generative call made
 * only on real matches (the common low-signal prompt never reaches it, so the Fase 0-2 latency property
 * holds). It is strictly never-worse-than-baseline: a disabled feature, uncalibrated scores, an absent
 * synthesizer, or any synthesis failure / timeout / not-relevant / empty / over-budget brief all fall
 * back to the same bounded, gated bullets that were rendered before Fase 3.
 */
public final class RecallInjection {

    /**
     * How many times {@code maxHits} to fetch as the candidate pool, so the relevance gate has hits to
     * backfill from when the top {@code maxHits} are partly gated out. A small constant keeps the recall
     * bounded while giving the gate room to work.
     */
    private static final int POOL_MULTIPLIER = 4;

    private final RecallService recall;
    private final LlmRecallProperties.Injection cfg;
    private final BriefSynthesizer synthesizer; // nullable: absent => bullets only (pre-Fase-3 behavior)
    private final Clock clock; // "now" for the per-hit recency label (issue #140); UTC in production

    /** Bullets-only injection (no brief synthesizer wired). */
    public RecallInjection(RecallService recall, LlmRecallProperties.Injection cfg) {
        this(recall, cfg, null);
    }

    /**
     * @param synthesizer the optional brief synthesizer (issue #135, Fase 3), or {@code null} to render
     *     the snippet bullets as before. Even when present, it is consulted only if the brief is enabled
     *     in {@code cfg} and the gate approved on calibrated scores.
     */
    public RecallInjection(
            RecallService recall, LlmRecallProperties.Injection cfg, BriefSynthesizer synthesizer) {
        this(recall, cfg, synthesizer, Clock.systemUTC());
    }

    /**
     * Fully-injectable constructor exposing the {@link Clock} the recency label reads "now" from, so a
     * test can assert "atualizado há Nd" deterministically against a fixed clock.
     *
     * @param clock the clock for the per-hit recency annotation; never null.
     */
    public RecallInjection(
            RecallService recall,
            LlmRecallProperties.Injection cfg,
            BriefSynthesizer synthesizer,
            Clock clock) {
        if (recall == null) {
            throw new IllegalArgumentException("recall must not be null");
        }
        if (cfg == null) {
            throw new IllegalArgumentException("injection config must not be null");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.recall = recall;
        this.cfg = cfg;
        this.synthesizer = synthesizer;
        this.clock = clock;
    }

    /**
     * Build the injection block for {@code prompt} within {@code scope}.
     *
     * @param scope  the project to recall from; never null.
     * @param prompt the user's prompt text to find relevant memory for; never null/blank.
     * @return the curated block (empty {@code text} when nothing clears the relevance gate).
     */
    public Result inject(Scope scope, String prompt) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("prompt must not be null or blank");
        }
        // Over-fetch a candidate pool larger than the cap so the relevance gate can BACKFILL: if the
        // top maxHits contain gate-failing hits, lower-ranked hits that clear the gate can still be
        // included. Fetching exactly maxHits would let filtering only shrink the block, never refill it.
        int pool = cfg.maxHits() * POOL_MULTIPLIER;
        RecallResult result = recall.search(new RecallQuery(prompt, scope, pool));

        // Never inject the low-confidence raw-observation fallback.
        if (result.rawFallback()) {
            return Result.empty();
        }
        if (result.hits().isEmpty()) {
            return Result.empty();
        }

        double topScore = result.hits().get(0).score();

        // Absolute gate (issue #130, Fase 2) — applied ONLY when a calibrated cross-encoder produced the
        // scores. A calibrated top score below the absolute floor means nothing here is genuinely relevant
        // (the common low-signal prompt: "ok", "run the tests", "thanks"), so inject nothing. This gate is
        // deliberately conditional on calibration: raw RRF fused scores are tiny and uncalibrated
        // (~0.01-0.05), so an absolute cut applied to them would wrongly empty almost every block — for
        // those the scale-invariant relative gate below is the only one that fires.
        if (result.calibrated() && topScore < cfg.minScoreAbsolute()) {
            return Result.empty();
        }

        // Relevance gate. Hits are ranked best-first, so the first hit carries the maximum score; the
        // gate is RELATIVE to it (keep hits within the minScore fraction of the best score). A relative
        // gate is scale-invariant — it behaves the same whether the scores are normalized 0..1 LLM
        // relevances (rerank ran) or raw RRF fused scores (rerank skipped / over budget / disabled) —
        // so a sensible minScore never silently empties the block just because rerank did not run.
        double threshold = topScore * cfg.minScore();
        List<RecallHit> gated = new ArrayList<>(cfg.maxHits());
        for (RecallHit h : result.hits()) {
            if (h.score() < threshold) {
                continue;
            }
            gated.add(h);
            if (gated.size() >= cfg.maxHits()) {
                break;
            }
        }
        if (gated.isEmpty()) {
            return Result.empty();
        }

        // Synthesized brief (issue #135, Fase 3) — the only generative call the injection makes, and
        // only on a real match that cleared the CALIBRATED gate: the common low-signal prompt returned
        // above without ever reaching here, so the Fase 0-2 latency property holds. Any failure, timeout,
        // not-relevant, empty, or over-budget brief falls through to the bullets below — never worse than
        // the baseline. The brief is rendered as labeled context, never as instruction (its prose is
        // synthesized from untrusted snippets, the real injection surface BriefSynthesizer hardens).
        if (synthesizer != null && cfg.brief().enabled() && result.calibrated()) {
            Duration timeout = Duration.ofMillis(cfg.brief().timeoutMs());
            Optional<BriefSynthesizer.Brief> brief = synthesizer.synthesize(prompt, gated, timeout);
            if (brief.isPresent()) {
                Result rendered = renderBrief(brief.get(), gated.size());
                if (!rendered.isEmpty()) {
                    return rendered;
                }
            }
        }

        return render(gated);
    }

    /**
     * Render the gated hits as a bounded markdown block, returning the block plus the number of hits
     * actually written (which may be fewer than {@code hits} when the char budget is reached). If not
     * even the first hit fits under {@code maxChars}, an empty result is returned — a header-only block
     * is never injected, and the reported hit count always matches the rendered content.
     */
    private Result render(List<RecallHit> hits) {
        String header = "## Relevant project memory\n";
        StringBuilder sb = new StringBuilder(256).append(header);
        int rendered = 0;
        for (RecallHit h : hits) {
            String line = renderHit(h);
            if (sb.length() + line.length() > cfg.maxChars()) {
                break;
            }
            sb.append(line);
            rendered++;
        }
        if (rendered == 0) {
            // Not even one bullet fit the budget; inject nothing rather than a bare header.
            return Result.empty();
        }
        return new Result(sb.toString().stripTrailing(), rendered);
    }

    /**
     * Render a synthesized brief (issue #135, Fase 3) as the bounded block: the standard header, the
     * brief prose, then an optional {@code Sources: …} line citing the paths it drew on. The brief is
     * labeled context under the same header as the bullets, never an instruction. Returns
     * {@link Result#empty()} when the header+brief alone overflows {@code maxChars} — the caller then
     * falls back to the (individually char-bounded) bullets, so the block stays bounded either way. The
     * Sources line is appended only when it too fits.
     *
     * @param brief the synthesized brief and its grounded citations.
     * @param hits  the number of gated hits the brief summarizes, reported as the block's hit count.
     */
    private Result renderBrief(BriefSynthesizer.Brief brief, int hits) {
        String body = clean(brief.text());
        if (body.isEmpty()) {
            return Result.empty();
        }
        StringBuilder sb = new StringBuilder(256).append("## Relevant project memory\n").append(body);
        if (sb.length() > cfg.maxChars()) {
            // The brief alone overflows the budget; fall back to the bullets rather than truncate prose.
            return Result.empty();
        }
        String sources = renderSources(brief.citedPaths());
        if (!sources.isEmpty() && sb.length() + sources.length() <= cfg.maxChars()) {
            sb.append(sources);
        }
        return new Result(sb.toString().stripTrailing(), hits);
    }

    /** A trailing {@code "\nSources: a.md, b.md"} line, or empty when there are no cited paths. */
    private static String renderSources(List<String> citedPaths) {
        if (citedPaths.isEmpty()) {
            return "";
        }
        return "\nSources: " + String.join(", ", citedPaths);
    }

    /**
     * Render one hit as a path-first bullet with layer / recency / relevance metadata (issue #140):
     * {@code - path · layer · atualizado há 3d · rel 0.91 — snippet}. Leading with the path gives the
     * agent the exact {@code memory_read_page} target; the layer and "atualizado há Nd" (from
     * {@code updated_at}) and the relevance score let it weigh the memory. Each metadata segment is
     * emitted only when present (a vector-only or raw hit may lack a layer/timestamp), and a hit without
     * a path falls back to its bold title as the lead. The {@code <mark>} headline tags are stripped and
     * newlines flattened so the bullet is a single clean line; the {@code maxChars} budget is enforced by
     * the caller ({@link #render}).
     */
    private String renderHit(RecallHit h) {
        StringBuilder b = new StringBuilder(128).append("- ");

        // Path-first: lead with the page path (the memory_read_page target); fall back to the title when
        // a hit has no path (vector-only / raw — though raw is never injected).
        String path = h.path();
        boolean hasPath = path != null && !path.isBlank();
        if (hasPath) {
            b.append(path);
        } else {
            b.append("**").append(clean(h.title())).append("**");
        }

        // Layer · recency · relevance — each segment appended only when available.
        List<String> meta = new ArrayList<>(3);
        if (h.layer() != null) {
            meta.add(h.layer().wire());
        }
        String recency = recencyLabel(h.updatedAt());
        if (recency != null) {
            meta.add(recency);
        }
        meta.add(String.format(Locale.ROOT, "rel %.2f", h.score()));
        b.append(" · ").append(String.join(" · ", meta));

        String snippet = clean(stripMarks(h.snippet()));
        if (!snippet.isEmpty()) {
            b.append(" — ").append(snippet);
        }
        b.append('\n');
        return b.toString();
    }

    /**
     * The relevance-prior recency label for a hit, derived from {@code updated_at} against the clock's
     * "now": {@code "atualizado hoje"} under a day, else {@code "atualizado há Nd"}. Returns {@code null}
     * when the hit carries no timestamp (raw/vector-only), so the bullet omits the segment. Age is
     * floored at zero so a slight clock skew never renders a negative day count.
     */
    private String recencyLabel(Instant updatedAt) {
        if (updatedAt == null) {
            return null;
        }
        long days = Duration.between(updatedAt, clock.instant()).toDays();
        if (days <= 0) {
            return "atualizado hoje";
        }
        return "atualizado há " + days + "d";
    }

    /** Strip the {@code <mark>}/{@code </mark>} headline delimiters the SQL snippet adds. */
    private static String stripMarks(String s) {
        return s == null ? "" : s.replace("<mark>", "").replace("</mark>", "");
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').strip();
    }

    /**
     * The injection outcome: the block to paste (possibly empty) and how many hits it covers.
     *
     * @param text  the markdown block; empty string when nothing cleared the gate.
     * @param hits  the number of hits rendered into {@code text}.
     */
    public record Result(String text, int hits) {

        public Result {
            if (text == null) {
                throw new IllegalArgumentException("injection text must not be null (use empty string)");
            }
            if (hits < 0) {
                throw new IllegalArgumentException("hits must be >= 0");
            }
        }

        /** @return an empty result — nothing cleared the relevance gate, so the hook injects nothing. */
        public static Result empty() {
            return new Result("", 0);
        }

        /** @return {@code true} when there is nothing to inject. */
        public boolean isEmpty() {
            return text.isEmpty();
        }
    }
}
