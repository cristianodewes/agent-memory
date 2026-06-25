/**
 * Postgres repositories, the single writer, and decay math.
 * (See docs/ARCHITECTURE.md §6.)
 *
 * <p>{@link com.agentmemory.store.ObservationWriter} is the persistence entry point for captured
 * observations and the structural half of the DD-010 / invariant #6 privacy boundary: it accepts
 * only a {@code com.agentmemory.hooks.Sanitized<NewObservation>}, so unsanitized text cannot reach
 * the store (issue #9). The concrete Postgres-backed, single-writer implementation arrives with
 * issues #4, #12 and #24.
 */
package com.agentmemory.store;
