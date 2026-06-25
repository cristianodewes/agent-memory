/**
 * Single, typed, immutable server configuration (ARCHITECTURE.md §6, §7).
 *
 * <p>This package owns the <em>one</em> configuration load at startup (invariant #1). Every
 * other subsystem (store, wiki, llm, recall, security) receives its settings from the beans
 * published here and never reads {@link System#getenv} or files directly.
 *
 * <p>Layering, lowest precedence first: built-in defaults &rarr; an optional external config
 * file ({@code agent-memory.yml}) &rarr; environment variables. Spring Boot's
 * {@code @ConfigurationProperties} relaxed binding performs the merge; the externalized file is
 * contributed by {@link com.agentmemory.config.AgentMemoryConfigImporter}.
 *
 * <p>{@link com.agentmemory.config.AgentMemoryConfig} is the resolved, validated object: it
 * canonicalizes and creates the absolute data directory (invariant #11), fails fast with an
 * actionable message on invalid input, and exposes the data-dir layout helpers
 * ({@code wiki/}, {@code raw/}, {@code db/}, {@code logs/}) that later issues build on.
 */
package com.agentmemory.config;
