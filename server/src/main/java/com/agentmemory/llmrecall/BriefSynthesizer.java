package com.agentmemory.llmrecall;

import com.agentmemory.llm.ChatMessage;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.ChatResponse;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.llm.ReasoningEffort;
import com.agentmemory.recall.RecallHit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The optional final step of curated injection (issue #135, Fase 3): synthesize the relevance-gated
 * hits into a single short "what you need to know" brief with path citations, instead of pasting raw
 * snippet bullets. The model is shown the prompt and the gated candidates' {@code path} / {@code title}
 * / {@code snippet} and returns {@code {relevant, brief, cited_paths}} (structured JSON, invariant #7,
 * schema {@code recall_curate}); the brief captures the reranker's decision as actionable prose.
 *
 * <h2>Never worse than the bullets</h2>
 * This is the single generative call Fase 3 reintroduces on the injection hot path, and it runs only
 * when {@link RecallInjection}'s calibrated gate has already approved (the common low-signal prompt
 * never reaches it). Every degradation — provider error, timeout, malformed JSON, {@code relevant:false},
 * or an empty brief — surfaces as {@link Optional#empty()}, on which the caller falls back to the
 * existing snippet bullets. It <strong>never throws</strong>, exactly like {@link CandidateReranker}.
 *
 * <h2>Prompt-injection hardening</h2>
 * The brief is prose generated from untrusted page snippets, so it is a real injection surface: the
 * system prompt instructs the model to treat the snippets as data, not instructions (mirroring
 * {@code recall-rerank.system.md}), each snippet is hard-capped at {@value #MAX_SNIPPET_CHARS} chars,
 * and the returned {@code cited_paths} are filtered down to paths we actually showed the model so a
 * hallucinated citation can never reach the rendered block. The caller renders the brief as labeled
 * context, never as instruction. Pure (no DB); unit-testable with a scripted provider.
 */
public final class BriefSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(BriefSynthesizer.class);

    /** Defensive per-snippet cap (chars) so one long snippet cannot blow up the curation prompt. */
    private static final int MAX_SNIPPET_CHARS = 280;

    private final LlmProvider llm;
    private final RecallPrompts prompts;
    private final ReasoningEffort reasoningEffort; // nullable: null = provider default reasoning
    private final JsonMapper json = JsonMapper.builder().build();

    public BriefSynthesizer(LlmProvider llm, RecallPrompts prompts) {
        this(llm, prompts, null);
    }

    /**
     * @param reasoningEffort the reasoning-effort hint to put on the curation call (issue #130) —
     *     {@link ReasoningEffort#MINIMAL} on the recall path, or {@code null} to leave the provider
     *     default unchanged.
     */
    public BriefSynthesizer(LlmProvider llm, RecallPrompts prompts, ReasoningEffort reasoningEffort) {
        if (llm == null) {
            throw new IllegalArgumentException("llm must not be null");
        }
        if (prompts == null) {
            throw new IllegalArgumentException("prompts must not be null");
        }
        this.llm = llm;
        this.prompts = prompts;
        this.reasoningEffort = reasoningEffort;
    }

    /**
     * Synthesize {@code gated} (the relevance-gated, best-first hits) into a brief grounded in their
     * snippets. Never throws.
     *
     * @param queryText      the user's prompt text.
     * @param gated          the gated hits to summarize; never null. An empty list yields no brief.
     * @param requestTimeout the per-call HTTP timeout (issue #130) bounding this one generative call,
     *     or {@code null} for the provider default. A timeout surfaces as a provider error and so yields
     *     {@link Optional#empty()} — the caller falls back to the bullets, preserving the latency bound.
     * @return the synthesized {@link Brief} when the model judged the candidates relevant and returned a
     *     non-empty brief; {@link Optional#empty()} on any failure, timeout, {@code relevant:false}, or
     *     empty brief.
     */
    public Optional<Brief> synthesize(String queryText, List<RecallHit> gated, Duration requestTimeout) {
        if (gated == null || gated.isEmpty()) {
            return Optional.empty();
        }
        Curation curation;
        try {
            ChatResponse response = llm.chat(buildRequest(queryText, gated, requestTimeout));
            curation = parse(response.text());
        } catch (RuntimeException e) {
            log.debug("brief synthesis skipped (keeping bullets): {}", e.toString());
            return Optional.empty();
        }
        if (curation == null || !curation.relevant() || curation.brief().isBlank()) {
            return Optional.empty();
        }
        // Keep only citations pointing at a candidate we actually showed the model, in the order the
        // model used them and de-duplicated — a hallucinated or repeated path never reaches the block.
        Set<String> known = new LinkedHashSet<>();
        for (RecallHit h : gated) {
            if (h.path() != null && !h.path().isBlank()) {
                known.add(h.path());
            }
        }
        List<String> cited = new ArrayList<>();
        for (String p : curation.citedPaths()) {
            if (known.contains(p) && !cited.contains(p)) {
                cited.add(p);
            }
        }
        return Optional.of(new Brief(curation.brief().strip(), cited));
    }

    private ChatRequest buildRequest(String queryText, List<RecallHit> gated, Duration requestTimeout) {
        StringBuilder user = new StringBuilder(512);
        user.append("Prompt: ").append(oneLine(queryText)).append("\n\nCandidates:\n");
        for (RecallHit h : gated) {
            user.append("- path: ").append(oneLine(h.path())).append('\n');
            user.append("  title: ").append(oneLine(h.title())).append('\n');
            user.append("  snippet: ").append(oneLine(boundSnippet(stripMarks(h.snippet())))).append('\n');
        }
        return ChatRequest.structured(
                List.of(
                        ChatMessage.system(prompts.curateSystemPrompt()),
                        ChatMessage.user(user.toString())),
                prompts.curateSchema())
                .withReasoningEffort(reasoningEffort)
                .withRequestTimeout(requestTimeout);
    }

    /**
     * Parse {@code {"relevant":…,"brief":…,"cited_paths":[…]}} tolerantly into a {@link Curation}.
     * A missing/non-boolean {@code relevant} is treated as {@code false}, a missing/non-string
     * {@code brief} as empty, and non-string or blank {@code cited_paths} entries are skipped. Returns
     * {@code null} only when the reply is not valid JSON, which the caller treats as "keep the bullets".
     */
    private Curation parse(String replyJson) {
        JsonNode root;
        try {
            root = json.readTree(replyJson);
        } catch (JacksonException e) {
            log.debug("brief reply was not valid JSON; ignoring: {}", e.toString());
            return null;
        }
        JsonNode relevantNode = root.get("relevant");
        boolean relevant =
                relevantNode != null && relevantNode.isBoolean() && relevantNode.booleanValue();
        JsonNode briefNode = root.get("brief");
        String brief = briefNode != null && briefNode.isString() ? briefNode.stringValue() : "";
        List<String> citedPaths = new ArrayList<>();
        JsonNode citedNode = root.get("cited_paths");
        if (citedNode != null && citedNode.isArray()) {
            for (JsonNode el : citedNode) {
                if (el != null && el.isString()) {
                    String p = el.stringValue().strip();
                    if (!p.isEmpty()) {
                        citedPaths.add(p);
                    }
                }
            }
        }
        return new Curation(relevant, brief, citedPaths);
    }

    private static String boundSnippet(String snippet) {
        if (snippet == null) {
            return "";
        }
        return snippet.length() <= MAX_SNIPPET_CHARS
                ? snippet
                : snippet.substring(0, MAX_SNIPPET_CHARS) + "…";
    }

    /** Strip the {@code <mark>}/{@code </mark>} headline delimiters the SQL snippet adds. */
    private static String stripMarks(String s) {
        return s == null ? "" : s.replace("<mark>", "").replace("</mark>", "");
    }

    /** Collapse newlines so each candidate stays a single YAML-ish line in the prompt. */
    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').strip();
    }

    /**
     * The synthesized brief and the page paths it drew on (already filtered to real candidate paths).
     *
     * @param text       the brief prose; never blank.
     * @param citedPaths the candidate paths the brief cited, in use order, de-duplicated; possibly empty.
     */
    public record Brief(String text, List<String> citedPaths) {

        public Brief {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("brief text must not be blank");
            }
            citedPaths = citedPaths == null ? List.of() : List.copyOf(citedPaths);
        }
    }

    /** The raw, tolerantly-parsed curation reply before grounding the citations. */
    private record Curation(boolean relevant, String brief, List<String> citedPaths) {}
}
