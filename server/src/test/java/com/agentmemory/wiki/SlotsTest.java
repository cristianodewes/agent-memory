package com.agentmemory.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.PagePath;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Slots}: the reserved-prefix classification, auto-pin, the never-swept
 * exemption seam, {@code slot_kind} parsing, and the invariant/state write-regime policy (issue #26
 * acceptance: auto-pinned/never-swept and slot_kind honored).
 */
class SlotsTest {

    private static PagePath path(String p) {
        return PagePath.of(p);
    }

    @Test
    void recognizesSlotsByReservedPrefix() {
        assertThat(Slots.isSlot(path("_slots/preferences.md"))).isTrue();
        assertThat(Slots.isSlot(path("_slots/state/current-focus.md"))).isTrue();
        assertThat(Slots.isSlot(path("concepts/recall.md"))).isFalse();
        assertThat(Slots.isSlot(path("_rules/style.md"))).isFalse();
    }

    @Test
    void slotsAreAutoPinned() {
        assertThat(Slots.isAutoPinned(path("_slots/identity.md"))).isTrue();
        assertThat(Slots.isAutoPinned(path("concepts/recall.md"))).isFalse();
    }

    @Test
    void normalizePinnedForcesPinForSlotsAndKeepsRequestOtherwise() {
        // Slot: pinned forced on even when the caller asked for false.
        assertThat(Slots.normalizePinned(path("_slots/prefs.md"), false)).isTrue();
        assertThat(Slots.normalizePinned(path("_slots/prefs.md"), true)).isTrue();
        // Non-slot: the caller's flag is preserved.
        assertThat(Slots.normalizePinned(path("concepts/x.md"), false)).isFalse();
        assertThat(Slots.normalizePinned(path("concepts/x.md"), true)).isTrue();
    }

    @Test
    void slotsAndPinnedPagesAreExemptFromSweep() {
        // A slot is exempt regardless of the persisted pinned flag.
        assertThat(Slots.isExemptFromSweep(path("_slots/identity.md"), false)).isTrue();
        // A non-slot is exempt only when explicitly pinned.
        assertThat(Slots.isExemptFromSweep(path("concepts/recall.md"), true)).isTrue();
        assertThat(Slots.isExemptFromSweep(path("concepts/recall.md"), false)).isFalse();
    }

    @Test
    void slotKindParsesLenientlyDefaultingToState() {
        assertThat(Slots.SlotKind.fromWire("state")).isEqualTo(Slots.SlotKind.STATE);
        assertThat(Slots.SlotKind.fromWire("invariant")).isEqualTo(Slots.SlotKind.INVARIANT);
        assertThat(Slots.SlotKind.fromWire("INVARIANT")).isEqualTo(Slots.SlotKind.INVARIANT);
        // Blank/null/unknown -> STATE (permissive default; never accidentally locks a slot).
        assertThat(Slots.SlotKind.fromWire(null)).isEqualTo(Slots.SlotKind.STATE);
        assertThat(Slots.SlotKind.fromWire("")).isEqualTo(Slots.SlotKind.STATE);
        assertThat(Slots.SlotKind.fromWire("whatever")).isEqualTo(Slots.SlotKind.STATE);
    }

    // --- write regime policy -------------------------------------------------------------------

    @Test
    void stateSlotsOverwriteFreely() {
        Slots.EditDecision d = Slots.editDecision(
                path("_slots/current-focus.md"), Slots.SlotKind.STATE, false);
        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).isNull();
    }

    @Test
    void invariantSlotsResistOverwriteWithoutForce() {
        Slots.EditDecision d = Slots.editDecision(
                path("_slots/identity.md"), Slots.SlotKind.INVARIANT, false);
        assertThat(d.allowed()).isFalse();
        assertThat(d.reason()).contains("invariant").contains("force");
    }

    @Test
    void invariantSlotsCanBeOverwrittenWithForce() {
        Slots.EditDecision d = Slots.editDecision(
                path("_slots/identity.md"), Slots.SlotKind.INVARIANT, true);
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void creatingANewSlotIsAlwaysAllowed() {
        // existingSlotKind == null means the slot does not exist yet (a create), even for a path that
        // will become an invariant — there is nothing to protect yet.
        Slots.EditDecision d = Slots.editDecision(path("_slots/identity.md"), null, false);
        assertThat(d.allowed()).isTrue();
    }

    @Test
    void nonSlotPagesAreUnaffectedByTheSlotPolicy() {
        Slots.EditDecision d = Slots.editDecision(
                path("concepts/recall.md"), Slots.SlotKind.INVARIANT, false);
        assertThat(d.allowed()).isTrue(); // policy only governs _slots/
    }
}
