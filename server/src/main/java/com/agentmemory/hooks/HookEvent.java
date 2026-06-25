package com.agentmemory.hooks;

import com.agentmemory.core.ObservationKind;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Normalizes the <em>agent-native</em> hook event name a client sends down to the canonical
 * {@link ObservationKind} the store is written against (ARCHITECTURE §5.4; issue #7). Different
 * agents spell the same lifecycle moment differently — Claude Code emits {@code "PostToolUse"},
 * a shell hook might emit {@code "post-tool-use"} or {@code "post_tool_use"}, Codex/Cursor use
 * their own names — and <strong>every</strong> recognized spelling must resolve to one canonical
 * kind so no real hook is silently dropped.
 *
 * <p>This is the fix for a documented prior-art bug: a native hook sent {@code "user-prompt-submit"}
 * and the server, matching only an exact literal, dropped the prompt entirely. Here that spelling —
 * and every other documented one — is in the alias table and maps to {@link ObservationKind#USER_PROMPT}.
 *
 * <h2>Resolution order</h2>
 * <ol>
 *   <li><strong>Alias table</strong> (case-insensitive, {@code _}/{@code -}/space-insensitive): the
 *       explicit per-agent spellings below.</li>
 *   <li><strong>Canonical fallback</strong>: {@link ObservationKind#fromWire(String)}, so a client
 *       that already sends the canonical kebab token resolves without needing a table entry.</li>
 *   <li><strong>{@link ObservationKind#OTHER}</strong>: anything unrecognized — never an exception.
 *       Pair {@code OTHER} with the payload's {@code extension} namespace to record third-party
 *       events without growing the enum.</li>
 * </ol>
 *
 * <p>The table is the <em>extension seam</em>: adding a new agent's spelling is a one-line addition
 * here and does not change how any existing or unknown event parses. This class is pure (no IO,
 * no Spring) and lives in {@code hooks} rather than {@code core} because the alias vocabulary is a
 * capture-pipeline concern, not part of the shared domain (issue #3 keeps only the canonical set).
 */
public final class HookEvent {

    private HookEvent() {}

    /**
     * Documented agent-native event spellings → canonical kind. Keys are stored in the normalized
     * form produced by {@link #normalizeKey(String)} (lower-case, {@code _}/space → {@code -}), so a
     * single entry covers all casings of a name. The canonical kebab tokens themselves are handled
     * by the {@link ObservationKind#fromWire(String)} fallback and are intentionally not duplicated
     * here.
     *
     * <p>Sources: Claude Code lifecycle hook names; Codex / Cursor / generic agent spellings
     * (Survey §2.1, §3.4).
     */
    private static final Map<String, ObservationKind> ALIASES = Map.ofEntries(
            // --- session start ---
            Map.entry("sessionstart", ObservationKind.SESSION_START),
            Map.entry("session-started", ObservationKind.SESSION_START),
            Map.entry("startup", ObservationKind.SESSION_START),
            Map.entry("start", ObservationKind.SESSION_START),

            // --- user prompt --- (the prior-art "user-prompt-submit" drop lives here)
            Map.entry("userpromptsubmit", ObservationKind.USER_PROMPT),
            Map.entry("user-prompt-submit", ObservationKind.USER_PROMPT),
            Map.entry("prompt", ObservationKind.USER_PROMPT),
            Map.entry("user-message", ObservationKind.USER_PROMPT),
            Map.entry("usermessage", ObservationKind.USER_PROMPT),

            // --- pre tool use ---
            Map.entry("pretooluse", ObservationKind.PRE_TOOL_USE),
            Map.entry("before-tool-use", ObservationKind.PRE_TOOL_USE),
            Map.entry("tool-call-start", ObservationKind.PRE_TOOL_USE),
            Map.entry("toolcallstart", ObservationKind.PRE_TOOL_USE),

            // --- post tool use --- (model the array tool_response shape; prior-art Bug A)
            Map.entry("posttooluse", ObservationKind.POST_TOOL_USE),
            Map.entry("after-tool-use", ObservationKind.POST_TOOL_USE),
            Map.entry("tool-call-end", ObservationKind.POST_TOOL_USE),
            Map.entry("toolcallend", ObservationKind.POST_TOOL_USE),
            Map.entry("tool-result", ObservationKind.POST_TOOL_USE),
            Map.entry("toolresult", ObservationKind.POST_TOOL_USE),

            // --- pre compact ---
            Map.entry("precompact", ObservationKind.PRE_COMPACT),
            Map.entry("compact", ObservationKind.PRE_COMPACT),
            Map.entry("before-compact", ObservationKind.PRE_COMPACT),

            // --- notification ---
            Map.entry("notify", ObservationKind.NOTIFICATION),

            // --- stop ---
            Map.entry("stophook", ObservationKind.STOP),
            Map.entry("subagentstop", ObservationKind.STOP),
            Map.entry("subagent-stop", ObservationKind.STOP),
            Map.entry("turn-end", ObservationKind.STOP),
            Map.entry("turnend", ObservationKind.STOP),

            // --- session end ---
            Map.entry("sessionend", ObservationKind.SESSION_END),
            Map.entry("session-stopped", ObservationKind.SESSION_END),
            Map.entry("shutdown", ObservationKind.SESSION_END),
            Map.entry("exit", ObservationKind.SESSION_END));

    /**
     * Resolves an agent-native event name to its canonical {@link ObservationKind}.
     *
     * <p>Matching is forgiving — case, and {@code _} / {@code -} / whitespace separators are all
     * equivalent — because clients are inconsistent and we would rather over-match a known event
     * than drop a real one. Unrecognized or blank input resolves to {@link ObservationKind#OTHER}
     * (never throws), which the caller may pair with an {@code extension} namespace.
     *
     * @param event the raw agent-native event name (e.g. {@code "PostToolUse"},
     *     {@code "user-prompt-submit"}); {@code null}/blank → {@link ObservationKind#OTHER}.
     * @return the canonical kind this event maps to.
     */
    public static ObservationKind parse(String event) {
        if (event == null || event.isBlank()) {
            return ObservationKind.OTHER;
        }
        ObservationKind alias = ALIASES.get(normalizeKey(event));
        if (alias != null) {
            return alias;
        }
        // Fall back to the canonical parser so a client already sending the kebab token (or a future
        // kind this build does not alias) still resolves; that parser yields OTHER for the unknown.
        return ObservationKind.fromWire(event);
    }

    /**
     * Reports whether {@code event} is a spelling this build explicitly recognizes — i.e. it appears
     * in the alias table or is a canonical kind other than {@link ObservationKind#OTHER}. Useful for
     * deciding whether an incoming {@code OTHER} should be flagged as a genuine extension event
     * versus a typo. (A canonical {@code "other"} token counts as recognized.)
     *
     * @param event the raw agent-native event name.
     * @return {@code true} if the spelling resolves to a known kind or is the literal {@code other}.
     */
    public static boolean isRecognized(String event) {
        if (event == null || event.isBlank()) {
            return false;
        }
        String key = normalizeKey(event);
        if (ALIASES.containsKey(key)) {
            return true;
        }
        // Canonical kebab tokens (including the literal "other") are recognized via fromWire; an
        // unknown token is the only case fromWire coerces to OTHER, so distinguish that here.
        return ObservationKind.fromWire(event) != ObservationKind.OTHER || key.equals("other");
    }

    /**
     * Normal form for an alias key / lookup token: trimmed, lower-cased (ASCII), with {@code _} and
     * runs of whitespace folded to {@code -}. So {@code "Post_Tool_Use"}, {@code "post tool use"}
     * and {@code "POST-TOOL-USE"} all collapse to {@code "post-tool-use"}.
     */
    private static String normalizeKey(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
    }

    /**
     * @return an immutable snapshot of the alias table (normalized spelling → canonical kind),
     *     grouped for documentation/tests. The canonical kebab tokens handled by the
     *     {@link ObservationKind#fromWire(String)} fallback are not included.
     */
    public static Map<String, ObservationKind> aliases() {
        return ALIASES.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** @return the canonical kinds that have at least one alias entry (for coverage assertions). */
    static java.util.Set<ObservationKind> aliasedKinds() {
        return Arrays.stream(ObservationKind.values())
                .filter(k -> ALIASES.containsValue(k))
                .collect(Collectors.toUnmodifiableSet());
    }
}
