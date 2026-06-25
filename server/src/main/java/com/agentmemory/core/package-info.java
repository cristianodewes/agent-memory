/**
 * Domain types, ids and errors. <strong>No IO</strong>, no framework wiring (ARCHITECTURE §6) —
 * only the Jackson <em>annotations</em> needed to pin the wire contract. This is the shared
 * vocabulary the server, the hook payloads and the API all agree on (issue #3).
 *
 * <h2>Identity</h2>
 * Every domain record carries the typed 3-tuple {@link com.agentmemory.core.Identity}
 * {@code (workspace, project, path)} (invariant #4) — never loose strings. The coordinates are
 * {@link com.agentmemory.core.WorkspaceId}, {@link com.agentmemory.core.ProjectId} and
 * {@link com.agentmemory.core.PagePath} (page-scoped rows only; project-scoped rows leave it null).
 *
 * <h2>Ids</h2>
 * Surrogate ids — {@link com.agentmemory.core.SessionId},
 * {@link com.agentmemory.core.ObservationId}, {@link com.agentmemory.core.PageId},
 * {@link com.agentmemory.core.LinkId}, {@link com.agentmemory.core.HandoffId} — wrap a
 * {@link java.util.UUID} minted as UUIDv7 ({@link com.agentmemory.core.Uuid7}) so rows sort by
 * creation time. Each is a distinct type so ids cannot be confused across tables.
 *
 * <h2>Records</h2>
 * {@link com.agentmemory.core.Page}, {@link com.agentmemory.core.Observation},
 * {@link com.agentmemory.core.Session}, {@link com.agentmemory.core.Link} and
 * {@link com.agentmemory.core.Handoff}, plus the canonical
 * {@link com.agentmemory.core.ObservationKind} enum (client-alias mapping is #7, not here) and
 * {@link com.agentmemory.core.HandoffStatus}.
 *
 * <h2>Serialization</h2>
 * Value types serialize as bare JSON scalars ({@code @JsonValue}); records as camelCase JSON
 * objects with nulls omitted ({@code @JsonInclude(NON_NULL)}). The normative contract and the
 * cross-language golden fixtures live under {@code docs/contracts/}; the Go mirror is
 * {@code client/internal/core}.
 */
package com.agentmemory.core;
