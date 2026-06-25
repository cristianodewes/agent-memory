package com.agentmemory.consolidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests (no DB/LLM) for {@link SessionSynthesisParser} — the consumer-side guard on the
 * LLM's structured-JSON reply (#18, invariant #7).
 */
class SessionSynthesisParserTest {

    private final SessionSynthesisParser parser = new SessionSynthesisParser();

    @Test
    void parsesAWellFormedReply() {
        String json = """
                {
                  "title": "Vector recall arm",
                  "summary": "Added the pgvector arm and fixed the CI smoke.",
                  "decisions": ["Chose cosine over ivfflat"],
                  "follow_ups": ["Wire embed-on-write in #14"],
                  "open_questions": [],
                  "highlights": ["279 tests pass", "  "]
                }
                """;
        SynthesizedSession s = parser.parse(json);
        assertThat(s.title()).isEqualTo("Vector recall arm");
        assertThat(s.summary()).contains("pgvector");
        assertThat(s.decisions()).containsExactly("Chose cosine over ivfflat");
        assertThat(s.followUps()).containsExactly("Wire embed-on-write in #14");
        assertThat(s.openQuestions()).isEmpty();
        // blank highlight entry is dropped by SynthesizedSession normalization
        assertThat(s.highlights()).containsExactly("279 tests pass");
    }

    @Test
    void treatsMissingArraysAsEmpty() {
        // Arrays absent entirely (a lenient model) → empty, not an error.
        String json = "{\"title\":\"t\",\"summary\":\"s\"}";
        SynthesizedSession s = parser.parse(json);
        assertThat(s.decisions()).isEmpty();
        assertThat(s.followUps()).isEmpty();
        assertThat(s.openQuestions()).isEmpty();
        assertThat(s.highlights()).isEmpty();
    }

    @Test
    void rejectsNonJson() {
        assertThatThrownBy(() -> parser.parse("not json at all"))
                .isInstanceOf(ConsolidationException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void rejectsMissingTitleOrSummary() {
        assertThatThrownBy(() -> parser.parse("{\"summary\":\"s\"}"))
                .isInstanceOf(ConsolidationException.class)
                .hasMessageContaining("title");
        assertThatThrownBy(() -> parser.parse("{\"title\":\"t\"}"))
                .isInstanceOf(ConsolidationException.class)
                .hasMessageContaining("summary");
    }

    @Test
    void rejectsBlankReply() {
        assertThatThrownBy(() -> parser.parse("   "))
                .isInstanceOf(ConsolidationException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsANonObjectReply() {
        assertThatThrownBy(() -> parser.parse("[1,2,3]"))
                .isInstanceOf(ConsolidationException.class)
                .hasMessageContaining("not a JSON object");
    }

    @Test
    void rejectsAWrongTypedArrayField() {
        assertThatThrownBy(() -> parser.parse("{\"title\":\"t\",\"summary\":\"s\",\"decisions\":\"nope\"}"))
                .isInstanceOf(ConsolidationException.class)
                .hasMessageContaining("decisions");
    }

    @Test
    void rejectsANonStringArrayElementRatherThanSilentlyDroppingIt() {
        // A garbage element (number/object) in an array signals a malformed reply — fail loudly so a
        // page is never rendered with content quietly missing.
        assertThatThrownBy(() ->
                parser.parse("{\"title\":\"t\",\"summary\":\"s\",\"decisions\":[\"ok\", 5]}"))
                .isInstanceOf(ConsolidationException.class)
                .hasMessageContaining("non-string element");
    }

    @Test
    void distinguishesBlankFromMissingAndWrongType() {
        assertThatThrownBy(() -> parser.parse("{\"title\":\"  \",\"summary\":\"s\"}"))
                .isInstanceOf(ConsolidationException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> parser.parse("{\"title\":123,\"summary\":\"s\"}"))
                .isInstanceOf(ConsolidationException.class)
                .hasMessageContaining("not a string");
        assertThatThrownBy(() -> parser.parse("{\"summary\":\"s\"}"))
                .isInstanceOf(ConsolidationException.class)
                .hasMessageContaining("missing");
    }
}
