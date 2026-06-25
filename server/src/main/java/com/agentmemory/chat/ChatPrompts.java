package com.agentmemory.chat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Loads the versioned "chat with your memory" system prompt from the classpath (issue #37: "keep
 * prompts versioned"). The prompt text lives under {@code src/main/resources/prompts/} so it is
 * reviewed in the repo and changed deliberately, rather than hard-coded inline — the same pattern as
 * {@link com.agentmemory.consolidate.SynthesisPrompts}. Read once at construction and held immutably.
 */
public final class ChatPrompts {

    /** Versioned prompt id, surfaced for provenance/auditability (mirrors handoff/recall). */
    public static final String PROMPT_VERSION = "chat/v1";

    private static final String CHAT_SYSTEM = "prompts/chat.system.md";

    private final String chatSystem;

    public ChatPrompts() {
        this.chatSystem = load(CHAT_SYSTEM);
    }

    /** The system prompt instructing the model to answer strictly from the provided memory, citing [n]. */
    public String chatSystem() {
        return chatSystem;
    }

    private static String load(String resource) {
        try (InputStream in = ChatPrompts.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing prompt resource on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read prompt resource: " + resource, e);
        }
    }
}
