package com.agentmemory.llmrecall;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.llm.LlmException;
import com.agentmemory.llm.TestDoubleProvider;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link QueryExpander} with a scripted, offline {@link TestDoubleProvider}: terms
 * are appended to the original query, de-duplicated against words already present, and capped at
 * {@code maxTerms}; every failure mode falls back to the original text. No Spring, no DB.
 */
class QueryExpanderTest {

    private static final RecallPrompts PROMPTS = new RecallPrompts();

    private static TestDoubleProvider scripted(String json) {
        return TestDoubleProvider.builder().chatResponder(req -> json).build();
    }

    @Test
    void appendsReturnedTermsToTheOriginalQuery() {
        QueryExpander expander = new QueryExpander(
                scripted("{\"terms\":[\"fusion\",\"ranking\"]}"), PROMPTS, 4);

        String out = expander.expand("recall");

        assertThat(out).isEqualTo("recall fusion ranking");
    }

    @Test
    void capsTheNumberOfAppendedTerms() {
        QueryExpander expander = new QueryExpander(
                scripted("{\"terms\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}"), PROMPTS, 2);

        String out = expander.expand("seed");

        assertThat(out).isEqualTo("seed a b"); // only the first 2 kept
    }

    @Test
    void dropsTermsAlreadyPresentInTheQuery() {
        // "recall" already appears in the query, so it must not be appended again; "graph" is new.
        QueryExpander expander = new QueryExpander(
                scripted("{\"terms\":[\"recall\",\"graph\"]}"), PROMPTS, 4);

        String out = expander.expand("hybrid recall");

        assertThat(out).isEqualTo("hybrid recall graph");
    }

    @Test
    void ignoresBlankAndNonStringTermsWithoutFailing() {
        QueryExpander expander = new QueryExpander(
                scripted("{\"terms\":[\"\",\"  \",\"valid\",42,null]}"), PROMPTS, 4);

        String out = expander.expand("seed");

        assertThat(out).isEqualTo("seed valid");
    }

    @Test
    void emptyTermsLeaveTheQueryUnchanged() {
        QueryExpander expander = new QueryExpander(scripted("{\"terms\":[]}"), PROMPTS, 4);

        assertThat(expander.expand("seed")).isEqualTo("seed");
    }

    @Test
    void malformedJsonLeavesTheQueryUnchanged() {
        QueryExpander expander = new QueryExpander(scripted("totally not json"), PROMPTS, 4);

        assertThat(expander.expand("seed")).isEqualTo("seed");
    }

    @Test
    void missingTermsFieldLeavesTheQueryUnchanged() {
        QueryExpander expander = new QueryExpander(scripted("{\"other\":1}"), PROMPTS, 4);

        assertThat(expander.expand("seed")).isEqualTo("seed");
    }

    @Test
    void llmFailureLeavesTheQueryUnchanged() {
        QueryExpander expander = new QueryExpander(
                TestDoubleProvider.builder().chatResponder(req -> {
                    throw new LlmException("provider down");
                }).build(),
                PROMPTS, 4);

        assertThat(expander.expand("seed")).isEqualTo("seed");
    }

    @Test
    void blankInputIsReturnedAsIs() {
        QueryExpander expander = new QueryExpander(scripted("{\"terms\":[\"x\"]}"), PROMPTS, 4);

        assertThat(expander.expand("  ")).isEqualTo("  ");
    }
}
