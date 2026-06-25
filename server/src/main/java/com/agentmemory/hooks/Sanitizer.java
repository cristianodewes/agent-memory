package com.agentmemory.hooks;

import com.agentmemory.config.AgentMemoryConfig;
import com.agentmemory.config.AgentMemoryProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The privacy <strong>typed boundary</strong> (DD-010 / invariant #6): the one and only way to turn
 * a raw {@link NewObservation} into something the store will accept. {@link #sanitize(NewObservation)}
 * scrubs the payload and returns a {@link Sanitized}{@code <NewObservation>}; because
 * {@link Sanitized}'s constructor is package-private and this class is its sole caller, no other code
 * — anywhere — can manufacture a sanitized value. "Was this text privacy-stripped?" is therefore a
 * compile-time guarantee carried by the type, not a convention.
 *
 * <h2>What it does to the payload</h2>
 * <ol>
 *   <li>Runs the redaction pipeline: the {@link Redactors#builtins() built-in} redactors
 *       (secrets/keys/tokens, then emails, then home-dir paths) followed by any configured
 *       {@link AgentMemoryProperties.Sanitization#customPatterns() custom patterns}, in order.</li>
 *   <li><strong>Then</strong> enforces the size cap: a payload longer than
 *       {@link AgentMemoryProperties.Sanitization#maxPayloadChars()} is truncated to the cap and a
 *       deterministic marker recording the omitted-character count is appended. Redaction runs
 *       before truncation on purpose — so a secret near the cap is scrubbed in full rather than
 *       half-cut and half-leaked.</li>
 * </ol>
 * The structural fields (ids, kind, identity, timestamps) are never touched; only the free-text
 * {@code payload} is rewritten, via {@link NewObservation#withPayload(String)}.
 *
 * <p>Stateless and pure after construction (the redactor list is built once). Published as a Spring
 * bean wired from {@link AgentMemoryConfig} (invariant #12 — no static singletons); also directly
 * constructible for unit tests without a Spring context.
 */
@Component
public final class Sanitizer {

    private final List<Redactor> redactors;
    private final int maxPayloadChars;

    /**
     * Spring entry point: build the sanitizer from the resolved configuration.
     *
     * @param config the single resolved server config; its {@code sanitization()} block tunes the
     *     size cap and custom patterns.
     */
    @Autowired
    public Sanitizer(AgentMemoryConfig config) {
        this(config.sanitization());
    }

    /**
     * Build a sanitizer directly from a sanitization config (test-friendly: no Spring context).
     * Built-in redactors are always installed; custom patterns are compiled here, so an invalid
     * regex fails fast at construction (startup) rather than on the first hook.
     *
     * @param sanitization the size cap + custom-pattern settings.
     */
    public Sanitizer(AgentMemoryProperties.Sanitization sanitization) {
        List<Redactor> all = new ArrayList<>(Redactors.builtins());
        for (AgentMemoryProperties.Sanitization.CustomPattern p : sanitization.customPatterns()) {
            all.add(Redactors.custom(p.regex(), p.label()));
        }
        this.redactors = List.copyOf(all);
        this.maxPayloadChars = sanitization.maxPayloadChars();
    }

    /**
     * Scrub {@code observation}'s payload (redact, then size-cap) and wrap the result as the storable
     * {@link Sanitized} type. This is the sole producer of {@code Sanitized<NewObservation>}.
     *
     * @param observation the raw, unsanitized create-request; never null.
     * @return the same observation with a scrubbed, size-capped payload, wrapped as sanitized.
     */
    public Sanitized<NewObservation> sanitize(NewObservation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("cannot sanitize a null observation");
        }
        String scrubbed = redact(observation.payload());
        scrubbed = truncate(scrubbed);
        return new Sanitized<>(observation.withPayload(scrubbed));
    }

    /**
     * Run the full redaction pipeline over an arbitrary string. Exposed (package-private) for the
     * redaction unit tests; the boundary guarantee is on {@link #sanitize}.
     *
     * @param text input text; never null.
     * @return the text with every redactor applied in order.
     */
    String redact(String text) {
        String out = text;
        for (Redactor r : redactors) {
            out = r.redact(out);
        }
        return out;
    }

    /**
     * Deterministically cap {@code text} at {@link #maxPayloadChars}. If it fits, it is returned
     * unchanged; otherwise it is cut to the cap (the marker counts toward the cap, so the result is
     * never longer than {@code maxPayloadChars}) and a marker recording how many characters were
     * dropped is appended. Same input ⇒ same output, always.
     *
     * @param text the (already-redacted) text.
     * @return {@code text} unchanged, or a truncated copy ending in {@code …[TRUNCATED: N chars omitted]}.
     */
    private String truncate(String text) {
        int len = text.length();
        if (len <= maxPayloadChars) {
            return text;
        }
        // Choose `keep` so that keep + len(marker(len - keep)) <= cap, with marker reporting exactly
        // the dropped count. marker length is non-decreasing in the omitted count (more digits), so we
        // iterate to a fixpoint: start assuming the whole overflow is dropped (longest plausible
        // marker) and grow `keep` until it is stable. Converges in ≤2 steps (digit-width only grows).
        int keep = maxPayloadChars - truncationMarker(len).length();
        if (keep <= 0) {
            // Cap too small to fit any marker: bare head cut to exactly the cap (still deterministic).
            return text.substring(0, maxPayloadChars);
        }
        while (true) {
            int omitted = len - keep;
            int next = maxPayloadChars - truncationMarker(omitted).length();
            if (next == keep) {
                return text.substring(0, keep) + truncationMarker(omitted);
            }
            keep = next;
            if (keep <= 0) {
                return text.substring(0, maxPayloadChars);
            }
        }
    }

    /** The deterministic truncation marker; {@code n} is the number of characters omitted. */
    private static String truncationMarker(int n) {
        return "…[TRUNCATED: " + n + " chars omitted]";
    }
}
