package com.agentmemory.curate;

import com.agentmemory.llm.ChatMessage;
import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.ChatResponse;
import com.agentmemory.llm.JsonSchema;
import com.agentmemory.llm.LlmProvider;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The LLM contradiction pass behind {@code memory_lint} (issue #29): shows the model a project's pages
 * and asks it to name pairs/small groups that state mutually incompatible facts, decisions, or
 * instructions. Structured-JSON only (invariant #7) — the reply is constrained to {@link #SCHEMA_JSON}
 * server-side and re-validated here.
 *
 * <h2>Safety / robustness</h2>
 * <ul>
 *   <li>References are validated against the exact pages shown to the model; a hallucinated path is
 *       dropped, and a "contradiction" left with fewer than two real references is discarded — so every
 *       reported contradiction points at pages that exist (the acceptance's "with references").</li>
 *   <li>A non-JSON or schema-empty reply yields zero contradictions rather than throwing, so the rule
 *       part of a lint run (the zero-cost curator) is never lost to an LLM hiccup.</li>
 *   <li>The prompt is bounded (at most {@value #MAX_PAGES} pages, {@value #MAX_BODY_CHARS} chars each)
 *       so a large project cannot blow the context window.</li>
 * </ul>
 */
public class ContradictionDetector {

    /** The structured-output schema name handed to the provider. */
    static final String SCHEMA_NAME = "memory_lint_contradictions";

    /** JSON-schema the reply is constrained to: a list of {pages[], explanation}. */
    static final String SCHEMA_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "contradictions": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "properties": {
                      "pages": { "type": "array", "items": { "type": "string" } },
                      "explanation": { "type": "string" }
                    },
                    "required": ["pages", "explanation"]
                  }
                }
              },
              "required": ["contradictions"]
            }
            """;

    private static final int MAX_PAGES = 60;
    private static final int MAX_BODY_CHARS = 1200;

    private final LlmProvider llm;
    private final ObjectMapper json = JsonMapper.builder().build();

    public ContradictionDetector(LlmProvider llm) {
        this.llm = llm;
    }

    /**
     * Find contradictions among a project's latest pages.
     *
     * @param scope the project (for the prompt context); never null.
     * @param pages the project's latest pages; fewer than two means nothing can contradict.
     * @return the validated contradictions (possibly empty).
     */
    public List<Contradiction> detect(Scope scope, List<PageRecord> pages) {
        if (pages == null || pages.size() < 2) {
            return List.of();
        }
        List<PageRecord> sample = pages.size() > MAX_PAGES ? pages.subList(0, MAX_PAGES) : pages;
        Set<String> knownPaths = new LinkedHashSet<>();
        for (PageRecord p : sample) {
            knownPaths.add(p.page().path().value());
        }

        ChatRequest request = ChatRequest.structured(
                List.of(
                        ChatMessage.system(systemPrompt()),
                        ChatMessage.user(userPrompt(scope, sample))),
                new JsonSchema(SCHEMA_NAME, SCHEMA_JSON));
        ChatResponse response = llm.chat(request); // LlmException propagates to the caller
        return parse(response.text(), knownPaths);
    }

    private List<Contradiction> parse(String replyJson, Set<String> knownPaths) {
        if (replyJson == null || replyJson.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = json.readTree(replyJson);
        } catch (JacksonException e) {
            return List.of(); // tolerate a non-JSON reply — keep the rule findings
        }
        JsonNode arr = root.isObject() ? root.get("contradictions") : null;
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<Contradiction> out = new ArrayList<>();
        for (JsonNode c : arr) {
            if (c == null || !c.isObject()) {
                continue;
            }
            JsonNode pagesNode = c.get("pages");
            if (pagesNode == null || !pagesNode.isArray()) {
                continue;
            }
            List<String> refs = new ArrayList<>();
            for (JsonNode p : pagesNode) {
                if (p != null && p.isString() && knownPaths.contains(p.stringValue())) {
                    if (!refs.contains(p.stringValue())) {
                        refs.add(p.stringValue());
                    }
                }
            }
            if (refs.size() < 2) {
                continue; // a real contradiction references ≥ 2 existing pages
            }
            JsonNode exp = c.get("explanation");
            String explanation = (exp != null && exp.isString()) ? exp.stringValue() : "";
            out.add(new Contradiction(refs, explanation));
        }
        return out;
    }

    private static String systemPrompt() {
        return "You are a meticulous knowledge-base auditor. You are given the pages of a single "
                + "project's long-term memory. Identify pairs or small groups of pages that DIRECTLY "
                + "CONTRADICT each other — stating mutually incompatible facts, decisions, values, or "
                + "instructions. Report ONLY genuine contradictions, never mere overlap, related "
                + "topics, or differing detail. Reference each page by its exact path as given. If "
                + "there are no contradictions, return an empty list.";
    }

    private static String userPrompt(Scope scope, List<PageRecord> pages) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("Project ").append(scope.workspaceSlug()).append('/').append(scope.projectSlug())
                .append(". Audit these pages for contradictions:\n\n");
        for (PageRecord p : pages) {
            String body = p.page().body();
            if (body.length() > MAX_BODY_CHARS) {
                body = body.substring(0, MAX_BODY_CHARS) + " …";
            }
            sb.append("## ").append(p.page().path().value())
                    .append(" — ").append(p.page().title()).append('\n')
                    .append(body).append("\n\n");
        }
        sb.append("Return the contradictions as structured JSON; reference pages by the exact paths "
                + "above.");
        return sb.toString();
    }
}
