package com.agentmemory.wiki;

import com.agentmemory.core.ProjectId;
import com.agentmemory.core.WorkspaceId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads a project's memory slots from the wiki files — the source of truth (DD-002) — for the
 * briefing's dedicated slots section (issue #26). A slot's write regime ({@code slot_kind}) lives in
 * the page frontmatter, so the authoritative way to surface it is to parse the {@code _slots/} files
 * rather than denormalize a column; this keeps slots "ordinary pages with reserved prefix +
 * frontmatter" (the issue's implementation note) with no schema change.
 *
 * <p>Reads are best-effort and never throw on a single bad file: a slot whose frontmatter cannot be
 * parsed is skipped (so one malformed slot never blanks the whole section). Results are ordered by
 * path for a stable briefing.
 */
public final class SlotsReader {

    private final WikiPaths wikiPaths;

    public SlotsReader(WikiPaths wikiPaths) {
        this.wikiPaths = wikiPaths;
    }

    /**
     * One slot as surfaced in a briefing: its path, title, declared {@link Slots.SlotKind}, and
     * pinned flag (always true for a slot — auto-pinned).
     *
     * @param path     the page path (e.g. {@code _slots/preferences.md}).
     * @param title    the slot's human title.
     * @param slotKind the declared write regime ({@code state} when unset).
     * @param pinned   whether the slot is pinned (always true for a slot).
     */
    public record SlotView(String path, String title, String slotKind, boolean pinned) {}

    /**
     * List the slots for {@code (workspace, project)} by reading {@code wiki/<ws>/<project>/_slots/}.
     * Returns an empty list when the project or its {@code _slots/} directory does not exist yet.
     *
     * @param workspace the workspace coordinate.
     * @param project   the project coordinate.
     * @return the slots, ordered by path; possibly empty.
     */
    public List<SlotView> list(WorkspaceId workspace, ProjectId project) {
        Path slotsDir = wikiPaths.wikiDir()
                .resolve(workspace.value())
                .resolve(project.value())
                .resolve(PageKind.SLOT.wire()); // _slots
        if (!Files.isDirectory(slotsDir)) {
            return List.of();
        }

        List<SlotView> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(slotsDir)) {
            List<Path> mdFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".md"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path file : mdFiles) {
                SlotView view = readSlot(file);
                if (view != null) {
                    out.add(view);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list slots under " + slotsDir, e);
        }
        out.sort(Comparator.comparing(SlotView::path));
        return out;
    }

    /** Parse one slot file's frontmatter into a {@link SlotView}, or {@code null} if it is unreadable. */
    private SlotView readSlot(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            MarkdownDocument doc = MarkdownDocument.parse(text);
            PageFrontmatter fm = doc.frontmatter();
            // Only surface pages that really sit under the _slots/ prefix (defensive: ignore a stray
            // file whose frontmatter path says otherwise).
            if (!Slots.isSlot(fm.path())) {
                return null;
            }
            Slots.SlotKind kind = Slots.SlotKind.fromWire(fm.slotKind());
            boolean pinned = Slots.normalizePinned(fm.path(), fm.pinned());
            return new SlotView(fm.path().value(), fm.title(), kind.wire(), pinned);
        } catch (IOException | RuntimeException e) {
            // A single malformed/unreadable slot must not break the briefing; skip it.
            return null;
        }
    }
}
