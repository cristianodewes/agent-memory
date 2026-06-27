package com.agentmemory.llmrecall;

import com.agentmemory.llm.ChatMessage;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.ChatResponse;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.llm.ReasoningEffort;
import com.agentmemory.recall.RecallHit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step two of LLM-assisted recall: re-order the fused candidates by an LLM relevance judgment instead
 * of the raw Reciprocal Rank Fusion order. The model is shown the query and the top-K candidates'
 * {@code id} / {@code title} / {@code snippet} and returns a relevance score per id (structured JSON,
 * invariant #7, schema {@code recall_rerank}); the candidates are then sorted by that score.
 *
 * <h2>Cost bound</h2>
 * Only the first {@code maxCandidates} (K) of the input list are ever sent to the LLM. Any RRF tail
 * beyond K is appended after the re-ranked head in its original order, so a large result set costs a
 * fixed-size prompt while still returning every hit. This is the "cap K" half of the acceptance's cost
 * controls.
 *
 * <h2>Determinism &amp; degradation</h2>
 * The sort is total and stable: candidates are ordered by LLM score descending, ties broken by the
 * original RRF rank ascending, so equal scores never reorder unpredictably. The re-ordering is applied
 * <em>only</em> when the model returns a complete ranking — a score for every head candidate. On any
 * failure or any untrustworthy reply — LLM error, malformed JSON, empty rankings, or a <em>partial</em>
 * ranking that omits some candidate ids — the original input list is returned unchanged (the non-LLM
 * fast path). Requiring full coverage is what guarantees rerank can only improve on or match the RRF
 * baseline, never drop below it: a partial reply could otherwise demote an omitted-but-strong RRF hit
 * below a weakly-judged one. The RRF tail beyond K keeps its original RRF score (only its rank is
 * re-stamped), so its relevance signal is preserved for any downstream score-based gate.
 *
 * <p>The titles/snippets sent are stored page content that already passed the ingest sanitization
 * boundary (#6); they are bounded defensively here and the model is instructed to treat them as
 * untrusted data, not instructions (prompt-injection hardening). Pure (no DB); unit-testable with a
 * scripted provider.
 */
public final class CandidateReranker {

    private static final Logger log = LoggerFactory.getLogger(CandidateReranker.class);

    /** Defensive per-snippet cap (chars) so one long snippet cannot blow up the rerank prompt. */
    private static final int MAX_SNIPPET_CHARS = 280;

    private final LlmProvider llm;
    private final RecallPrompts prompts;
    private final int maxCandidates;
    private final ReasoningEffort reasoningEffort; // nullable: null = provider default reasoning
    private final JsonMapper json = JsonMapper.builder().build();

    public CandidateReranker(LlmProvider llm, RecallPrompts prompts, int maxCandidates) {
        this(llm, prompts, maxCandidates, null);
    }

    /**
     * @param reasoningEffort the reasoning-effort hint to put on the rerank call (issue #130) —
     *     {@link ReasoningEffort#MINIMAL} on the recall path, or {@code null} to leave the provider
     *     default unchanged.
     */
    public CandidateReranker(LlmProvider llm, RecallPrompts prompts, int maxCandidates,
            ReasoningEffort reasoningEffort) {
        if (maxCandidates <= 0) {
            throw new IllegalArgumentException("maxCandidates must be > 0, was " + maxCandidates);
        }
        this.llm = llm;
        this.prompts = prompts;
        this.maxCandidates = maxCandidates;
        this.reasoningEffort = reasoningEffort;
    }

    /**
     * Re-rank with the provider-default per-call timeout (no recall budget bound).
     *
     * @see #rerank(String, List, Duration)
     */
    public List<RecallHit> rerank(String queryText, List<RecallHit> fused) {
        return rerank(queryText, fused, null);
    }

    /**
     * Re-rank {@code fused} (the RRF-ordered hits) by LLM relevance to {@code queryText}, re-stamping
     * each returned hit's 1-based rank and a normalized 0..1 score. Never throws.
     *
     * @param queryText      the user's recall text.
     * @param fused          the RRF-ordered hits (already best-first); never null.
     * @param requestTimeout the per-call HTTP timeout derived from the remaining recall budget (issue
     *     #130), or {@code null} for the provider default. A timeout surfaces as a provider error and so
     *     keeps the RRF order — the "never worse than baseline" guarantee holds for the budget axis too.
     * @return the re-ordered hits with fresh rank/score, or {@code fused} unchanged on any failure or
     *     when there is nothing worth re-ranking (0 or 1 hits).
     */
    public List<RecallHit> rerank(String queryText, List<RecallHit> fused, Duration requestTimeout) {
        if (fused == null || fused.size() <= 1) {
            return fused;
        }
        // Split into the head we will re-rank (top K) and the tail we leave in RRF order.
        int headSize = Math.min(maxCandidates, fused.size());
        List<RecallHit> head = fused.subList(0, headSize);
        List<RecallHit> tail = fused.subList(headSize, fused.size());

        Map<String, Double> scores;
        try {
            ChatResponse response = llm.chat(buildRequest(queryText, head, requestTimeout));
            scores = parseScores(response.text());
        } catch (RuntimeException e) {
            log.debug("rerank skipped (keeping RRF order): {}", e.toString());
            return fused;
        }
        // Require a score for EVERY head candidate. A partial ranking (the model omitted some ids)
        // cannot be trusted to reorder safely: an omitted but genuinely top hit would be treated as
        // unscored and could be demoted below a weakly-judged one, making the result worse than the RRF
        // baseline — which would break the "never worse than baseline" guarantee. So an empty OR partial
        // reply degrades to the RRF order unchanged, exactly like a hard failure.
        if (scores.size() < head.size() || !scoresCoverAll(head, scores)) {
            if (!scores.isEmpty()) {
                log.debug("rerank reply scored {}/{} candidates; keeping RRF order",
                        scores.size(), head.size());
            }
            return fused;
        }

        // Order the head by LLM score desc, tie-break by original position (stable, deterministic).
        List<Ranked> ranked = new ArrayList<>(head.size());
        for (int i = 0; i < head.size(); i++) {
            RecallHit hit = head.get(i);
            double score = clamp01(scores.get(hit.id()));
            ranked.add(new Ranked(hit, score, i));
        }
        ranked.sort(Comparator.<Ranked>comparingDouble(r -> r.score).reversed()
                .thenComparingInt(r -> r.originalIndex));

        // Re-assemble: re-ranked head first, then the untouched RRF tail; re-stamp ranks across both.
        // The tail keeps its original RRF score (we do NOT zero it — zeroing would discard the tail's
        // relevance signal and trip a downstream relevance gate); only its rank is re-stamped so the
        // full list is contiguously ranked 1..n.
        List<RecallHit> result = new ArrayList<>(fused.size());
        int rank = 1;
        for (Ranked r : ranked) {
            result.add(r.hit.withRankAndScore(rank, r.score));
            rank++;
        }
        for (RecallHit t : tail) {
            result.add(t.withRankAndScore(rank, t.score()));
            rank++;
        }
        return result;
    }

    /** @return true iff every head candidate id has a score in {@code scores}. */
    private static boolean scoresCoverAll(List<RecallHit> head, Map<String, Double> scores) {
        for (RecallHit h : head) {
            if (!scores.containsKey(h.id())) {
                return false;
            }
        }
        return true;
    }

    private ChatRequest buildRequest(String queryText, List<RecallHit> head, Duration requestTimeout) {
        StringBuilder user = new StringBuilder(256);
        user.append("Query: ").append(queryText).append("\n\nCandidates:\n");
        for (RecallHit h : head) {
            user.append("- id: ").append(h.id()).append('\n');
            user.append("  title: ").append(oneLine(h.title())).append('\n');
            user.append("  snippet: ").append(oneLine(boundSnippet(h.snippet()))).append('\n');
        }
        return ChatRequest.structured(
                List.of(
                        ChatMessage.system(prompts.rerankSystemPrompt()),
                        ChatMessage.user(user.toString())),
                prompts.rerankSchema())
                .withReasoningEffort(reasoningEffort)
                .withRequestTimeout(requestTimeout);
    }

    /**
     * Parse {@code {"rankings":[{"id":…,"relevance":…}]}} into an id→relevance map. Tolerant: entries
     * missing an id or a numeric relevance are skipped (a partial ranking still improves on RRF for the
     * ids it did score). Returns an empty map when nothing usable is present, which the caller treats
     * as "keep RRF order".
     */
    private Map<String, Double> parseScores(String replyJson) {
        JsonNode root;
        try {
            root = json.readTree(replyJson);
        } catch (JacksonException e) {
            log.debug("rerank reply was not valid JSON; ignoring: {}", e.toString());
            return Map.of();
        }
        JsonNode rankings = root.get("rankings");
        if (rankings == null || !rankings.isArray()) {
            return Map.of();
        }
        Map<String, Double> scores = new HashMap<>();
        for (JsonNode el : rankings) {
            JsonNode idNode = el.get("id");
            JsonNode relNode = el.get("relevance");
            if (idNode == null || !idNode.isString() || relNode == null || !relNode.isNumber()) {
                continue;
            }
            String id = idNode.stringValue().strip();
            if (id.isEmpty()) {
                continue;
            }
            scores.put(id, clamp01(relNode.doubleValue()));
        }
        return scores;
    }

    private static String boundSnippet(String snippet) {
        if (snippet == null) {
            return "";
        }
        return snippet.length() <= MAX_SNIPPET_CHARS
                ? snippet
                : snippet.substring(0, MAX_SNIPPET_CHARS) + "…";
    }

    /** Collapse newlines so each candidate stays a single YAML-ish line in the prompt. */
    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').strip();
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** A candidate paired with its LLM score and original RRF position, for the stable re-sort. */
    private record Ranked(RecallHit hit, double score, int originalIndex) {}
}
