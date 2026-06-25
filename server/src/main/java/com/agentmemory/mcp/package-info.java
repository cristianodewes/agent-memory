/**
 * MCP transport (Streamable HTTP at /mcp) and the read-only tool surface (DD-003, ARCHITECTURE
 * §2.2, §5.1).
 *
 * <h2>Issue #17 — MCP endpoint + read-only tools (this milestone)</h2>
 * {@link com.agentmemory.mcp.McpConfiguration} stands up the official MCP Java SDK's
 * Streamable-HTTP servlet transport at {@code /mcp} and an {@code McpSyncServer} bound to the five
 * read tools in {@link com.agentmemory.mcp.MemoryTools}: {@code memory_query} (hybrid recall over
 * {@link com.agentmemory.recall.RecallService}, #15/#16), {@code memory_recent} and
 * {@code memory_read_page} (over {@link com.agentmemory.store.PageRepository}, #12),
 * {@code memory_status} and {@code memory_briefing} (a structured snapshot with <em>no</em> LLM
 * call, over {@link com.agentmemory.mcp.McpReadRepository}). Handlers are thin: resolve the scope,
 * call one service, shape the JSON result.
 *
 * <p>Scope follows DD-003: {@link com.agentmemory.mcp.ScopeResolver} defaults to the most recently
 * active project (from hook capture) and accepts an explicit {@code workspace}+{@code project}
 * override. Each tool advertises the read-only annotation hint.
 *
 * <h2>Still scaffolding</h2>
 * Write/destructive tools (handoffs/consolidate/write-page — #19/#20/#22), LLM re-rank (#21) and
 * {@code memory_explore} prose (#19) arrive with their own issues; bearer auth is #38 (loopback
 * default for now).
 */
package com.agentmemory.mcp;
