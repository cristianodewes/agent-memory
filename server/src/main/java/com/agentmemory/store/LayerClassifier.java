package com.agentmemory.store;

import com.agentmemory.core.MemoryLayer;
import com.agentmemory.core.PagePath;
import java.util.Locale;

/**
 * Classifies a page into its retention {@link MemoryLayer} (issue #24). Classification is
 * deterministic and derives from the page's <em>kind</em>, read off the path's top folder — the same
 * folder vocabulary consolidation writes into ({@code concepts/ decisions/ gotchas/ procedures/
 * sessions/ …}, ARCHITECTURE §2.2) and that {@code wiki.PageKind} already standardizes. Reusing that
 * one signal keeps the layer a page lands in consistent with how it was filed, with no LLM call and
 * no second taxonomy to keep in sync.
 *
 * <p>Mapping (folder → layer):
 * <ul>
 *   <li>{@code concepts/}, {@code decisions/}, {@code _rules/}, {@code _slots/} → {@link MemoryLayer#SEMANTIC} —
 *       distilled, timeless knowledge; does not age out. ({@code _slots/} pages are also auto-pinned and
 *       sweep-exempt — issue #26 — so a slot is never classified {@code WORKING} and thus never dropped
 *       from latest at session end.)</li>
 *   <li>{@code procedures/} → {@link MemoryLayer#PROCEDURAL} — how-to/runbooks; frequency-driven.</li>
 *   <li>{@code sessions/}, {@code gotchas/} → {@link MemoryLayer#EPISODIC} — events in time; hot→cold
 *       age decay.</li>
 *   <li>anything else, including a root-level page with no folder → {@link MemoryLayer#WORKING} —
 *       volatile scratch; the shortest retention and dropped from latest at session end.</li>
 * </ul>
 *
 * <p>Kept package-visible vocabulary aside, the mapping is intentionally simple and total: every path
 * resolves to exactly one layer, defaulting to {@link MemoryLayer#WORKING} so an unfiled page is
 * treated as the most volatile rather than accidentally durable. Stateless.
 */
public final class LayerClassifier {

    private LayerClassifier() {
    }

    /**
     * Classify a page by its path.
     *
     * @param path the page path (project-root-relative); never null.
     * @return the retention layer for the page.
     */
    public static MemoryLayer classify(PagePath path) {
        if (path == null) {
            throw new IllegalArgumentException("page path must not be null");
        }
        return fromTopFolder(path.topFolder());
    }

    /**
     * Classify by a raw top-folder token (the {@code wiki.PageKind} wire vocabulary). Exposed so
     * callers that already hold the folder/kind (e.g. a reindex over frontmatter) need not rebuild a
     * {@link PagePath}.
     *
     * @param topFolder the path's top folder, e.g. {@code concepts}; blank ⇒ {@link MemoryLayer#WORKING}.
     * @return the retention layer for that folder.
     */
    public static MemoryLayer fromTopFolder(String topFolder) {
        if (topFolder == null || topFolder.isBlank()) {
            return MemoryLayer.WORKING;
        }
        switch (topFolder.trim().toLowerCase(Locale.ROOT)) {
            case "concepts":
            case "decisions":
            case "_rules":
            case "_slots":
                return MemoryLayer.SEMANTIC;
            case "procedures":
                return MemoryLayer.PROCEDURAL;
            case "sessions":
            case "gotchas":
                return MemoryLayer.EPISODIC;
            default:
                return MemoryLayer.WORKING;
        }
    }
}
