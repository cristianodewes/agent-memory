package com.agentmemory.consolidate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads the versioned synthesis prompt templates from the classpath (issue #18: "keep prompts /
 * versioned templates in-repo for review"). The prompt text lives under
 * {@code src/main/resources/prompts/} so it is reviewed in the repo and changed deliberately, rather
 * than being hard-coded inline. Templates are read once at construction and held immutably.
 */
public final class SynthesisPrompts {

    private static final String SYNTHESIS_SYSTEM = "prompts/session-synthesis.system.md";
    private static final String CHUNK_SYSTEM = "prompts/session-chunk-summary.system.md";

    private final String synthesisSystem;
    private final String chunkSummarySystem;

    public SynthesisPrompts() {
        this.synthesisSystem = load(SYNTHESIS_SYSTEM);
        this.chunkSummarySystem = load(CHUNK_SYSTEM);
    }

    /** The system prompt for the final session synthesis (structured-JSON output). */
    public String synthesisSystem() {
        return synthesisSystem;
    }

    /** The system prompt for condensing one chunk of a long session (the map step). */
    public String chunkSummarySystem() {
        return chunkSummarySystem;
    }

    private static String load(String resource) {
        try (InputStream in = SynthesisPrompts.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing prompt resource on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read prompt resource: " + resource, e);
        }
    }
}
