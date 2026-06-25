package com.agentmemory.curate;

import java.util.List;

/**
 * One contradiction found by the LLM lint pass (issue #29): a small set of pages (≥ 2) that state
 * mutually incompatible facts, decisions, or instructions, with a short explanation. The pages are
 * referenced by their project-relative paths (validated against the pages actually shown to the model,
 * so a hallucinated reference is dropped) — the "with references" the acceptance calls for.
 *
 * @param pages       the project-relative paths of the contradicting pages (≥ 2); never null.
 * @param explanation a short description of the contradiction; never null (may be empty).
 */
public record Contradiction(List<String> pages, String explanation) {

    public Contradiction {
        pages = pages == null ? List.of() : List.copyOf(pages);
        explanation = explanation == null ? "" : explanation;
    }
}
