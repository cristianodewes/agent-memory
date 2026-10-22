package com.agentmemory.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prefers one {@link LlmProvider} and, on any provider failure, repeats the same request on a fallback
 * (issue #156). The synthesis route uses it so the session pages and handoffs keep being written by the
 * primary model when the synthesis model is down, out of quota or misconfigured — quality degrades for
 * that call, the memory does not stop.
 *
 * <p>Deliberately <em>not</em> a {@link RateLimitTrackingLlmProvider}: the preferred provider's failures
 * never reach the primary's {@link LlmRateLimitGate}, so a 429 from the synthesis model does not pause
 * the consolidation and handoff queues that the primary can still serve. The fallback is the decorated
 * primary itself, so its outcome — and, when both fail, its exception — feeds the gate exactly as today.
 */
final class FallbackLlmProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(FallbackLlmProvider.class);

    private final LlmProvider preferred;
    private final LlmProvider fallback;
    private final String axis;

    FallbackLlmProvider(LlmProvider preferred, LlmProvider fallback, String axis) {
        this.preferred = preferred;
        this.fallback = fallback;
        this.axis = axis;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        try {
            return preferred.chat(request);
        } catch (LlmException e) {
            log.warn("{} LLM provider '{}' (model {}) failed: {}. Repeating the call on the primary '{}' "
                    + "(model {}).", axis, preferred.id(), preferred.model(), e.getMessage(),
                    fallback.id(), fallback.model());
            return fallback.chat(request);
        }
    }

    @Override
    public String id() {
        return preferred.id();
    }

    @Override
    public String model() {
        return preferred.model();
    }

    @Override
    public void probe() {
        preferred.probe();
    }
}
