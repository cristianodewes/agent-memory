# Roadmap

The project is delivered as **40 `feat` issues** across ten milestones. Each issue is
self-contained and agent-executable (context, scope, acceptance criteria, implementation
notes, tests). Build in milestone order; within a milestone, respect the `Depends on` field of
each issue.

> Legend — areas: `client-go` · `server` · `core` · `db` · `hooks` · `search` · `mcp` ·
> `consolidation` · `llm` · `web` · `security` · `infra`.

---

## Milestones

| ID | Milestone | Theme | Issues |
|----|-----------|-------|--------|
| **M0** | Foundations & scaffolding | Repos, config, identity, schema, CI, **LLM provider (required)** | #1–#6 |
| **M1** | Capture pipeline | Hooks → spool → drain → sanitize → store | #7–#11 |
| **M2** | Storage & retrieval | Versioned pages, git wiki, reindex, hybrid + vector search | #12–#16 |
| **M3** | MCP, consolidation & LLM recall | MCP tools, LLM compile, LLM-assisted recall | #17–#21 |
| **M4** | Handoffs (LLM-generated) | LLM "where you left off" + injection | #22–#23 |
| **M5** | Layered memory & decay | Decay math, forget sweep, memory slots | #24–#26 |
| **M6** | Cross-project graph & curation | Wikilinks, graph, curator/lint | #27–#29 |
| **M7** | Self-improvement loop | Scheduler, approval gate, eval gates | #30–#31 |
| **M8** | Identity & lifecycle ops | Marker file, rename/move/purge, time-travel, backup, bootstrap | #32–#34 |
| **M9** | Web, API, security, packaging | API, web + chat, auth, multi-user, providers, packaging | #35–#40 |

---

## M0 — Foundations & scaffolding

1. **feat(infra): monorepo scaffolding — Go client + Spring Boot server + dev tooling.**
   `client/` Go module, `server/` Spring Boot (Gradle wrapper), `docker/` compose, root docs,
   editorconfig, Makefile/Taskfile.
2. **feat(server): configuration loading (single path) + canonical absolute data dir.**
   One `Config.load()`; env + file; absolute data dir created/validated and logged at startup.
3. **feat(core): domain model & 3-tuple identity (workspace/project/page).** Shared spec; Java
   value types + Go structs; ID strategy; serialization contract for hooks/API.
4. **feat(db): Postgres schema & Flyway migrations + tsvector FTS + pgvector.** All head tables
   (§4.2 of ARCHITECTURE), FTS generated columns, vector columns, indexes.
5. **feat(infra): CI pipeline (GitHub Actions).** Go build/test/lint (`golangci-lint`) + Gradle
   build/test + docker-compose smoke + migration check.
6. **feat(llm): LLM provider abstraction (REQUIRED) + Embedder + typed ProviderAuth + startup
   health gate.** `LlmProvider`/`Embedder` interfaces, structured-JSON output contract, provider
   matrix stubs, fail-fast startup validation.

## M1 — Capture pipeline

7. **feat(hooks): hook event vocabulary & payload schemas.** Canonical `ObservationKind`
   (`session-start`, `user-prompt`, `pre-tool-use`, `post-tool-use`, `pre-compact`,
   `notification`, `stop`, `session-end`, `other`); per-client aliases; `extension=` seam.
8. **feat(server): /hook ingestion + backpressure (202/429) + single-writer + audit log.**
9. **feat(server): sanitization typed boundary (privacy strip).** `Sanitized<NewObservation>`
   equivalent — only constructor is `sanitize()`.
10. **feat(client-go): native hooks — local spool + drain at session boundaries.** Fire-and-forget
    ≤200 ms; short backlog drain at `session-start`, main drain at `session-end`; `/hook/batch`.
11. **feat(server): immutable session log (log.md) + raw archive.** One `## [ts] <event> | <title>`
    line per event; `raw/` immutable archive.

## M2 — Storage & retrieval

12. **feat(server): page storage with version chain (is_latest + supersedes) + atomic writes.**
13. **feat(server): markdown wiki source of truth — git commit-on-write + file watcher.** JGit
    commit per consolidation/session-end; watcher reconciles external edits and ignores own writes.
14. **feat(server): reindex — rebuild Postgres index from wiki files.**
15. **feat(search): hybrid RRF retrieval (FTS tsvector + link-graph) + raw observation fallback.**
16. **feat(search): vector reranking (pgvector + Embedder) fused into RRF.** Default-on, degrades
    gracefully if embeddings unavailable.

## M3 — MCP, consolidation & LLM recall

17. **feat(mcp): MCP /mcp endpoint + read-only tools.** `memory_query/recent/read_page/status/
    briefing`; project resolved from recent hook activity.
18. **feat(consolidation): LLM session synthesis at session-end → sessions/<id>.md.** Always LLM
    (no rule-based fallback); structured JSON output; git commit.
19. **feat(consolidation): LLM consolidation + atomic multi-page fan-out + memory_consolidate/
    explore.** Rewrite to durable pages across `concepts/decisions/gotchas/procedures`.
20. **feat(mcp): memory_write_page + memory_delete_page (admission chain).**
21. **feat(llm): LLM-assisted recall — query expansion, candidate rerank, curated prompt-time
    injection.** Sits on top of #15/#16; powers `memory_query` and the UserPromptSubmit injection.

## M4 — Handoffs (LLM-generated)

22. **feat(server): LLM-generated typed handoff + endpoints + MCP tools.** `begin/accept/cancel`,
    single-use; the handoff body (summary, open questions, next steps) is written by the LLM from
    the session's observations.
23. **feat(client-go): SessionStart handoff injection.** Fetch + inject the "where you left off"
    block before the first prompt; `memory_handoff_begin` for clients without a session-end hook.

## M5 — Layered memory & decay

24. **feat(server): layered memory + decay math + access reinforcement.** working/episodic/
    semantic/procedural; `salience·exp(−λΔt) + σ·log(1+access)·exp(−μ·days)`.
25. **feat(server): forget sweep + pinned exemption + dry_run + memory_forget_sweep.**
26. **feat(server): memory slots (_slots/).** Auto-pinned editable `slot_kind: state|invariant`
    slots surfaced in briefing/explore.

## M6 — Cross-project graph & curation

27. **feat(server): scoped wikilinks + deferred-safe resolution + auto backlinks.**
    `[[project:path]]`, `[[ws/project:path]]`; `to_page_id` nullable, re-pointed when target appears.
28. **feat(server): unified dependency graph + /api/v1/graph + dangling-ref lint.**
29. **feat(consolidation): curator + memory_lint (rules + LLM contradictions) + cross-project
    scopes/global.** Rule-based maintenance report; LLM contradiction findings → `_lint/`.

## M7 — Self-improvement loop

30. **feat(consolidation): auto-improve scheduler + pending-writes audit + approval gate + report
    + memory_auto_improve.** Non-overlapping ticks; first-run watermark; per-session claims;
    `require_approval` toggle.
31. **feat(consolidation): executable eval gates (JSON contract, off by default, never in hook
    path).** Runs a project-supplied gate for selected proposal prefixes after LLM validation.

## M8 — Identity & lifecycle ops

32. **feat(client-go): project derivation from cwd + .agent-memory.toml marker file.**
33. **feat(server): lifecycle ops — rename/move/purge project + reset with live-process check.**
34. **feat(server): time-travel (checkpoints + restore-page) + online backup/restore + bootstrap.**

## M9 — Web, API, security, providers, packaging

35. **feat(web): read-only /api/v1 JSON API.** workspaces/projects/pages/recent/briefing/overview/
    search/graph.
36. **feat(web): embedded /web read-only browser.** Project list, folder tree, FTS, markdown
    render, dark mode.
37. **feat(web): "chat with your memory" — LLM chat grounded in the wiki (RAG).** Folder/project
    scoped chat over the API; guardrails on LLM-mediated writes.
38. **feat(security): auth — loopback default + bearer + Basic on /web + allowed hosts.**
39. **feat(security): multi-user attribution + OIDC device for native hooks.** `user add/list/
    expire/rotate-token`, token pepper; per-user audit attribution.
40. **feat(infra): provider matrix + packaging + self-routing snippet.** Anthropic/OpenAI/
    OpenAI-compat/Gemini + embeddings; docker-compose, Go binary releases, install-mcp/
    install-hooks/setup-agent/uninstall; `memory_install_self_routing`.

---

## Dependency notes

- **M0 gates everything.** In particular #4 (schema) and #6 (LLM provider) are prerequisites
  for most later work.
- **Capture (M1) before retrieval (M2)** — there is nothing to index until events flow.
- **MCP read tools (#17) before** consolidation/handoff tools so the surface is testable.
- **#18/#19 (LLM consolidation) depend on #6 (LLM provider) and #12/#13 (page storage + wiki).**
- **Handoffs (M4) depend on** consolidation (#18) and the MCP transport (#17).
- **Self-improvement (M7) depends on** consolidation (M3) and curation (M6).
- **Web/API (M9) depends on** storage + recall (M2/M3); **chat (#37) depends on** the LLM
  provider (#6) and recall (#21).
