/**
 * Hybrid search: full-text + link-graph + vector fused with RRF, then LLM
 * re-ranking. (See docs/ARCHITECTURE.md §3.3, §5.1, §6.)
 *
 * <h2>Issue #15 — hybrid RRF retrieval (this milestone)</h2>
 * {@link com.agentmemory.recall.RecallService} ({@link com.agentmemory.recall.HybridRecallService})
 * runs two arms over the {@code pages}/{@code links} tables and fuses them:
 * <ul>
 *   <li><strong>Full-text</strong> over {@code pages_fts} ({@code ts_rank}, {@code is_latest} only)
 *       — {@link com.agentmemory.recall.RecallRepository#ftsPages};</li>
 *   <li><strong>Link-graph neighborhood</strong> — expands the FTS hits one hop over {@code links}
 *       (both directions), ranked by edge-count to the seed set
 *       ({@link com.agentmemory.recall.RecallRepository#graphNeighbors});</li>
 *   <li><strong>RRF fusion</strong> ({@link com.agentmemory.recall.RrfFusion}) merges the two ranked
 *       arms into one ordered list with a stable, deterministic tie-break.</li>
 * </ul>
 * When no compiled page matches, a <strong>bounded raw-observation fallback</strong> over
 * {@code observations_fts} is returned, clearly flagged
 * ({@link com.agentmemory.recall.RecallResult#rawFallback()}). Snippets are HTML-{@code <mark>}-ed via
 * {@code ts_headline}. Scope is a single {@code (workspace, project)}
 * ({@link com.agentmemory.recall.Scope}); multi-scope/global fan-out is #29.
 *
 * <p>The {@link com.agentmemory.recall.Fusion} seam is pluggable: #16 adds a vector
 * {@link com.agentmemory.recall.RankedList} to the same fuse call and #21 re-ranks the fused output,
 * neither rewriting the arms or the service.
 *
 * <h2>Still scaffolding</h2>
 * Vector reranking (#16), LLM query-expansion / re-rank (#21) and MCP wiring (#17) arrive with their
 * own issues.
 */
package com.agentmemory.recall;
