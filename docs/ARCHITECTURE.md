# Architecture

This document describes the target architecture of **agent-memory**: a Go client and a
Spring Boot server that give AI coding agents a persistent, LLM-compiled, project-scoped
memory. It is the reference for every milestone in [`ROADMAP.md`](ROADMAP.md).

---

## 1. Design goals

1. **Zero-friction capture.** Agents never call a "save note" tool; lifecycle hooks capture
   everything automatically and never block the agent's hot path.
2. **Compile, don't retrieve.** Raw observations are compiled by an LLM into durable,
   human-readable markdown pages — not retrieved over raw logs at query time.
3. **Markdown + git is the source of truth.** Grep-able, Obsidian-editable, `rsync`-backupable,
   git-time-travelable. Postgres is a *derived* index that can always be rebuilt.
4. **LLM-first (required).** A configured LLM provider is mandatory and validated at startup.
   Handoffs, consolidation, recall re-ranking, curation and chat are all LLM-mediated.
5. **Hybrid recall.** Full-text (Postgres `tsvector`) + link-graph neighborhood + vector
   (`pgvector`) fused with Reciprocal Rank Fusion, then LLM re-ranked/curated.
6. **Typed identity & isolation.** Every domain row carries a `(workspace, project, path)`
   tuple from day one; projects are cheap to rename/move/purge.
7. **Operational simplicity.** One server, one Postgres, one git-backed wiki directory. No
   bespoke vector database to operate.

## 2. Components

```
 ┌─────────────┐   stdio/HTTP MCP + lifecycle hooks   ┌──────────────────────────┐
 │  AI agent   │ ───────────────────────────────────▶ │  agent-memory (Go client)│
 │ Claude Code │ ◀─── injected handoff / recall ────── │                          │
 └─────────────┘                                       └────────────┬─────────────┘
                                                                     │ HTTP
                                                                     ▼
                                                  ┌───────────────────────────────────┐
                                                  │   agent-memory server (Spring)    │
                                                  │  ingest · sanitize · store ·       │
                                                  │  consolidate(LLM) · recall ·       │
                                                  │  handoff(LLM) · MCP · API · web    │
                                                  └───────┬──────────────┬────────────┘
                                                          │              │
                                          derived index   │              │ requires
                                                          ▼              ▼
                                                  ┌──────────────┐  ┌──────────────┐
                                                  │ Postgres 16  │  │ LLM provider │
                                                  │ FTS+pgvector │  │  (required)  │
                                                  └──────────────┘  └──────────────┘
                                                          ▲
                                       source of truth    │ reindex (rebuildable)
                                                  ┌──────────────┐
                                                  │  wiki/ (git) │
                                                  └──────────────┘
```

### 2.1 Go client (`client/`)

The only piece that runs on the developer's machine. It must be a small, fast native binary
because it sits on the agent's hot path.

- **Native hooks** (`agent-memory hook --event <kind>`): invoked by the agent's lifecycle
  hooks. Writes the event to a **local spool** and returns immediately (fire-and-forget,
  ≤200 ms). Never blocks on the network.
- **Drain**: at session boundaries (`session-start`, `session-end`) the spool is drained to
  the server via `POST /hook` (and `/hook/batch`). A short backlog drain also runs at
  `session-start` before fetching the handoff.
- **Thin CLI**: every subcommand (`status`, `search`, `read-page`, `write-page`, `consolidate`,
  `backup`, …) is an **HTTP client of the server** — it never touches Postgres or the wiki
  directly. The server is the single source of truth.
- **Installers**: `install-hooks`, `install-mcp`, `install-instructions`, `setup-agent`,
  `uninstall` — idempotent commands that wire the agent's settings to agent-memory.
- **Identity resolution**: walks up from `cwd` to the main git root to derive the project; a
  `.agent-memory.toml` marker file in any ancestor overrides workspace/project.

### 2.2 Spring Boot server (`server/`)

The source of truth and the home of all knowledge-shaping logic.

- **Ingest** (`POST /hook`, `/hook/batch`, `/handoff`): accepts hook payloads, returns `202`
  immediately or `429` under saturation (never enqueues unbounded work).
- **Sanitize**: a typed boundary (privacy strip) is the *only* path untrusted text takes to
  the store.
- **Store**: a single-writer discipline (all writes serialized) into Postgres; markdown pages
  written atomically to `wiki/` and committed to git on every consolidation/session-end.
- **Consolidate (LLM)**: compiles observations into `sessions/`, `concepts/`, `decisions/`,
  `gotchas/`, `procedures/` pages; supports atomic multi-page fan-out.
- **Recall**: hybrid FTS + graph + vector RRF, then LLM query-expansion / re-ranking and
  curated prompt-time injection.
- **Handoffs (LLM)**: at session end the LLM writes a typed handoff (summary, open questions,
  next steps); single-use.
- **MCP `/mcp`**: Streamable-HTTP MCP endpoint exposing the tool surface (§5.1). Project is
  resolved from recent hook activity for the session.
- **`/api/v1`**: read-only JSON API for custom frontends.
- **`/web`**: embedded read-only browser + LLM "chat with your memory".
- **Self-improvement**: a scheduler reviews finished sessions out-of-band, proposing edits
  through an approval gate with optional executable eval gates.

### 2.3 Postgres (derived index)

Holds the relational + search state derived from the wiki and the raw capture log. Always
rebuildable from `wiki/` via `reindex` (capture tables — `sessions`, `observations`,
`audit_log` — are the exception: they are primary and covered by backup/restore).

### 2.4 LLM provider (required)

A pluggable provider behind a typed interface. The server validates connectivity at startup
and **fails fast** if no provider is reachable. Embeddings are a separate (default-on)
provider axis; if embeddings are unavailable, recall degrades gracefully to FTS + graph.

## 3. Data flow

### 3.1 Capture
```
agent event ─▶ Go hook ─▶ local spool (disk)            [returns in ≤200 ms]
   … at session boundary …
spool ─▶ POST /hook(/batch) ─▶ sanitize ─▶ single writer ─▶ observations (+ log.md append)
```

### 3.2 Consolidation (LLM)
```
session-end / pre-compact / memory_consolidate
   ─▶ load session observations
   ─▶ LLM compile (structured JSON output)
   ─▶ atomic markdown write to wiki/ (tmp+rename+fsync)
   ─▶ git commit
   ─▶ reindex affected pages (FTS + embeddings) in same logical txn
```

### 3.3 Recall (LLM-assisted)
```
memory_query / prompt-time injection
   ─▶ FTS(tsvector) ⊕ link-graph neighborhood ⊕ vector(pgvector)   [RRF fuse]
   ─▶ LLM query-expansion + candidate re-rank + curation
   ─▶ return hits / inject top-N into prompt
   ─▶ bump access_count + last_accessed_at (decay reinforcement)
```

### 3.4 Handoff (LLM)
```
session-end ─▶ LLM writes typed handoff (summary, open Qs, next steps) ─▶ store (open)
next session-start ─▶ Go hook fetches + injects handoff ─▶ accept marks it consumed (single-use)
```

## 4. Storage model

### 4.1 Data directory layout
```
<data_dir>/
├── wiki/     # markdown source of truth, one git repo
│   └── <workspace>/<project>/
│       ├── sessions/   concepts/   decisions/   gotchas/   procedures/
│       ├── _rules/     _slots/     _lint/
│       └── log.md      # immutable per-session event log
├── raw/      # immutable raw session archive
├── db/       # (optional) local artifacts
└── logs/     # rotating tracing
```

### 4.2 Postgres schema (head)

| Table | Contents |
|---|---|
| `workspaces`, `projects` | Top of the 3-tuple identity coordinate. |
| `pages` | Versioned wiki pages: `is_latest` + `supersedes` chain; access columns (decay) + embedding ref. |
| `pages_fts` | `tsvector` generated column / index over `(title, body)`. |
| `sessions`, `observations` | Hook capture + full audit of agent activity. |
| `observations_fts` | `tsvector` over raw observations (bounded fallback). |
| `links` | Wikilinks / cross-refs; `to_page_id` nullable (forward/deferred links); cross-project scope. |
| `handoffs` | Typed handoff records (open / accepted / expired). |
| `page_embeddings` | `pgvector` column with `(provider, model, dim)` denormalized. **Default dim contract = `1024`** (the default embedder, Voyage `voyage-3`; see §2.4 and `com.agentmemory.llm.VoyageEmbedder.DEFAULT_DIMENSIONS`). The `pgvector` column in #4 sizes to this; a provider returning a different width fails the `EmbeddingResult`/`Embedder.dimensions()` check rather than being stored. |
| `audit_log` | Every mutation, addressable by `at DESC`. |
| `pending_writes` | Self-improvement proposals awaiting/approved through the gate. |

## 5. Integration surface

### 5.1 MCP tools (hosted on the server `/mcp`)

| Tool | Hint | Purpose |
|---|---|---|
| `memory_query` | read | Hybrid FTS+graph+vector, LLM-reranked, raw fallback. |
| `memory_recent` | read | Most recently updated latest pages. |
| `memory_read_page` | read | Full page body by path or top FTS hit. |
| `memory_status` | read | Counts, paths, version. |
| `memory_briefing` | read | Structured snapshot (counts/activity/rules/slots/recent). |
| `memory_explore` | read | LLM prose digest over the briefing. |
| `memory_handoff_begin` | write | Open an (LLM-written) handoff. |
| `memory_handoff_accept` | write | Fetch + ack the latest open handoff (auto by cwd). |
| `memory_handoff_cancel` | write | Expire a mistaken handoff. |
| `memory_consolidate` | write | LLM page rewrite; `multi_page=true` for atomic fan-out. |
| `memory_auto_improve` | write | Review a finished session and stage/apply edits. |
| `memory_write_page` | write | Durable annotation when the user asks to remember something. |
| `memory_delete_page` | write | Delete a page by exact path (idempotent). |
| `memory_forget_sweep` | write | Retention pass; `dry_run=true` to preview. |
| `memory_lint` | write | Contradiction findings (rules + LLM) → `_lint/`. |
| `memory_install_self_routing` | read | Returns the routing snippet for CLAUDE.md / AGENTS.md. |

### 5.2 HTTP API (`/api/v1`, read-only)
```
GET  /api/v1/workspaces
GET  /api/v1/projects?workspace=...
GET  /api/v1/workspaces/{ws}/projects/{p}/pages
GET  /api/v1/workspaces/{ws}/projects/{p}/pages/{path}
GET  /api/v1/workspaces/{ws}/projects/{p}/recent?limit=...
GET  /api/v1/workspaces/{ws}/projects/{p}/briefing?limit=...
GET  /api/v1/workspaces/{ws}/overview?limit=...
GET  /api/v1/search?q=...&workspace=...&project=...&limit=...
POST /api/v1/search   { "q": "...", "scopes": [{ "workspace": "...", "project": "..." }] }
GET  /api/v1/graph
```

### 5.3 Web (`/web`)
Read-only markdown browser (project list, folder tree, FTS, dark mode) **plus** an LLM
"chat with your memory" grounded in the wiki (RAG). Non-GET browser requests are guarded.

### 5.4 Hooks (`/hook`, `/handoff`)
Native Go hooks spool locally and drain at boundaries. An `extension=<namespace>` field lets
third parties record their own `source_event` without expanding the canonical enum.

## 6. Module / package layout

The Spring server's packages echo the single-responsibility crate boundaries of the prior art
(no circular dependencies):

| Package | Responsibility |
|---|---|
| `com.agentmemory.core` | Domain types, ids, errors. No IO. |
| `com.agentmemory.store` | Postgres repositories, single writer, decay math. |
| `com.agentmemory.wiki` | Atomic markdown writes, file watcher, git. |
| `com.agentmemory.hooks` | Payload schemas, sanitizer, `/hook` ingress. |
| `com.agentmemory.llm` | Provider auth boundary + `LlmProvider` / `Embedder`. |
| `com.agentmemory.consolidate` | Compile / lint / sweep / auto-improve pipeline. |
| `com.agentmemory.recall` | Hybrid search + RRF + LLM re-rank. |
| `com.agentmemory.mcp` | MCP transport + tool router. |
| `com.agentmemory.web` | `/api/v1`, `/web`, markdown rendering, chat. |
| `com.agentmemory.security` | Auth, tokens, allowed-hosts, multi-user. |

Go client packages:

| Package | Responsibility |
|---|---|
| `cmd/agent-memory` | Binary entrypoint. |
| `internal/cli` | Subcommands (thin HTTP client). |
| `internal/hook` | Hook event capture + canonicalization. |
| `internal/spool` | Local on-disk spool. |
| `internal/drain` | Boundary drain to the server. |
| `internal/apiclient` | Typed HTTP client of the server. |
| `internal/config` | Single config load. |
| `internal/install` | install-hooks / install-mcp / setup-agent. |

## 7. Cross-cutting invariants

Each invariant exists to prevent a class of bug seen in prior art. Agents implementing any
milestone must uphold these:

1. **Single config load** at startup (`Config.load()` once).
2. **Single writer** — all Postgres + wiki writes serialized through one path.
3. **Index commits with the data** — no post-return background indexing that can diverge.
4. **Typed 3-tuple identity** `(workspace, project, path)` on every domain row.
5. **Hooks are fire-and-forget** — hard timeout ≤200 ms; server returns `202` or `429`.
6. **Sanitization is a typed boundary** — untrusted text reaches the store only via `sanitize()`.
7. **Structured JSON outputs only** from the LLM (provider-native JSON-schema modes).
8. **`{provider, model, dim}` denormalized** next to every embedding.
9. **Live-process check before destructive ops** (`reset` / `backup` / `restore`).
10. **Atomic file writes** (tmp + rename + fsync; the watcher ignores its own writes).
11. **Canonical absolute data dir**, logged at startup.
12. **No global singletons** — dependencies are explicit (Spring beans / Go constructors).
13. **LLM is required** — validated at startup (fail-fast); all knowledge-shaping flows are
    LLM-mediated. *(This is the deliberate inversion of ai-memory's zero-LLM default.)*
14. **Provider auth resolves before client construction** (typed `ProviderAuth`, never raw env reads).
15. **Tracing subscribers filter their own module** (no logging feedback loops).

## 8. Tech stack

| Concern | Choice |
|---|---|
| Client | Go 1.23+ (Cobra CLI, native MCP/hook bridges) |
| Server | Spring Boot 4.x on JDK 21+ (Maven, Spring Web, Spring Data JDBC/JPA) |
| Index DB | Postgres 16 + `pgvector`, full-text via `tsvector` |
| Migrations | Flyway |
| Wiki | Markdown in a git repo (JGit on the server) |
| MCP | Streamable-HTTP transport mounted on the server |
| LLM | Pluggable provider (Anthropic / OpenAI / OpenAI-compat / Gemini), **required** |
| Packaging | docker-compose (server + postgres), native Go binaries, reverse-proxy TLS templates |
| CI | GitHub Actions (Go + Maven) |

See [`design-decisions.md`](design-decisions.md) for the rationale behind each major choice.
