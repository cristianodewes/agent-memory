/**
 * Postgres repositories, the single writer, and decay math.
 * (See docs/ARCHITECTURE.md §6.)
 *
 * <p>Holds the {@code pages} index repository ({@link com.agentmemory.store.PageRepository}, #12) —
 * the {@code is_latest}/{@code supersedes} version chain with atomic, per-path-serialized writes —
 * wired by {@link com.agentmemory.store.StoreConfiguration}.
 *
 * <p>{@link com.agentmemory.store.ObservationWriter} is the persistence entry point for captured
 * observations and the structural half of the DD-010 / invariant #6 privacy boundary: it accepts
 * only a {@code com.agentmemory.hooks.Sanitized<NewObservation>}, so unsanitized text cannot reach
 * the store (issue #9). Decay math (#24) lands later.
 */
package com.agentmemory.store;
