package com.agentmemory.consolidate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads the versioned consolidation + explore prompt templates from the classpath (issue #19; same
 * convention as {@link SynthesisPrompts}). The prompt text lives under
 * {@code src/main/resources/prompts/} so it is reviewed in the repo and changed deliberately rather
 * than hard-coded inline. Templates are read once at construction and held immutably.
 */
public final class ConsolidationPrompts {

    private static final String CONSOLIDATION_SYSTEM = "prompts/consolidation.system.md";
    private static final String EXPLORE_SYSTEM = "prompts/explore.system.md";

    private final String consolidationSystem;
    private final String exploreSystem;

    public ConsolidationPrompts() {
        this.consolidationSystem = load(CONSOLIDATION_SYSTEM);
        this.exploreSystem = load(EXPLORE_SYSTEM);
    }

    /** The system prompt for the multi-page consolidation call (structured-JSON output). */
    public String consolidationSystem() {
        return consolidationSystem;
    }

    /** The system prompt for the {@code memory_explore} prose digest (free-text output). */
    public String exploreSystem() {
        return exploreSystem;
    }

    private static String load(String resource) {
        try (InputStream in = ConsolidationPrompts.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing prompt resource on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read prompt resource: " + resource, e);
        }
    }
}
