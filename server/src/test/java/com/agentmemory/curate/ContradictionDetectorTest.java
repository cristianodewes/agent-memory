package com.agentmemory.curate;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.Identity;
import com.agentmemory.core.MemoryLayer;
import com.agentmemory.core.Page;
import com.agentmemory.core.PageId;
import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import com.agentmemory.llm.TestDoubleProvider;
import com.agentmemory.recall.Scope;
import com.agentmemory.store.PageRecord;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the LLM contradiction pass (issue #29) using a scripted {@link TestDoubleProvider} —
 * no Spring, no DB. They pin the consumer-side guarantees: a scripted contradiction parses, a
 * hallucinated page reference is dropped (and a contradiction left with fewer than two real references
 * is discarded), a non-JSON reply is tolerated (zero contradictions, never a throw), and fewer than two
 * pages skips the model entirely.
 */
class ContradictionDetectorTest {

    private static final Scope SCOPE = Scope.of("ws", "app");

    private static PageRecord page(String path, String title, String body) {
        Identity id = Identity.ofPage(WorkspaceId.of("ws"), ProjectId.of("app"), PagePath.of(path));
        Page p = new Page(PageId.newId(), id, title, body, true, null,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"));
        return new PageRecord(p, MemoryLayer.SEMANTIC, 0, null);
    }

    @Test
    void parsesContradictionAndDropsHallucinatedReferences() {
        TestDoubleProvider llm = TestDoubleProvider.builder()
                .chatResponder(req -> """
                        {"contradictions":[
                          {"pages":["concepts/a.md","concepts/b.md"],"explanation":"A says X, B says not-X"},
                          {"pages":["concepts/a.md","concepts/ghost.md"],"explanation":"refers to a missing page"}
                        ]}""")
                .build();
        ContradictionDetector detector = new ContradictionDetector(llm);

        List<Contradiction> found = detector.detect(SCOPE, List.of(
                page("concepts/a.md", "A", "the sky is green"),
                page("concepts/b.md", "B", "the sky is not green")));

        // The second item referenced concepts/ghost.md (not shown to the model) -> dropped to a single
        // valid ref -> discarded. Only the real a/b contradiction survives.
        assertThat(found).singleElement().satisfies(c -> {
            assertThat(c.pages()).containsExactly("concepts/a.md", "concepts/b.md");
            assertThat(c.explanation()).contains("not-X");
        });
        assertThat(llm.chatCalls()).hasSize(1);
        assertThat(llm.chatCalls().get(0).wantsStructuredOutput()).isTrue();
    }

    @Test
    void toleratesNonJsonReplyWithZeroContradictions() {
        TestDoubleProvider llm = TestDoubleProvider.builder()
                .chatResponder(req -> "I could not find any contradictions, sorry!")
                .build();
        ContradictionDetector detector = new ContradictionDetector(llm);

        assertThat(detector.detect(SCOPE, List.of(
                page("a.md", "A", "x"), page("b.md", "B", "y")))).isEmpty();
    }

    @Test
    void fewerThanTwoPagesNeverCallsTheModel() {
        TestDoubleProvider llm = TestDoubleProvider.builder()
                .chatResponder(req -> {
                    throw new AssertionError("LLM must not be called when there is nothing to compare");
                })
                .build();
        ContradictionDetector detector = new ContradictionDetector(llm);

        assertThat(detector.detect(SCOPE, List.of(page("solo.md", "Solo", "only one")))).isEmpty();
        assertThat(llm.chatCalls()).isEmpty();
    }
}
