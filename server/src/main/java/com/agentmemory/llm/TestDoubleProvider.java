package com.agentmemory.llm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * A deterministic, network-free implementation of both {@link LlmProvider} and {@link Embedder} for
 * tests and offline development — the "deterministic test double" the issue requires so downstream
 * issues (consolidation, recall, handoff, chat) can exercise LLM flows without a real provider or
 * any hard-coded key.
 *
 * <p>It is selected by configuring the provider key {@code "test"} (see {@code ProviderFactory}), so
 * a developer can run the whole server with {@code agent-memory.llm.auth.provider=test} and no API
 * key. Behaviour is fully deterministic:
 * <ul>
 *   <li>{@link #chat} returns a canned reply. The default reply is derived from the request (and is
 *       schema-shaped JSON when the request asked for structured output), but a custom
 *       {@link #chatResponder} can script exact replies for a test.</li>
 *   <li>{@link #embed} hashes the input into a fixed-width unit vector, so identical text always
 *       yields the same embedding and the {@code dim} contract is honoured.</li>
 *   <li>{@link #failProbe} makes {@link #probe()} throw, which lets a test prove the startup health
 *       gate fails fast on an unreachable/rejecting <em>required</em> provider (invariant #13)
 *       without any network.</li>
 * </ul>
 *
 * <p>This is production code (not under {@code src/test}) on purpose: it is part of the provider
 * matrix and is referenced by both the factory and downstream test harnesses.
 */
public final class TestDoubleProvider implements LlmProvider, Embedder {

    /** The provider key that selects this double in config and in {@code ProviderFactory}. */
    public static final String PROVIDER_KEY = "test";

    /** Default deterministic dimensionality — small, fast, and distinct from real providers. */
    public static final int DEFAULT_DIMENSIONS = 8;

    private final String model;
    private final int dimensions;
    private final boolean failProbe;
    private final Function<ChatRequest, String> chatResponder;

    private final List<ChatRequest> chatCalls = new ArrayList<>();
    private final List<String> embedCalls = new ArrayList<>();

    private TestDoubleProvider(Builder b) {
        this.model = b.model;
        this.dimensions = b.dimensions;
        this.failProbe = b.failProbe;
        this.chatResponder = b.chatResponder;
    }

    /** A double with all defaults: model {@code "test-model"}, {@value #DEFAULT_DIMENSIONS} dims, healthy probe. */
    public static TestDoubleProvider create() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    // --- LlmProvider ---------------------------------------------------------------------------

    @Override
    public ChatResponse chat(ChatRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        chatCalls.add(request);
        String text = chatResponder.apply(request);
        // Estimate tokens deterministically so callers can assert on usage without a real tokenizer.
        int inTokens = request.messages().stream().mapToInt(m -> approxTokens(m.content())).sum();
        return new ChatResponse(text, PROVIDER_KEY, model, inTokens, approxTokens(text));
    }

    @Override
    public String id() {
        return PROVIDER_KEY;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public void probe() {
        if (failProbe) {
            throw LlmException.permanent(
                    "test-double probe configured to fail (simulating an unreachable provider)", null);
        }
    }

    // --- Embedder ------------------------------------------------------------------------------

    @Override
    public EmbeddingResult embed(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Embedder.embed requires non-blank text");
        }
        embedCalls.add(text);
        return new EmbeddingResult(deterministicVector(text), PROVIDER_KEY, model, dimensions);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    // --- test-introspection helpers ------------------------------------------------------------

    /** Immutable view of the chat requests this double has received, in order. */
    public List<ChatRequest> chatCalls() {
        return List.copyOf(chatCalls);
    }

    /** Immutable view of the embed inputs this double has received, in order. */
    public List<String> embedCalls() {
        return List.copyOf(embedCalls);
    }

    // --- deterministic internals ---------------------------------------------------------------

    /**
     * A stable unit vector seeded from the text's bytes — a 32-bit FNV-1a hash drives a tiny LCG so
     * every component is reproducible for the same input, and the result is L2-normalized so cosine
     * similarity behaves sensibly in tests.
     */
    private float[] deterministicVector(String text) {
        long state = fnv1a(text.getBytes(StandardCharsets.UTF_8));
        float[] v = new float[dimensions];
        double sumSq = 0.0;
        for (int i = 0; i < dimensions; i++) {
            // LCG step (Numerical Recipes constants) keeps the sequence deterministic and spread out.
            state = (state * 6364136223846793005L + 1442695040888963407L);
            float component = ((state >>> 33) / (float) (1L << 31)) - 1.0f; // in [-1, 1)
            v[i] = component;
            sumSq += (double) component * component;
        }
        float norm = (float) Math.sqrt(sumSq);
        if (norm > 0f) {
            for (int i = 0; i < dimensions; i++) {
                v[i] /= norm;
            }
        }
        return v;
    }

    private static long fnv1a(byte[] bytes) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : bytes) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static int approxTokens(String s) {
        // ~4 chars/token, floored at 1 for any non-empty content.
        return s.isEmpty() ? 0 : Math.max(1, s.length() / 4);
    }

    /**
     * The default canned reply: echoes the last message, wrapped as JSON when the request asked for
     * structured output so the reply still parses. Tests that need exact content supply their own
     * {@link Builder#chatResponder}.
     */
    private static String defaultReply(ChatRequest request) {
        String last = request.messages().get(request.messages().size() - 1).content();
        if (request.wantsStructuredOutput()) {
            return "{\"echo\":" + jsonString(last) + "}";
        }
        return "test-double reply to: " + last;
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Builder for a configured {@link TestDoubleProvider}. */
    public static final class Builder {
        private String model = "test-model";
        private int dimensions = DEFAULT_DIMENSIONS;
        private boolean failProbe = false;
        private Function<ChatRequest, String> chatResponder = TestDoubleProvider::defaultReply;

        public Builder model(String model) {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model must not be blank");
            }
            this.model = model;
            return this;
        }

        public Builder dimensions(int dimensions) {
            if (dimensions <= 0) {
                throw new IllegalArgumentException("dimensions must be positive");
            }
            this.dimensions = dimensions;
            return this;
        }

        /** Make {@link #probe()} throw, to simulate an unreachable / credential-rejecting provider. */
        public Builder failProbe(boolean failProbe) {
            this.failProbe = failProbe;
            return this;
        }

        /** Script exact chat replies as a function of the request. */
        public Builder chatResponder(Function<ChatRequest, String> chatResponder) {
            if (chatResponder == null) {
                throw new IllegalArgumentException("chatResponder must not be null");
            }
            this.chatResponder = chatResponder;
            return this;
        }

        public TestDoubleProvider build() {
            return new TestDoubleProvider(this);
        }
    }
}
