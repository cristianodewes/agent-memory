# agent-memory

> **LLM-powered long-term memory for AI coding agents.**
> A Go client + Spring Boot server that captures every prompt, tool call and decision an
> agent makes, **compiles** them into a coherent markdown knowledge base with the help of an
> LLM, and hands the next session a ready-to-use "where you left off" briefing — before the
> first prompt.

> **Status:** pre-alpha. This repository currently holds the **architecture, roadmap and the
> full backlog of `feat` issues**. Implementation is tracked across milestones `M0`–`M9`
> (see [`docs/ROADMAP.md`](docs/ROADMAP.md)). No runtime code has been written yet — each
> milestone is designed to be picked up by an autonomous agent or contributor.

---

## The problem

LLM coding agents (Claude Code, Codex, Cursor, Gemini CLI, OpenCode, …) lose **all** context
when a session ends. The next session starts blind: re-deriving decisions, re-reading the same
files, re-asking the same questions. `agent-memory` gives agents a persistent, shared,
project-scoped memory so knowledge accumulates across sessions, tools and machines.

## What it does

1. **Captures** every prompt + tool call + session boundary with zero friction (lifecycle
   hooks — the user never types a "save note" command).
2. **Compiles** those raw observations into durable, human-readable markdown pages using an
   LLM (the *compile-don't-retrieve* pattern). The wiki is the source of truth; Postgres is a
   derived search index.
3. **Recalls** the right knowledge on demand via hybrid search (full-text + link-graph +
   vector), re-ranked and curated by the LLM, and injects the most relevant context at the
   start of each prompt.
4. **Hands off** between sessions and agents: an **LLM-written** briefing (summary, open
   questions, next steps) is prepared at session end and injected when the next agent starts —
   even a different CLI in the same directory.

## Why an LLM is required

`agent-memory` is **not** a zero-LLM tool. A configured LLM provider is a **hard requirement**,
validated at startup (fail-fast). Making the LLM central — rather than optional — unlocks
capabilities a rule-based engine cannot match:

| Flow | What the LLM does |
|---|---|
| **Handoffs** | Reads the session's observations and writes a rich, accurate "where you left off". |
| **Consolidation** | Turns noisy observations into clean concept/decision/gotcha pages; atomic multi-page fan-out. |
| **Recall** | Expands queries, re-ranks candidates, and curates what gets injected into a prompt. |
| **Curation** | Detects contradictions, suggests links, proposes self-improvements with approval gates. |
| **Chat** | "Chat with your memory" — answer questions grounded in the wiki (RAG). |

Providers are pluggable (Anthropic, OpenAI, OpenAI-compatible/Ollama/vLLM, Gemini, …) but a
provider **must** be configured. See [`docs/design-decisions.md`](docs/design-decisions.md#dd-005).

## Architecture at a glance

```
                ┌──────────────────────── developer machine ────────────────────────┐
                │                                                                     │
  Claude Code ──┤  lifecycle hooks ──▶  agent-memory (Go client)                      │
  Codex /       │                         • native hooks: capture → local spool       │
  Cursor / …    │                         • drain at session boundaries (≤200 ms)      │
                │                         • thin CLI (HTTP client of the server)       │
                │                         • install-mcp / install-hooks / setup-agent  │
                └───────────────┬─────────────────────────────────────────────────────┘
                                │  HTTPS: POST /hook · /handoff · /mcp · /api/v1
                                ▼
        ┌──────────────────────────── agent-memory server (Spring Boot) ─────────────┐
        │  /hook ingest ─▶ sanitize ─▶ single writer ─▶ observations                  │
        │  consolidation (LLM) ─▶ writes markdown ─▶ wiki/ (git, SOURCE OF TRUTH)     │
        │  recall: FTS + link-graph + vector ─▶ LLM rerank/curate                     │
        │  handoffs (LLM-generated) · MCP tool router · /api/v1 · /web (+ chat)       │
        └───────────────┬──────────────────────────────────┬─────────────────────────┘
                        │ derives index                     │ requires
                        ▼                                   ▼
                  Postgres 16                          LLM provider
            (tsvector FTS + pgvector,            (Anthropic / OpenAI / compat /
             sessions, links, handoffs,           Gemini …) — REQUIRED, health-gated
             embeddings, audit_log)
```

The **markdown wiki in a git repo is the source of truth** (grep-able, opens in Obsidian,
backs up with `rsync`, time-travels with `git`). Postgres is a *derived* index that can be
rebuilt at any time with `reindex`.

## Components

| Component | Stack | Responsibility |
|---|---|---|
| **Client** | Go 1.23+ | Lifecycle hooks, local spool + drain, thin CLI, MCP/hook installers, cwd→project resolution. |
| **Server** | Spring Boot 3.x (Java 25, Gradle) | Source of truth. Ingest, sanitize, store, consolidate (LLM), recall, handoffs, MCP `/mcp`, `/api/v1`, `/web`, auth. |
| **Index** | Postgres 16 + pgvector | Derived full-text (tsvector) + vector index, plus relational tables (sessions, observations, links, handoffs, audit). |
| **Wiki** | Markdown + git | Source of truth for compiled knowledge. |
| **LLM** | Pluggable provider (required) | Consolidation, handoffs, recall re-ranking, curation, chat. |

## Repository layout (target)

```
agent-memory/
├── client/                 # Go module — the `agent-memory` binary (hooks, spool, CLI, installers)
├── server/                 # Spring Boot app — source of truth (Gradle)
├── docker/                 # docker-compose (server + postgres), Dockerfiles, reverse-proxy templates
├── docs/                   # ARCHITECTURE, ROADMAP, design-decisions
└── .github/                # issue templates, CI workflows
```

> The `client/` and `server/` trees are created by the **M0 scaffolding** issue. This repo
> intentionally starts as planning-only so each piece is built by a dedicated task.

## Prerequisites (for contributors / agents)

- **Go 1.23+** — _note: not currently on this machine's PATH; install before working on `client/`._
- **JDK 25** (or 21+) and **Gradle 8.x** (a wrapper will be committed by M0).
- **Docker / docker-compose** — for Postgres and local end-to-end runs.
- **An LLM provider** API key/credentials — required to run the server (see design decision DD-005).

## How work is organized

Every unit of work is a **`feat` issue**, grouped into milestones `M0`–`M9`. Each issue is
written to be self-contained and agent-executable: context, scope, acceptance criteria,
implementation notes and tests. Start with **M0** and follow the dependency order in
[`docs/ROADMAP.md`](docs/ROADMAP.md). See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the
workflow and [`AGENTS.md`](AGENTS.md) for agent conventions.

## Prior art

`agent-memory` is a clean-room product inspired by a feature survey of
[`akitaonrails/ai-memory`](https://github.com/akitaonrails/ai-memory) (Rust), and by the
Karpathy "LLM wiki" (*compile, don't retrieve*) pattern, `agentmemory`, `basic-memory`,
`cognee`, `A-MEM`, and the Hermes Agent self-improvement loop. The key departure: **the LLM
is required, not optional.**

## License

MIT © 2026 Cristiano Dewes — see [`LICENSE`](LICENSE).
