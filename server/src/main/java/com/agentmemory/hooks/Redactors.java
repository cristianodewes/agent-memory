package com.agentmemory.hooks;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The built-in {@link Redactor} set (issue #9 acceptance criteria: keys/tokens, emails, home-dir
 * paths) plus the factory for a {@link #custom(String, String) configurable} regex redactor.
 *
 * <p>Each placeholder is the bracketed marker {@code [REDACTED:<label>]}. The markers are chosen so
 * that <em>no</em> built-in pattern matches its own (or another built-in's) output: the email/secret
 * patterns require an {@code @} or a {@code key=}/{@code :} shape, and the home-path pattern requires
 * a real path prefix — none of which appear in {@code [REDACTED:...]}. That makes the whole pipeline
 * idempotent, so sanitizing already-sanitized text is a no-op (a property the tests assert).
 *
 * <p>All patterns are pre-compiled constants (a redactor is pure and hot on the capture path) and the
 * matching is conservative: we would rather over-redact a borderline token than leak a real secret.
 */
final class Redactors {

    private Redactors() {}

    /** Marker emitted in place of redacted content. {@code label} is a short stable class token. */
    static String marker(String label) {
        return "[REDACTED:" + label + "]";
    }

    // --- emails ------------------------------------------------------------------------------------

    /**
     * RFC-pragmatic email shape (local part, {@code @}, dotted domain). Not the full RFC 5322 grammar
     * — deliberately simple and greedy enough to catch real addresses in captured prose/logs.
     */
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    /** Redacts email addresses → {@code [REDACTED:email]}. */
    static final Redactor EMAIL_REDACTOR = labeled("email",
            text -> EMAIL.matcher(text).replaceAll(marker("email")));

    // --- secrets / keys / tokens -------------------------------------------------------------------

    /**
     * Well-known high-entropy provider credential formats, matched by their fixed prefixes so we do
     * not need an entropy heuristic: OpenAI/Anthropic {@code sk-…} / {@code sk-ant-…}, GitHub
     * {@code gh[pousr]_…}, Google {@code AIza…}, Slack {@code xox[baprs]-…}, and AWS access key ids
     * {@code AKIA…}/{@code ASIA…}. Bounded quantifiers keep matching linear.
     */
    private static final Pattern KNOWN_KEY = Pattern.compile(
            "\\b("
                    + "sk-(?:ant-)?[A-Za-z0-9_\\-]{16,}"      // OpenAI / Anthropic
                    + "|gh[pousr]_[A-Za-z0-9]{20,}"           // GitHub tokens
                    + "|AIza[A-Za-z0-9_\\-]{20,}"             // Google API key
                    + "|xox[baprs]-[A-Za-z0-9\\-]{10,}"       // Slack token
                    + "|(?:AKIA|ASIA)[A-Z0-9]{16}"            // AWS access key id
                    + ")\\b");

    /** {@code Authorization: Bearer <token>} / {@code Bearer <token>} (case-insensitive on "bearer"). */
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bbearer\\s+[A-Za-z0-9._\\-]+");

    /**
     * Generic {@code <sensitive-key> = <value>} / {@code : <value>} assignments. Captures the key name
     * (group 1) and the assignment operator (group 2) so the redaction keeps the label — e.g.
     * {@code api_key=…} → {@code api_key=[REDACTED:secret]} — which is far more useful in an audit log
     * than blanking the whole line. The value is anything up to the next whitespace, quote or comma.
     */
    private static final Pattern KEYED_SECRET = Pattern.compile(
            "(?i)\\b(api[_\\-]?key|secret|token|password|passwd|pwd|access[_\\-]?key|auth[_\\-]?token|"
                    + "client[_\\-]?secret|private[_\\-]?key)(\\s*[=:]\\s*)\"?[^\\s\"',]+\"?");

    /**
     * Redacts secrets/keys/tokens → {@code [REDACTED:secret]} (or {@code [REDACTED:token]} for bearer).
     * Order inside the rule: known fixed-format keys first, then bearer headers, then generic keyed
     * assignments, so a {@code token=sk-…} value is masked once and cleanly.
     */
    static final Redactor SECRET_REDACTOR = labeled("secret", text -> {
        String out = KNOWN_KEY.matcher(text).replaceAll(marker("secret"));
        out = BEARER.matcher(out).replaceAll("Bearer " + marker("token"));
        out = KEYED_SECRET.matcher(out).replaceAll("$1$2" + marker("secret"));
        return out;
    });

    // --- home-directory paths ----------------------------------------------------------------------

    /**
     * Absolute user-home prefixes on the three mainstream layouts — Unix {@code /home/<user>}, macOS
     * {@code /Users/<user>}, Windows {@code C:\Users\<user>} (any drive letter, {@code \} or {@code /}
     * separators). Only the <em>identifying prefix</em> is replaced; the path tail is preserved so the
     * location stays meaningful (e.g. {@code /home/alice/proj/x} → {@code [REDACTED:home]/proj/x}). The
     * username segment is {@code [^/\\]+} so it stops at the first separator.
     */
    private static final Pattern HOME_PATH = Pattern.compile(
            "(?:/home/[^/\\\\]+"
                    + "|/Users/[^/\\\\]+"
                    + "|[A-Za-z]:\\\\Users\\\\[^/\\\\]+"
                    + "|[A-Za-z]:/Users/[^/\\\\]+)");

    /** Redacts absolute home-dir prefixes → {@code [REDACTED:home]}, keeping the path tail. */
    static final Redactor HOME_PATH_REDACTOR = labeled("home",
            text -> HOME_PATH.matcher(text).replaceAll(marker("home")));

    // --- custom (configured) -----------------------------------------------------------------------

    /**
     * Builds a redactor from a user-supplied regex. Used for {@code agent-memory.sanitization.custom-
     * patterns}. The whole match is replaced with {@code [REDACTED:<label>]}.
     *
     * @param regex a valid Java regex; every match is redacted.
     * @param label the marker label (defaults to {@code custom} when blank).
     * @return a redactor applying {@code regex}.
     * @throws java.util.regex.PatternSyntaxException if {@code regex} does not compile (surfaced at
     *     config-resolution time so a bad pattern fails fast, not silently).
     */
    static Redactor custom(String regex, String label) {
        Pattern compiled = Pattern.compile(regex);
        String mark = marker(label == null || label.isBlank() ? "custom" : label);
        String lbl = label == null || label.isBlank() ? "custom" : label;
        return labeled(lbl, text -> compiled.matcher(text).replaceAll(java.util.regex.Matcher.quoteReplacement(mark)));
    }

    /** The built-in redactors in their canonical application order. */
    static List<Redactor> builtins() {
        // Secrets first (a keyed `password=alice@x.com` should read [REDACTED:secret], not leak the
        // value as an email match), then emails, then home paths.
        return List.of(SECRET_REDACTOR, EMAIL_REDACTOR, HOME_PATH_REDACTOR);
    }

    /** Wraps a lambda with a stable {@link Redactor#label()} (the functional interface alone can't). */
    private static Redactor labeled(String label, Redactor delegate) {
        return new Redactor() {
            @Override
            public String redact(String text) {
                return delegate.redact(text);
            }

            @Override
            public String label() {
                return label;
            }
        };
    }
}
