package com.agentmemory.wiki;

import com.agentmemory.core.PagePath;

/**
 * The semantics of <strong>memory slots</strong> (issue #26; ARCHITECTURE §5.1, Survey §2.6): small,
 * auto-pinned, editable pages under the reserved {@code _slots/} path prefix that surface
 * high-resistance context — identity, preferences, and mutable project state — prominently in the
 * briefing.
 *
 * <p>Per the issue's implementation note, a slot is an <em>ordinary page with a reserved path prefix
 * plus frontmatter</em>, so all storage and search reuse applies; this class is the single source of
 * truth for the rules that prefix implies, so no caller re-derives "is this a slot" ad hoc:
 *
 * <ul>
 *   <li><b>Auto-pinned.</b> Every {@code _slots/} page is pinned regardless of its frontmatter flag
 *       ({@link #isAutoPinned}); {@link #normalizePinned} forces it on at write time. Pinned pages are
 *       exempt from the retention sweep ({@link #isExemptFromSweep}, the seam #24/#25's sweep honors).</li>
 *   <li><b>Write regime.</b> A slot declares a {@link SlotKind}: {@code state} (mutable context,
 *       freely overwritten) or {@code invariant} (rules/identity/preferences, high resistance to
 *       change). {@link #editDecision} is the slot-aware validation the write tool (#20) consults so
 *       an invariant slot is not silently clobbered.</li>
 * </ul>
 *
 * <p>Stateless utility (all static); the reserved prefix is {@link PageKind#SLOT}'s wire token.
 */
public final class Slots {

    private Slots() {}

    /** The reserved page-path prefix that marks a slot, including the trailing slash. */
    public static final String PREFIX = PageKind.SLOT.wire() + "/"; // "_slots/"

    /**
     * The write regime a slot declares in its {@code slot_kind} frontmatter. Absent/blank/unknown
     * resolves to {@link #STATE} — the permissive default, so a slot is never accidentally locked
     * just because its kind was omitted.
     */
    public enum SlotKind {
        /** Mutable context (e.g. "current focus"): overwritten freely on every write. */
        STATE("state"),
        /** Rules / identity / preferences: high resistance — an overwrite must be explicit. */
        INVARIANT("invariant");

        private final String wire;

        SlotKind(String wire) {
            this.wire = wire;
        }

        /** @return the lowercase {@code slot_kind} frontmatter token. */
        public String wire() {
            return wire;
        }

        /**
         * Parse a {@code slot_kind} token, case-insensitively. Blank or unrecognized → {@link #STATE}
         * (the permissive default), so an unknown future kind never hard-locks a slot.
         *
         * @param token the frontmatter value, possibly {@code null}.
         * @return the matching kind, or {@link #STATE}.
         */
        public static SlotKind fromWire(String token) {
            if (token == null || token.isBlank()) {
                return STATE;
            }
            String n = token.trim().toLowerCase(java.util.Locale.ROOT);
            for (SlotKind k : values()) {
                if (k.wire.equals(n)) {
                    return k;
                }
            }
            return STATE;
        }
    }

    /** @return {@code true} if {@code path} is under the reserved {@code _slots/} prefix. */
    public static boolean isSlot(PagePath path) {
        return path != null && path.value().startsWith(PREFIX);
    }

    /** @return {@code true} if a page at {@code path} is a slot — slots are auto-pinned (always). */
    public static boolean isAutoPinned(PagePath path) {
        return isSlot(path);
    }

    /**
     * The {@code pinned} value to persist for a page at {@code path}: forced {@code true} for a slot
     * (auto-pin), otherwise the caller's requested flag. The write path calls this so a slot file is
     * always written pinned even if the input frontmatter said otherwise.
     *
     * @param path           the page path.
     * @param requestedPinned the pinned flag the caller asked for.
     * @return the effective pinned flag.
     */
    public static boolean normalizePinned(PagePath path, boolean requestedPinned) {
        return isSlot(path) || requestedPinned;
    }

    /**
     * Whether a page is exempt from the retention sweep (#25) — the seam the sweep consults so it
     * never evicts protected pages. A page is exempt when it is a slot (auto-pinned) or explicitly
     * pinned. Keeping this here (rather than in the sweep) means "what is protected" has one
     * definition shared by the writer's auto-pin and the sweep's skip.
     *
     * @param path   the page path.
     * @param pinned the page's persisted {@code pinned} flag.
     * @return {@code true} if the page must not be swept.
     */
    public static boolean isExemptFromSweep(PagePath path, boolean pinned) {
        return isSlot(path) || pinned;
    }

    /**
     * Decide whether a write to a slot may proceed (issue #26 write regime; the validation seam #20's
     * {@code memory_write_page} calls before persisting). Non-slot pages are unaffected — this only
     * governs {@code _slots/} pages.
     *
     * <ul>
     *   <li>Creating a new slot, or overwriting a {@code state} slot, is always allowed.</li>
     *   <li>Overwriting an existing {@code invariant} slot is <em>denied</em> unless {@code force} is
     *       set — the high-resistance policy. The denial carries an actionable reason.</li>
     * </ul>
     *
     * @param path             the target page path.
     * @param existingSlotKind the current slot's kind when overwriting an existing slot, or
     *                         {@code null} when the slot does not exist yet (a create).
     * @param force            an explicit override to overwrite an invariant slot.
     * @return an {@link EditDecision}; {@link EditDecision#allowed()} is {@code false} only for an
     *     unforced overwrite of an existing invariant slot.
     */
    public static EditDecision editDecision(PagePath path, SlotKind existingSlotKind, boolean force) {
        if (!isSlot(path)) {
            return EditDecision.allow(); // not a slot: no slot policy applies
        }
        boolean overwritingExisting = existingSlotKind != null;
        if (overwritingExisting && existingSlotKind == SlotKind.INVARIANT && !force) {
            return EditDecision.deny(
                    "slot '" + path.value() + "' is an invariant (high-resistance) slot; refusing to "
                            + "overwrite it without an explicit force/override. Change slot_kind to "
                            + "'state' first, or pass force to confirm the intentional change.");
        }
        return EditDecision.allow();
    }

    /**
     * The result of {@link #editDecision}: an allow, or a deny carrying an actionable reason. A
     * tiny value type (rather than a boolean + out-param) so the write tool can surface the reason.
     *
     * @param allowed whether the write may proceed.
     * @param reason  the human-readable denial reason, or {@code null} when allowed.
     */
    public record EditDecision(boolean allowed, String reason) {
        static EditDecision allow() {
            return new EditDecision(true, null);
        }

        static EditDecision deny(String reason) {
            return new EditDecision(false, reason);
        }
    }
}
