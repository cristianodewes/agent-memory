package com.agentmemory.llm;

/**
 * Decorates the required chat {@link LlmProvider} to feed call outcomes into the shared
 * {@link LlmRateLimitGate} (issue #148). Every consumer that injects the primary provider — the
 * consolidation, handoff, lint and curator flows — is thereby instrumented at one seam, with no
 * change at any call site: a {@code 429} raises the gate's pause, any success clears it.
 *
 * <p>Pure pass-through otherwise: exceptions are rethrown untouched, so fail-fast semantics
 * (startup probe, interactive endpoints) are unchanged.
 */
final class RateLimitTrackingLlmProvider implements LlmProvider {

    private final LlmProvider delegate;
    private final LlmRateLimitGate gate;

    RateLimitTrackingLlmProvider(LlmProvider delegate, LlmRateLimitGate gate) {
        this.delegate = delegate;
        this.gate = gate;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        try {
            ChatResponse response = delegate.chat(request);
            gate.recordSuccess();
            return response;
        } catch (LlmException e) {
            gate.recordFailure(e);
            throw e;
        }
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public String model() {
        return delegate.model();
    }

    @Override
    public void probe() {
        try {
            delegate.probe();
            gate.recordSuccess();
        } catch (LlmException e) {
            gate.recordFailure(e);
            throw e;
        }
    }
}
