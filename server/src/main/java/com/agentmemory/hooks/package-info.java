/**
 * Hook payload schemas, the sanitizer (typed privacy boundary), and the /hook
 * ingress. (See docs/ARCHITECTURE.md §5.4, §6.)
 *
 * <h2>Issue #7 (this milestone)</h2>
 * {@link com.agentmemory.hooks.HookPayload} is the wire envelope a Go client {@code POST}s to
 * {@code /hook} for one captured lifecycle event (identity tuple, session id, canonical kind, raw
 * source event, title/body, tool name/input/response, timestamp, {@code extension} namespace). It
 * builds on the shared {@code core} vocabulary (issue #3) rather than duplicating it — the canonical
 * {@link com.agentmemory.core.ObservationKind} lives in {@code core}; only the capture-pipeline
 * concerns live here.
 *
 * <p>{@link com.agentmemory.hooks.HookEvent} is the client-alias normalizer: it maps each agent's
 * native event spelling ({@code "PostToolUse"}, {@code "user-prompt-submit"}, {@code "post_tool_use"},
 * …) onto one canonical kind so no real hook is silently dropped, falling back to
 * {@link com.agentmemory.core.ObservationKind#OTHER} (never an exception) for unknown events. The
 * alias table is the extension seam — adding an agent's spelling is a one-line change that cannot
 * break parsing of existing or unknown events.
 *
 * <p>The {@code toolResponse} field is modeled as raw JSON ({@code JsonNode}) so an
 * <em>array</em>-shaped tool response (the documented prior-art "Bug A") survives ingest intact.
 *
 * <p>The cross-language golden fixtures under {@code docs/contracts/fixtures/hook_*.json} are
 * round-tripped by both this package and the Go mirror {@code client/internal/hook}.
 *
 * <h2>Still scaffolding</h2>
 * The sanitizer (typed privacy boundary, #9) and the {@code /hook} ingress controller (#8) arrive
 * with their own issues.
 */
package com.agentmemory.hooks;
