package com.agentmemory.llmrecall;

import com.agentmemory.recall.RecallHit;
import com.agentmemory.recall.RecallQuery;
import com.agentmemory.recall.RecallResult;
import com.agentmemory.recall.RecallService;
import com.agentmemory.recall.Scope;
import java.util.ArrayList;
import java.util.List;

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
 * <p>The block is plain markdown (a short header plus one bullet per hit: title + path + snippet). The
 * snippet is stored page content (already sanitized at ingest, #6); the renderer strips the
 * {@code <mark>} headline tags so the injected text is clean prose. Stateless given its collaborators.
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

    public RecallInjection(RecallService recall, LlmRecallProperties.Injection cfg) {
        if (recall == null) {
            throw new IllegalArgumentException("recall must not be null");
        }
        if (cfg == null) {
            throw new IllegalArgumentException("injection config must not be null");
        }
        this.recall = recall;
        this.cfg = cfg;
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

    private static String renderHit(RecallHit h) {
        StringBuilder b = new StringBuilder(96);
        b.append("- **").append(clean(h.title())).append("**");
        if (h.path() != null && !h.path().isBlank()) {
            b.append(" (").append(h.path()).append(")");
        }
        String snippet = clean(stripMarks(h.snippet()));
        if (!snippet.isEmpty()) {
            b.append(" — ").append(snippet);
        }
        b.append('\n');
        return b.toString();
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
