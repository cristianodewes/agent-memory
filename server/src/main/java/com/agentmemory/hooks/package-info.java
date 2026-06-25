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
 * <h2>Issue #9 — sanitization typed boundary</h2>
 * {@link com.agentmemory.hooks.Sanitizer} is the privacy strip (DD-010, invariant #6): the only way
 * to turn a raw {@link com.agentmemory.hooks.NewObservation} into a storable value is
 * {@link com.agentmemory.hooks.Sanitizer#sanitize(com.agentmemory.hooks.NewObservation)}, which
 * returns a {@link com.agentmemory.hooks.Sanitized}{@code <NewObservation>}. Because
 * {@link com.agentmemory.hooks.Sanitized} has no public constructor and the sanitizer is its sole
 * producer, untrusted captured text cannot reach the store ({@code com.agentmemory.store}, whose
 * {@code ObservationWriter} accepts only the sanitized type) without being scrubbed — a compile-time
 * guarantee, backed by an architecture test. The redaction pipeline
 * ({@link com.agentmemory.hooks.Redactor} / {@link com.agentmemory.hooks.Redactors}) covers
 * secrets/keys/tokens, emails and home-dir paths, plus configurable custom regexes, and enforces a
 * deterministic size cap.
 *
 * <h2>Issue #8 — /hook ingestion + backpressure</h2>
 * {@link com.agentmemory.hooks.IngestService} is the capture pipeline: it assembles a
 * {@link com.agentmemory.hooks.NewObservation} from a {@link com.agentmemory.hooks.HookPayload}
 * (flattening the structured fields to one text {@code payload} via
 * {@link com.agentmemory.hooks.HookPayloadText}, keeping an array {@code toolResponse} verbatim —
 * Bug A), runs it through the {@link com.agentmemory.hooks.Sanitizer} on the synchronous path, then
 * hands the {@link com.agentmemory.hooks.Sanitized} value to a <strong>bounded</strong> queue drained
 * by a single worker thread to the store's writer. A saturated queue yields
 * {@link com.agentmemory.hooks.IngestStatus#THROTTLED} (HTTP 429, invariant #5); the handler never
 * blocks the agent. The HTTP surface is {@code com.agentmemory.web.HookController}
 * ({@code POST /hook}, {@code /hook/batch} with per-item partial-accept). Idempotent replay is the
 * writer's job (dedupe on {@code clientEventId}); this layer just routes.
 */
package com.agentmemory.hooks;
