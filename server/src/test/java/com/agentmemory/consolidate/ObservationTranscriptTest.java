package com.agentmemory.consolidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.Identity;
import com.agentmemory.core.Observation;
import com.agentmemory.core.ObservationId;
import com.agentmemory.core.ObservationKind;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.SessionId;
import com.agentmemory.core.Uuid7;
import com.agentmemory.core.WorkspaceId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests (no DB/LLM) for {@link ObservationTranscript} — the transcript rendering and the
 * token-budget chunking the long-session path relies on (#18).
 */
class ObservationTranscriptTest {

    private static final SessionId SESSION = SessionId.newId();

    private static Observation obs(String payload) {
        return new Observation(
                new ObservationId(Uuid7.randomUuid()),
                SESSION,
                Identity.ofProject(WorkspaceId.of("ws"), ProjectId.of("proj")),
                ObservationKind.USER_PROMPT,
                null, null, payload, Instant.parse("2026-06-25T12:00:00Z"));
    }

    @Test
    void renderAllJoinsBlocksInOrderWithHeaders() {
        String t = ObservationTranscript.renderAll(List.of(obs("first"), obs("second")));
        assertThat(t).contains("[1 ").contains("[2 ").contains("user-prompt").contains("first").contains("second");
        assertThat(t.indexOf("first")).isLessThan(t.indexOf("second"));
    }

    @Test
    void shortTranscriptIsASingleChunk() {
        List<String> chunks = ObservationTranscript.chunk(List.of(obs("a"), obs("b")), 10_000);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("a").contains("b");
    }

    @Test
    void longTranscriptIsSplitIntoMultipleChunksEachWithinBudget() {
        // 20 observations whose blocks are ~60 chars each; a 200-char budget forces several chunks.
        List<Observation> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(obs("payload-" + i + "-" + "x".repeat(40)));
        }
        int budget = 200;
        List<String> chunks = ObservationTranscript.chunk(many, budget);

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(budget));
        // Every observation's payload appears somewhere, in order, across the chunks (nothing dropped).
        String joined = String.join("\n", chunks);
        for (int i = 0; i < 20; i++) {
            assertThat(joined).contains("payload-" + i + "-");
        }
    }

    @Test
    void aSingleOversizedObservationIsTruncatedToTheBudget() {
        Observation huge = obs("z".repeat(1000));
        List<String> chunks = ObservationTranscript.chunk(List.of(huge), 100);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).length()).isLessThanOrEqualTo(100);
        assertThat(chunks.get(0)).endsWith(ObservationTranscript.TRUNCATION_MARKER);
    }

    @Test
    void oversizedObservationFlushesTheBufferedChunkFirst() {
        // A small obs then a huge one: the small one must not be lost when the huge one is emitted.
        List<String> chunks = ObservationTranscript.chunk(List.of(obs("small"), obs("z".repeat(1000))), 120);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).contains("small");
        assertThat(chunks.get(1)).endsWith(ObservationTranscript.TRUNCATION_MARKER);
    }

    @Test
    void rejectsEmptyObservationsOrBadBudget() {
        assertThatThrownBy(() -> ObservationTranscript.chunk(List.of(), 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObservationTranscript.chunk(List.of(obs("a")), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- chunkText (the reduce-step collapse helper) ----------------------------------------------

    @Test
    void chunkTextSplitsOnParagraphBoundariesWithinBudget() {
        String text = String.join("\n\n",
                "para one " + "a".repeat(80),
                "para two " + "b".repeat(80),
                "para three " + "c".repeat(80));
        List<String> chunks = ObservationTranscript.chunkText(text, 120);
        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(120));
        String joined = String.join("\n", chunks);
        assertThat(joined).contains("para one").contains("para two").contains("para three");
    }

    @Test
    void chunkTextTruncatesAnOversizedParagraph() {
        List<String> chunks = ObservationTranscript.chunkText("x".repeat(500), 100);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).length()).isLessThanOrEqualTo(100);
        assertThat(chunks.get(0)).endsWith(ObservationTranscript.TRUNCATION_MARKER);
    }

    @Test
    void chunkTextRejectsBlankOrBadBudget() {
        assertThatThrownBy(() -> ObservationTranscript.chunkText("  ", 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ObservationTranscript.chunkText("x", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
