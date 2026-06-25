/**
 * Postgres repositories, the single writer, and decay math.
 * (See docs/ARCHITECTURE.md §6.)
 *
 * <p>Holds the {@code pages} index repository ({@link com.agentmemory.store.PageRepository}, #12) —
 * the {@code is_latest}/{@code supersedes} version chain with atomic, per-path-serialized writes —
 * wired by {@link com.agentmemory.store.StoreConfiguration}. Decay math (#24) lands later.
 */
package com.agentmemory.store;
