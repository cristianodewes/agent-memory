package com.agentmemory.wiki;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentmemory.core.PagePath;
import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link SlotsReader}: reading {@code _slots/} pages from the wiki files (source of
 * truth) with their {@code slot_kind}, ordered and resilient to a single bad file — what the
 * briefing's dedicated slots section consumes (issue #26).
 */
class SlotsReaderTest {

    private static final WorkspaceId WS = WorkspaceId.of("acme");
    private static final ProjectId PROJECT = ProjectId.of("app");

    private static SlotsReader reader(Path data) {
        return new SlotsReader(new WikiPaths(data.resolve("wiki")));
    }

    /** Write a slot page file under wiki/acme/app/_slots/<name>. */
    private static void writeSlot(Path data, String name, String title, String slotKind, boolean pinned)
            throws Exception {
        String pathValue = "_slots/" + name;
        PagePath path = PagePath.of(pathValue);
        PageFrontmatter fm = new PageFrontmatter(
                title, PageKind.SLOT, pinned, slotKind, WS, PROJECT, path,
                Instant.parse("2026-06-25T12:00:00Z"), Instant.parse("2026-06-25T12:00:00Z"));
        String rendered = new MarkdownDocument(fm, "body of " + title + "\n").render();
        Path file = data.resolve("wiki").resolve("acme").resolve("app").resolve(path.value());
        Files.createDirectories(file.getParent());
        Files.writeString(file, rendered, StandardCharsets.UTF_8);
    }

    @Test
    void listsSlotsWithKindOrderedByPath(@TempDir Path data) throws Exception {
        writeSlot(data, "preferences.md", "Preferences", "invariant", true);
        writeSlot(data, "current-focus.md", "Current focus", "state", true);

        List<SlotsReader.SlotView> slots = reader(data).list(WS, PROJECT);

        assertThat(slots).extracting(SlotsReader.SlotView::path)
                .containsExactly("_slots/current-focus.md", "_slots/preferences.md");
        SlotsReader.SlotView focus = slots.get(0);
        assertThat(focus.title()).isEqualTo("Current focus");
        assertThat(focus.slotKind()).isEqualTo("state");
        assertThat(focus.pinned()).isTrue();
        assertThat(slots.get(1).slotKind()).isEqualTo("invariant");
    }

    @Test
    void slotMissingSlotKindDefaultsToState(@TempDir Path data) throws Exception {
        writeSlot(data, "notes.md", "Notes", null, true); // no slot_kind in frontmatter

        List<SlotsReader.SlotView> slots = reader(data).list(WS, PROJECT);

        assertThat(slots).hasSize(1);
        assertThat(slots.get(0).slotKind()).isEqualTo("state");
    }

    @Test
    void emptyWhenNoSlotsDirectory(@TempDir Path data) {
        assertThat(reader(data).list(WS, PROJECT)).isEmpty();
    }

    @Test
    void skipsAMalformedSlotFileButKeepsTheRest(@TempDir Path data) throws Exception {
        writeSlot(data, "good.md", "Good", "state", true);
        // A malformed file with no frontmatter fence must be skipped, not blow up the listing.
        Path bad = data.resolve("wiki").resolve("acme").resolve("app").resolve("_slots").resolve("bad.md");
        Files.createDirectories(bad.getParent());
        Files.writeString(bad, "not a real page, no frontmatter at all");

        List<SlotsReader.SlotView> slots = reader(data).list(WS, PROJECT);

        assertThat(slots).extracting(SlotsReader.SlotView::path).containsExactly("_slots/good.md");
    }
}
