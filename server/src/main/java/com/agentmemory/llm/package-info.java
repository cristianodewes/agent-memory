/**
 * The LLM provider auth boundary plus the {@link com.agentmemory.llm.LlmProvider} /
 * {@link com.agentmemory.llm.Embedder} interfaces. The LLM is a REQUIRED dependency
 * (design-decision DD-005, invariant #13); embeddings are a separate, default-on axis that may
 * degrade gracefully. (See docs/ARCHITECTURE.md §2.4, §6, §7.)
 *
 * <p>Shape (issue #6):
 * <ul>
 *   <li>Domain contracts — {@link com.agentmemory.llm.ChatMessage}, {@link com.agentmemory.llm.ChatRequest},
 *       {@link com.agentmemory.llm.ChatResponse}, {@link com.agentmemory.llm.JsonSchema} (structured-JSON
 *       output, invariant #7), {@link com.agentmemory.llm.EmbeddingResult}
 *       ({@code {provider, model, dim}} denormalized, invariant #8).</li>
 *   <li>Providers — {@link com.agentmemory.llm.AnthropicLlmProvider} (chat),
 *       {@link com.agentmemory.llm.VoyageEmbedder} (embeddings; Anthropic has no embeddings endpoint),
 *       {@link com.agentmemory.llm.OpenAiCompatLlmProvider} (stub for #40), and
 *       {@link com.agentmemory.llm.TestDoubleProvider} (deterministic, offline; both axes).</li>
 *   <li>Selection — {@link com.agentmemory.llm.ProviderFactory} resolves a provider from a typed
 *       {@link com.agentmemory.config.ProviderAuth} (auth before client construction, invariant #14);
 *       the matrix is data-driven so #40 adds providers without touching consumers.</li>
 *   <li>Startup — {@link com.agentmemory.llm.LlmHealthGate} probes the required chat provider
 *       (fail-fast) and the optional embedder (degraded-recall warning); wired by
 *       {@link com.agentmemory.llm.LlmModule}.</li>
 * </ul>
 *
 * <p>Out of scope here (per the issue): the consumers — consolidation (#18/#19), recall (#21),
 * handoff (#22), chat (#37) — depend on this layer but are not implemented in it.
 */
package com.agentmemory.llm;
