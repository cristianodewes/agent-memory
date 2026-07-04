package com.agentmemory.autoimprove;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads the versioned session-review prompt templates from the classpath (issue #150), mirroring the
 * consolidation {@code SynthesisPrompts}: prompt text lives under
 * {@code src/main/resources/prompts/} so it is reviewed in the repo and changed deliberately. Both
 * prompts carry the #136 prompt-injection hardening (session material is untrusted data). Templates
 * are read once at construction and held immutably.
 */
public final class ReviewPrompts {

    private static final String REVIEW_SYSTEM = "prompts/autoimprove-review.system.md";
    private static final String VERIFY_SYSTEM = "prompts/autoimprove-verify.system.md";

    private final String reviewSystem;
    private final String verifySystem;

    public ReviewPrompts() {
        this.reviewSystem = load(REVIEW_SYSTEM);
        this.verifySystem = load(VERIFY_SYSTEM);
    }

    /** The system prompt for phase A — durable-knowledge extraction (structured-JSON output). */
    public String reviewSystem() {
        return reviewSystem;
    }

    /** The system prompt for phase B — adversarial verification of one candidate (structured-JSON). */
    public String verifySystem() {
        return verifySystem;
    }

    private static String load(String resource) {
        try (InputStream in = ReviewPrompts.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing prompt resource on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read prompt resource: " + resource, e);
        }
    }
}
