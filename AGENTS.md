# AGENTS.md

Guidance for AI agents working in this repository.

## What this project is

`agent-memory` — an LLM-powered long-term memory for AI coding agents. **Go client +
Spring Boot server**, with a **markdown+git wiki as the source of truth** and **Postgres
(FTS + pgvector) as a derived index**. The **LLM is a required dependency** (see
[`docs/design-decisions.md#dd-005`](docs/design-decisions.md)).

Start here:
1. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — components, data flow, schema, invariants.
2. [`docs/ROADMAP.md`](docs/ROADMAP.md) — the 40 `feat` issues across milestones M0–M9.
3. [`docs/design-decisions.md`](docs/design-decisions.md) — the binding ADRs.
4. [`CONTRIBUTING.md`](CONTRIBUTING.md) — workflow, branching, Definition of Done.

## Rules of engagement

- **One issue at a time.** Take the lowest-numbered open `feat` issue in the current milestone
  whose dependencies are closed. Do not start an issue whose `Depends on` are still open.
- **Honor the invariants** in ARCHITECTURE §7 — they are not negotiable.
- **Respect the boundaries.** The CLI is a thin HTTP client; only the server touches Postgres
  and the wiki. Untrusted text reaches the store only via `sanitize()`.
- **Structured output only** for LLM calls. Provider keys come from config, never hard-coded.
- **Keep client/server contracts in lockstep** — hook payloads, the MCP tool schema and the
  HTTP API are shared contracts; change both sides together.
- **Tests + lint must pass** before you consider an issue done.

## Conventions

- Go: `gofmt`/`goimports`, `golangci-lint`; package layout per ARCHITECTURE §6.
- Java: Spring Boot 4.x on JDK 21+ (25 works), Maven; package layout per ARCHITECTURE §6.
- Commits: Conventional Commits; branch `feat/<issue>-<slug>`.

## Project memory routing

When self-routing snippets are generated (issue #40, `memory_install_self_routing`), they will
be appended below this line.

<!-- agent-memory:self-routing -->
