/**
 * Executable eval gates (issue #31): an optional, project-supplied external validator that runs over a
 * high-stakes self-improvement proposal (e.g. {@code _rules/}, {@code procedures/}) as defense in depth
 * on top of LLM validation, before the auto-improve loop (#30) stages/approves it.
 *
 * <p>{@link com.agentmemory.eval.EvalGate} runs the command under a strict {@code JSON-in/JSON-out}
 * contract with a hard timeout, a scrubbed environment, captured output and fail-closed semantics; it is
 * off by default and never invoked on a hook path. The contract types ({@link com.agentmemory.eval.EvalProposal},
 * {@link com.agentmemory.eval.EvalVerdict}) are intentionally minimal and self-contained so the
 * auto-improve loop maps its own {@code pending_writes} record onto them at the call site — the two
 * features share no type or table.
 */
package com.agentmemory.eval;
