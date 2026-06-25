/**
 * LLM-assisted recall (issue #21): query expansion, candidate re-rank, and curated prompt-time
 * injection layered over the base hybrid recall ({@code com.agentmemory.recall}). Every LLM step is a
 * structured-JSON call (invariant #7) behind the required {@link com.agentmemory.llm.LlmProvider}
 * (DD-005, invariant #13) and is strictly best-effort — on failure or over budget the pipeline
 * degrades to the raw Reciprocal Rank Fusion result, so recall is never worse than the baseline.
 *
 * <p>Shape:
 * <ul>
 *   <li>Pipeline — {@link com.agentmemory.llmrecall.LlmRecallService} decorates the base
 *       {@link com.agentmemory.recall.RecallService}: optional {@link com.agentmemory.llmrecall.QueryExpander}
 *       → base retrieval (over-fetched pool) → optional {@link com.agentmemory.llmrecall.CandidateReranker}
 *       → trim → {@link com.agentmemory.llmrecall.AccessReinforcer}.</li>
 *   <li>Injection — {@link com.agentmemory.llmrecall.RecallInjection} curates a concise,
 *       relevance-gated, bounded markdown block for a {@code UserPromptSubmit} hook (exposed by
 *       {@code com.agentmemory.web.RecallInjectionController} at {@code POST /recall/inject}).</li>
 *   <li>Cost controls — {@link com.agentmemory.llmrecall.LlmRecallProperties}
 *       ({@code agent-memory.recall.llm}) caps the candidate count K and the per-query LLM-call budget,
 *       with a non-LLM fast path when disabled or over budget.</li>
 *   <li>Reinforcement (#24 seam) — {@link com.agentmemory.llmrecall.AccessReinforcer} fires on the
 *       returned hits; {@link com.agentmemory.llmrecall.JdbcAccessReinforcer} additively bumps the V3
 *       {@code access_count}/{@code last_accessed_at} columns, overridable by #24's own reinforcer.</li>
 *   <li>Prompts/schemas — {@link com.agentmemory.llmrecall.RecallPrompts} loads the system prompts and
 *       the structured-output schemas; wiring in {@link com.agentmemory.llmrecall.LlmRecallConfiguration}.</li>
 * </ul>
 */
package com.agentmemory.llmrecall;
