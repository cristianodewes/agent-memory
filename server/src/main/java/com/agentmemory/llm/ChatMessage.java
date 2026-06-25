package com.agentmemory.llm;

import java.util.List;

/**
 * One turn in a chat exchange with an {@link LlmProvider}: a {@link Role} and its text content.
 *
 * <p>Deliberately provider-neutral — providers map this onto their own wire shape (Anthropic's
 * {@code system} field plus {@code messages[]}, OpenAI's {@code messages[]}, …). Keeping the
 * domain type free of provider quirks is what lets issue #40 add providers without touching the
 * consumers in {@code consolidate}/{@code recall}/{@code handoff}.
 *
 * @param role    who is speaking.
 * @param content the message text.
 */
public record ChatMessage(Role role, String content) {

    public ChatMessage {
        if (role == null) {
            throw new IllegalArgumentException("ChatMessage.role must not be null");
        }
        if (content == null) {
            throw new IllegalArgumentException("ChatMessage.content must not be null");
        }
    }

    /** Speaker of a {@link ChatMessage}. */
    public enum Role {
        /** High-level instructions / persona. Providers may hoist this out of the message list. */
        SYSTEM,
        /** Input from the user (or, in this product, the calling subsystem). */
        USER,
        /** A prior model response, replayed for multi-turn context. */
        ASSISTANT
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content);
    }

    /** Convenience: a single user turn. */
    public static List<ChatMessage> ofUser(String content) {
        return List.of(user(content));
    }
}
