package com.agentmemory.hooks;

/**
 * One privacy redaction rule: given some text, return it with a single class of sensitive content
 * replaced by a stable placeholder. The {@link Sanitizer} runs an ordered list of these over an
 * observation's payload.
 *
 * <p>Redactors must be:
 * <ul>
 *   <li><strong>Pure and total</strong> — no IO, no state, never throw on any input (including
 *       pathological/huge strings); a redactor that fails open would punch a hole in the boundary.</li>
 *   <li><strong>Idempotent</strong> — running the result through the same redactor again is a no-op,
 *       so the placeholder text itself is never re-redacted. Built-ins achieve this by emitting a
 *       bracketed marker (e.g. {@code [REDACTED:email]}) that their own pattern does not match.</li>
 * </ul>
 *
 * <p>Order can matter (a broad rule may mask text a narrower one would have caught), so the
 * sanitizer applies them in a fixed, documented sequence — see {@link Sanitizer}.
 */
@FunctionalInterface
public interface Redactor {

    /**
     * @param text the (possibly already partially-redacted) input; never null.
     * @return the text with this redactor's sensitive class replaced by placeholders; never null.
     */
    String redact(String text);

    /**
     * @return a short stable label for this redactor (e.g. {@code "email"}), used in the placeholder
     *     marker and for config/diagnostics. Defaults to the simple class name lower-cased.
     */
    default String label() {
        return getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }
}
