package com.agentmemory.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentmemory.core.MemoryLayer;
import com.agentmemory.core.PagePath;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LayerClassifier} (issue #24): the deterministic page-path → {@link MemoryLayer}
 * mapping, derived from the same top-folder vocabulary the wiki files pages under.
 */
class LayerClassifierTest {

    @Test
    void conceptsDecisionsRulesAndSlotsAreSemantic() {
        assertThat(LayerClassifier.classify(PagePath.of("concepts/recall.md")))
                .isEqualTo(MemoryLayer.SEMANTIC);
        assertThat(LayerClassifier.classify(PagePath.of("decisions/storage.md")))
                .isEqualTo(MemoryLayer.SEMANTIC);
        assertThat(LayerClassifier.classify(PagePath.of("_rules/no-secrets.md")))
                .isEqualTo(MemoryLayer.SEMANTIC);
        // _slots/ pages are auto-pinned and sweep-exempt (#26): they must be SEMANTIC, never WORKING,
        // so the session-end working-drop (dropWorkingFromLatest) cannot remove a slot from latest.
        assertThat(LayerClassifier.classify(PagePath.of("_slots/identity.md")))
                .as("_slots/ must not be WORKING (auto-pinned, never swept)")
                .isEqualTo(MemoryLayer.SEMANTIC);
    }

    @Test
    void proceduresAreProcedural() {
        assertThat(LayerClassifier.classify(PagePath.of("procedures/release.md")))
                .isEqualTo(MemoryLayer.PROCEDURAL);
    }

    @Test
    void sessionsAndGotchasAreEpisodic() {
        assertThat(LayerClassifier.classify(PagePath.of("sessions/2026-06-25.md")))
                .isEqualTo(MemoryLayer.EPISODIC);
        assertThat(LayerClassifier.classify(PagePath.of("gotchas/windows-crlf.md")))
                .isEqualTo(MemoryLayer.EPISODIC);
    }

    @Test
    void unknownFolderDefaultsToWorking() {
        assertThat(LayerClassifier.classify(PagePath.of("scratch/notes.md")))
                .isEqualTo(MemoryLayer.WORKING);
        assertThat(LayerClassifier.classify(PagePath.of("other/misc.md")))
                .isEqualTo(MemoryLayer.WORKING);
    }

    @Test
    void rootLevelPageWithNoFolderIsWorking() {
        // No top folder ⇒ most volatile bucket (an unfiled page is not accidentally durable).
        assertThat(LayerClassifier.classify(PagePath.of("README.md")))
                .isEqualTo(MemoryLayer.WORKING);
    }

    @Test
    void fromTopFolderIsCaseInsensitiveAndBlankSafe() {
        assertThat(LayerClassifier.fromTopFolder("CONCEPTS")).isEqualTo(MemoryLayer.SEMANTIC);
        assertThat(LayerClassifier.fromTopFolder("  procedures  ")).isEqualTo(MemoryLayer.PROCEDURAL);
        assertThat(LayerClassifier.fromTopFolder("")).isEqualTo(MemoryLayer.WORKING);
        assertThat(LayerClassifier.fromTopFolder(null)).isEqualTo(MemoryLayer.WORKING);
    }

    @Test
    void rejectsNullPath() {
        assertThatThrownBy(() -> LayerClassifier.classify(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
