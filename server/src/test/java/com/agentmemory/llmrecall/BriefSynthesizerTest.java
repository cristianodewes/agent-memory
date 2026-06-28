package com.agentmemory.llmrecall;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.llm.ChatRequest;
import com.agentmemory.llm.LlmException;
import com.agentmemory.llm.ReasoningEffort;
import com.agentmemory.llm.TestDoubleProvider;
import com.agentmemory.recall.HitSource;
import com.agentmemory.recall.RecallHit;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link BriefSynthesizer} with a scripted, offline {@link TestDoubleProvider} (issue
 * #135, Fase 3): a relevant reply is parsed into a brief with grounded citations, every degradation
 * (not-relevant, blank brief, malformed JSON, provider failure/timeout) yields {@link Optional#empty()}
 * so the caller keeps its bullets, citations are filtered to real candidate paths, snippets are capped,
 * and the call carries the MINIMAL reasoning hint + per-call timeout. No Spring, no DB.
 */
class BriefSynthesizerTest {

    private static final RecallPrompts PROMPTS = new RecallPrompts();

    private static RecallHit page(String id, String title, String snippet) {
        return new RecallHit(HitSource.PAGE, id, "p/" + id + ".md", title, null, 0.9, 1, snippet);
    }

    private static List<RecallHit> gated(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> page(id, "Title " + id, "snippet for " + id)).toList();
    }

    /** A provider returning a fixed curate JSON regardless of the request. */
    private static TestDoubleProvider scripted(String json) {
        return TestDoubleProvider.builder().chatResponder(req -> json).build();
    }

    private static TestDoubleProvider scripted(Function<ChatRequest, String> responder) {
        return TestDoubleProvider.builder().chatResponder(responder).build();
    }

    @Test
    void synthesizesABriefWithGroundedCitations() {
        TestDoubleProvider llm = scripted(
                "{\"relevant\":true,\"brief\":\"The server runs behind Traefik.\","
                        + "\"cited_paths\":[\"p/a.md\",\"p/b.md\"]}");
        BriefSynthesizer synth = new BriefSynthesizer(llm, PROMPTS);

        Optional<BriefSynthesizer.Brief> out = synth.synthesize("where", gated("a", "b"), null);

        assertThat(out).isPresent();
        assertThat(out.get().text()).isEqualTo("The server runs behind Traefik.");
        assertThat(out.get().citedPaths()).containsExactly("p/a.md", "p/b.md");
        assertThat(llm.chatCalls()).hasSize(1);
        // The call uses the curate system prompt + schema (structured output).
        ChatRequest sent = llm.chatCalls().get(0);
        assertThat(sent.wantsStructuredOutput()).isTrue();
        assertThat(sent.schema().name()).isEqualTo(RecallPrompts.CURATE_SCHEMA_NAME);
        assertThat(sent.messages().get(0).content()).isEqualTo(PROMPTS.curateSystemPrompt());
        // The candidate paths/titles/snippets are in the user message.
        assertThat(sent.messages().get(1).content()).contains("path: p/a.md").contains("path: p/b.md");
    }

    @Test
    void notRelevantYieldsEmpty() {
        BriefSynthesizer synth = new BriefSynthesizer(
                scripted("{\"relevant\":false,\"brief\":\"\",\"cited_paths\":[]}"), PROMPTS);

        assertThat(synth.synthesize("q", gated("a"), null)).isEmpty();
    }

    @Test
    void blankBriefYieldsEmpty() {
        BriefSynthesizer synth = new BriefSynthesizer(
                scripted("{\"relevant\":true,\"brief\":\"   \",\"cited_paths\":[\"p/a.md\"]}"), PROMPTS);

        assertThat(synth.synthesize("q", gated("a"), null)).isEmpty();
    }

    @Test
    void malformedJsonYieldsEmpty() {
        BriefSynthesizer synth = new BriefSynthesizer(scripted("not json at all"), PROMPTS);

        assertThat(synth.synthesize("q", gated("a"), null)).isEmpty();
    }

    @Test
    void providerFailureOrTimeoutYieldsEmptyNeverThrows() {
        // A provider error stands in for both a hard failure and a per-call HTTP timeout.
        BriefSynthesizer synth = new BriefSynthesizer(scripted(req -> {
            throw new LlmException("boom");
        }), PROMPTS);

        assertThat(synth.synthesize("q", gated("a"), Duration.ofMillis(500))).isEmpty();
    }

    @Test
    void citationsAreFilteredToRealCandidatePathsDedupedAndOrdered() {
        // The model cites a hallucinated path (p/zzz.md), repeats p/b.md, and reverses the order. Only
        // real candidate paths survive, de-duplicated, in the order the model used them.
        TestDoubleProvider llm = scripted(
                "{\"relevant\":true,\"brief\":\"A brief.\","
                        + "\"cited_paths\":[\"p/b.md\",\"p/zzz.md\",\"p/b.md\",\"p/a.md\"]}");
        BriefSynthesizer synth = new BriefSynthesizer(llm, PROMPTS);

        Optional<BriefSynthesizer.Brief> out = synth.synthesize("q", gated("a", "b"), null);

        assertThat(out).isPresent();
        assertThat(out.get().citedPaths()).containsExactly("p/b.md", "p/a.md");
    }

    @Test
    void keepsTheBriefButDropsAllCitationsWhenNoneAreReal() {
        // Every cited path is hallucinated → the brief still stands (grounded prose), just with no Sources.
        TestDoubleProvider llm = scripted(
                "{\"relevant\":true,\"brief\":\"A grounded brief.\",\"cited_paths\":[\"p/nope.md\"]}");
        BriefSynthesizer synth = new BriefSynthesizer(llm, PROMPTS);

        Optional<BriefSynthesizer.Brief> out = synth.synthesize("q", gated("a"), null);

        assertThat(out).isPresent();
        assertThat(out.get().text()).isEqualTo("A grounded brief.");
        assertThat(out.get().citedPaths()).isEmpty();
    }

    @Test
    void emptyOrNullGatedMakesNoCallAndYieldsEmpty() {
        TestDoubleProvider llm = scripted("{\"relevant\":true,\"brief\":\"x\",\"cited_paths\":[]}");
        BriefSynthesizer synth = new BriefSynthesizer(llm, PROMPTS);

        assertThat(synth.synthesize("q", List.of(), null)).isEmpty();
        assertThat(synth.synthesize("q", null, null)).isEmpty();
        assertThat(llm.chatCalls()).isEmpty(); // never bothered the provider
    }

    @Test
    void emitsMinimalReasoningHintAndPerCallTimeoutWhenConfigured() {
        TestDoubleProvider llm = scripted(
                "{\"relevant\":true,\"brief\":\"A brief.\",\"cited_paths\":[\"p/a.md\"]}");
        BriefSynthesizer synth = new BriefSynthesizer(llm, PROMPTS, ReasoningEffort.MINIMAL);

        synth.synthesize("q", gated("a"), Duration.ofMillis(2500));

        ChatRequest sent = llm.chatCalls().get(0);
        assertThat(sent.reasoningEffort()).isEqualTo(ReasoningEffort.MINIMAL);
        assertThat(sent.requestTimeout()).isEqualTo(Duration.ofMillis(2500));
    }

    @Test
    void leavesReasoningUnsetByDefault() {
        TestDoubleProvider llm = scripted(
                "{\"relevant\":true,\"brief\":\"A brief.\",\"cited_paths\":[\"p/a.md\"]}");
        BriefSynthesizer synth = new BriefSynthesizer(llm, PROMPTS);

        synth.synthesize("q", gated("a"), null);

        ChatRequest sent = llm.chatCalls().get(0);
        assertThat(sent.reasoningEffort()).isNull();
        assertThat(sent.requestTimeout()).isNull();
    }

    @Test
    void capsLongSnippetsInThePrompt() {
        // A snippet longer than the 280-char cap is truncated before it reaches the model, so one long
        // page cannot blow up the curation prompt (and the injection surface stays bounded).
        String longSnippet = "y".repeat(400);
        TestDoubleProvider llm = scripted(
                "{\"relevant\":true,\"brief\":\"A brief.\",\"cited_paths\":[\"p/a.md\"]}");
        BriefSynthesizer synth = new BriefSynthesizer(llm, PROMPTS);

        synth.synthesize("q", List.of(page("a", "Alpha", longSnippet)), null);

        String sentToLlm = llm.chatCalls().get(0).messages().get(1).content();
        assertThat(sentToLlm).contains("…"); // truncation marker present
        assertThat(sentToLlm).doesNotContain("y".repeat(281)); // never the full 400 chars
    }

    @Test
    void stripsMarkTagsFromSnippetsBeforeSynthesis() {
        TestDoubleProvider llm = scripted(
                "{\"relevant\":true,\"brief\":\"A brief.\",\"cited_paths\":[\"p/a.md\"]}");
        BriefSynthesizer synth = new BriefSynthesizer(llm, PROMPTS);

        synth.synthesize("q", List.of(page("a", "Alpha", "the <mark>marked</mark> term")), null);

        String sentToLlm = llm.chatCalls().get(0).messages().get(1).content();
        assertThat(sentToLlm).contains("the marked term").doesNotContain("<mark>");
    }

    @Test
    void curateSystemPromptCarriesTheAntiInjectionClause() {
        // Acceptance: the curation prompt must instruct the model to treat snippets as untrusted data,
        // not instructions (the brief is prose synthesized from an injection surface).
        String prompt = PROMPTS.curateSystemPrompt().toLowerCase();
        assertThat(prompt).contains("untrusted");
        assertThat(prompt).contains("not instructions");
    }
}
