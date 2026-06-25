/**
 * LLM-generated typed session handoffs (issue #22; ARCHITECTURE §3.4, §4.2 {@code handoffs};
 * DD-005). The flagship LLM feature: when a session ends, the model reads the session's captured
 * observations and writes a typed "where you left off" record — a {@code summary} plus
 * {@code openQuestions} and {@code nextSteps} — which the next agent on the project consumes once at
 * start.
 *
 * <h2>Pieces</h2>
 * <ul>
 *   <li>{@link com.agentmemory.handoff.HandoffService} — the orchestration: read the session's
 *       observations, ask the required {@link com.agentmemory.llm.LlmProvider} for a structured-JSON
 *       handoff (invariant #7, a {@link com.agentmemory.llm.JsonSchema} on the request; no rule-based
 *       fallback, invariant #13), parse it, and open it via the repository. Also {@code accept}
 *       (single-use consume), {@code cancel} (expire), and {@code peekOpen}.</li>
 *   <li>{@link com.agentmemory.handoff.SessionEndHandoffTrigger} — a {@code Consumer<Observation>}
 *       attached as the ingest pipeline's post-write listener; on a {@code session-end} observation it
 *       dispatches {@code begin} to its own dedicated single-thread daemon executor (issue #78) so the
 *       blocking LLM call runs off the ingest worker — off the HTTP hot path, outside the write
 *       transaction, and without stalling the ingest drain (invariant #5). A generation failure is
 *       swallowed: capture is already durable and a client can still open a handoff explicitly.</li>
 *   <li>{@link com.agentmemory.handoff.HandoffConfiguration} — wires the service and attaches the
 *       trigger; gated on a {@code DataSource} (like the other store-coupled modules) and ordered
 *       after {@code StoreConfiguration} so the {@link com.agentmemory.store.HandoffRepository} exists.
 *       Registered in {@code META-INF/spring/.../AutoConfiguration.imports}.</li>
 *   <li>{@link com.agentmemory.handoff.HandoffException} — a malformed LLM reply surfaced as a clear
 *       failure rather than a persisted half-handoff.</li>
 * </ul>
 *
 * <h2>Surfaces</h2>
 * Persistence is {@link com.agentmemory.store.JdbcHandoffRepository} over the {@code handoffs} table:
 * a transaction-scoped advisory lock serializes per-project writes (single-writer, invariant #2), the
 * insert + index update share the write transaction (invariant #3), and the partial unique index
 * {@code handoffs_one_open_per_project} enforces at most one open handoff (the prior open one is
 * expired before a new one is inserted, so the index never transiently double-counts).
 * {@link com.agentmemory.web.HandoffController} exposes {@code POST /handoff},
 * {@code POST /handoff/accept}, {@code POST /handoff/cancel}; {@link com.agentmemory.mcp.HandoffTools}
 * exposes the {@code memory_handoff_begin}/{@code memory_handoff_accept}/{@code memory_handoff_cancel}
 * MCP tools. {@code accept} is the cross-agent injection point — any agent in the project consumes the
 * one a prior agent left.
 */
package com.agentmemory.handoff;
