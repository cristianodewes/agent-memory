package com.agentmemory.llmrecall;

import com.agentmemory.llm.ChatMessage;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.ChatResponse;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.llm.ReasoningEffort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Step one of LLM-assisted recall: rewrite a query into a few extra full-text retrieval terms before
 * fusion. The base FTS arm parses its text with {@code plainto_tsquery('english', …)} (natural words,
 * AND-combined per word), so appending synonyms / related vocabulary broadens what the keyword arm can
 * match without changing the query semantics the user expressed.
 *
 * <p>The expansion is a single structured-JSON LLM call (invariant #7, schema {@code recall_expansion})
 * and is strictly <strong>additive and best-effort</strong>: on any failure — LLM error, malformed
 * JSON, empty result — it returns the original query text unchanged, so recall never degrades below
 * the RRF baseline because of expansion. The number of appended terms is capped
 * ({@link LlmRecallProperties.Expansion#maxTerms()}) to bound the widening.
 *
 * <p>Only page <em>content</em> the user themselves typed (the query) is sent to the model here, plus
 * the static system prompt — there is no stored-memory content in the expansion call.
 */
public final class QueryExpander {

    private static final Logger log = LoggerFactory.getLogger(QueryExpander.class);

    private final LlmProvider llm;
    private final RecallPrompts prompts;
    private final int maxTerms;
    private final ReasoningEffort reasoningEffort; // nullable: null = provider default reasoning
    private final JsonMapper json = JsonMapper.builder().build();

    public QueryExpander(LlmProvider llm, RecallPrompts prompts, int maxTerms) {
        this(llm, prompts, maxTerms, null);
    }

    /**
     * @param reasoningEffort the reasoning-effort hint to put on the expansion call (issue #130) —
     *     {@link ReasoningEffort#MINIMAL} on the recall path, or {@code null} to leave the provider
     *     default unchanged.
     */
    public QueryExpander(LlmProvider llm, RecallPrompts prompts, int maxTerms,
            ReasoningEffort reasoningEffort) {
        if (maxTerms <= 0) {
            throw new IllegalArgumentException("maxTerms must be > 0, was " + maxTerms);
        }
        this.llm = llm;
        this.prompts = prompts;
        this.maxTerms = maxTerms;
        this.reasoningEffort = reasoningEffort;
    }

    /**
     * Expand {@code queryText} with the provider-default per-call timeout (no recall budget bound).
     *
     * @see #expand(String, Duration)
     */
    public String expand(String queryText) {
        return expand(queryText, null);
    }

    /**
     * Expand {@code queryText} into the original text plus up to {@code maxTerms} additional retrieval
     * terms. Never throws.
     *
     * @param queryText      the user's recall text; never null/blank.
     * @param requestTimeout the per-call HTTP timeout derived from the remaining recall budget (issue
     *     #130), or {@code null} for the provider default. A timeout simply surfaces as a provider error,
     *     which falls back to the original text — expansion never degrades recall below the RRF baseline.
     * @return the (possibly) widened query text, or the original text if expansion added nothing or
     *     failed.
     */
    public String expand(String queryText, Duration requestTimeout) {
        if (queryText == null || queryText.isBlank()) {
            return queryText;
        }
        try {
            ChatRequest request = ChatRequest.structured(
                    List.of(
                            ChatMessage.system(prompts.expansionSystemPrompt()),
                            ChatMessage.user("Query: " + queryText)),
                    prompts.expansionSchema())
                    .withReasoningEffort(reasoningEffort)
                    .withRequestTimeout(requestTimeout);
            ChatResponse response = llm.chat(request);
            List<String> terms = parseTerms(response.text(), queryText);
            if (terms.isEmpty()) {
                return queryText;
            }
            return queryText + " " + String.join(" ", terms);
        } catch (RuntimeException e) {
            // Expansion is optional; never let it fail the surrounding recall.
            log.debug("query expansion skipped (falling back to original text): {}", e.toString());
            return queryText;
        }
    }

    /**
     * Parse {@code {"terms":[…]}} into a de-duplicated, bounded list of clean terms, excluding any that
     * merely echo a word already in the original query. Tolerant: non-string / blank entries are
     * dropped rather than throwing, because a slightly-malformed expansion should degrade to "fewer
     * terms", not "no recall".
     */
    private List<String> parseTerms(String replyJson, String originalQuery) {
        JsonNode root;
        try {
            root = json.readTree(replyJson);
        } catch (JacksonException e) {
            log.debug("expansion reply was not valid JSON; ignoring: {}", e.toString());
            return List.of();
        }
        JsonNode termsNode = root.get("terms");
        if (termsNode == null || !termsNode.isArray()) {
            return List.of();
        }
        Set<String> already = lowerWords(originalQuery);
        Set<String> picked = new LinkedHashSet<>();
        for (JsonNode el : termsNode) {
            if (!el.isString()) {
                continue;
            }
            String term = el.stringValue().strip();
            if (term.isEmpty()) {
                continue;
            }
            String lower = term.toLowerCase(Locale.ROOT);
            // Skip a term that is just a word already present in the query (no new signal).
            if (already.contains(lower)) {
                continue;
            }
            picked.add(term);
            if (picked.size() >= maxTerms) {
                break;
            }
        }
        return new ArrayList<>(picked);
    }

    private static Set<String> lowerWords(String text) {
        Set<String> words = new LinkedHashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!w.isBlank()) {
                words.add(w);
            }
        }
        return words;
    }
}
