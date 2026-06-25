package com.agentmemory.consolidate;

import java.util.List;

/**
 * The structured result of compiling a session's observations with the LLM (issue #18) — the typed,
 * validated shape of the model's JSON reply (invariant #7: structured-JSON only). It is rendered to
 * the {@code sessions/<id>.md} body and is also the structured content the M4 handoff (#22) draws
 * from, so the fields are deliberately handoff-shaped (summary / decisions / follow-ups / open
 * questions) rather than free prose.
 *
 * <p>Lists are never null (normalized to empty) and {@code title}/{@code summary} are required; the
 * synthesizer rejects a reply missing them. See {@link #SCHEMA_JSON} for the JSON-Schema the provider
 * constrains the reply to and {@link SessionSynthesisParser} for the parse.
 *
 * @param title       a short, human title for the session page (e.g. "Vector recall arm + CI fix").
 * @param summary     a coherent narrative summary of what happened in the session.
 * @param decisions   notable decisions made (each a self-contained sentence); possibly empty.
 * @param followUps   open action items / next steps; possibly empty.
 * @param openQuestions unresolved questions worth carrying into the next session; possibly empty.
 * @param highlights  short bullet highlights / key facts surfaced in the session; possibly empty.
 */
public record SynthesizedSession(
        String title,
        String summary,
        List<String> decisions,
        List<String> followUps,
        List<String> openQuestions,
        List<String> highlights) {

    public SynthesizedSession {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("synthesized session title must not be blank");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("synthesized session summary must not be blank");
        }
        decisions = normalize(decisions);
        followUps = normalize(followUps);
        openQuestions = normalize(openQuestions);
        highlights = normalize(highlights);
    }

    private static List<String> normalize(List<String> in) {
        if (in == null) {
            return List.of();
        }
        // Drop blank/null entries (a model may emit an empty bullet) and trim, preserving order.
        return in.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::strip)
                .toList();
    }

    /** The schema name handed to the provider (labels the request + 24h server-side schema cache). */
    public static final String SCHEMA_NAME = "session_synthesis";

    /**
     * The JSON-Schema (draft-2020-12 subset) the LLM reply is constrained to (invariant #7). Required:
     * {@code title}, {@code summary}; arrays of strings for the rest. {@code additionalProperties:false}
     * keeps the reply tight and parseable. Kept here next to the record it parses into so the contract
     * is reviewable in one place; the human-readable instructions live in the versioned prompt resource.
     */
    public static final String SCHEMA_JSON = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["title", "summary", "decisions", "follow_ups", "open_questions", "highlights"],
              "properties": {
                "title":   { "type": "string", "minLength": 1, "maxLength": 120 },
                "summary": { "type": "string", "minLength": 1 },
                "decisions":      { "type": "array", "items": { "type": "string" } },
                "follow_ups":     { "type": "array", "items": { "type": "string" } },
                "open_questions": { "type": "array", "items": { "type": "string" } },
                "highlights":     { "type": "array", "items": { "type": "string" } }
              }
            }
            """;
}
