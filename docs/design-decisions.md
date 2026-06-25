# Design decisions

Architecture Decision Records for **agent-memory**. Each entry states the decision, the
context, and the rationale. These are binding for implementers unless superseded.

---

## DD-001 — Two components: Go client, Spring Boot server

**Decision.** A small native **Go** binary on the developer's machine (hooks, spool, CLI,
installers) talks over HTTP to a **Spring Boot** server that owns all state and logic.

**Why.** The client sits on the agent's hot path, where JVM startup per hook is unacceptable —
a native Go binary starts in milliseconds and is trivial to distribute. The server, where the
real work lives (storage, LLM consolidation, search, web), benefits from the Spring/JVM
ecosystem (Spring Web, Data, Flyway, JGit, scheduling, mature Postgres tooling). The split also
mirrors the prior art's discipline that "the CLI is a thin HTTP client; the server is the only
source of truth."

## DD-002 — Markdown + git is the source of truth; Postgres is a derived index

**Decision.** Compiled knowledge lives as markdown files in a git-versioned `wiki/` directory.
Postgres is a *derived* index (FTS + vector + relational metadata) rebuildable via `reindex`.

**Why.** Keeps the product's soul: knowledge is grep-able, opens in Obsidian, backs up with
`rsync`, and time-travels with `git`. It avoids coupling durable knowledge to a database schema
and makes `reindex`/restore cheap. The cost — a file writer, git commit-on-write, and a watcher
to reconcile external edits — is accepted. (Capture tables — `sessions`, `observations`,
`audit_log` — are primary in Postgres, not derived, and are covered by backup/restore.)

> Alternative considered: Postgres-primary (page bodies in the DB). Rejected for v1 because it
> forfeits Obsidian editing and git history, which are core differentiators.

## DD-003 — MCP is hosted on the server (Streamable HTTP)

**Decision.** The MCP tool surface is served by the Spring server at `/mcp`. The Go client
registers it with the agent (`install-mcp`) and provides the hooks. MCP project scope is
resolved from the session's recent hook activity.

**Why.** Tool logic touches storage, search and the LLM — keeping a single implementation on
the server avoids duplicating it in Go. Hooks already report `cwd`, so the server can resolve
"the current project" from recent activity without the agent passing it explicitly. Tools still
accept explicit `workspace`/`project`/`scopes` for cross-project queries.

## DD-004 — Postgres 16 + pgvector + tsvector (single datastore)

**Decision.** One Postgres instance provides relational storage, full-text search (`tsvector`),
and vector search (`pgvector`). No separate search engine or vector database.

**Why.** Operational simplicity — one thing to run, back up and reason about. `tsvector` covers
FTS; `pgvector` covers semantic recall; Reciprocal Rank Fusion blends them with the link graph.
Avoiding a bespoke vector DB is an explicit inherited differentiator.

## DD-005 — The LLM is a required dependency (the deliberate inversion)

**Decision.** Unlike the prior art (which runs fully without an LLM), **agent-memory requires a
configured LLM provider.** The server validates provider connectivity at startup and **fails
fast** if none is reachable. There is no rule-based fallback path to maintain.

**Why.** Making the LLM central — not optional — is the product thesis: it unlocks capabilities
a rules engine cannot match and removes the complexity of dual code paths.

- **Handoffs are LLM-written** — the model reads the session's observations and produces an
  accurate "where you left off" (summary, open questions, next steps), instead of a template.
- **Session synthesis & consolidation are always LLM** — cleaner pages, atomic multi-page fan-out.
- **Recall is LLM-assisted** — query expansion, candidate re-ranking, and curated prompt-time
  injection on top of FTS + graph + vector.
- **Curation** — contradiction detection, link suggestions, and self-improvement proposals.
- **Chat with your memory** — RAG answers grounded in the wiki.

**Boundaries kept.** Providers remain pluggable behind a typed interface (`LlmProvider` /
`Embedder`), auth resolves before client construction (typed `ProviderAuth`), and all LLM calls
use provider-native structured-JSON output. **Embeddings** are a separate (default-on) axis: if
embeddings are unavailable, recall degrades to FTS + graph, but a chat/consolidation LLM is
mandatory.

> This replaces invariant "zero-LLM default" with invariant #13: *LLM is required, validated at
> startup; all knowledge-shaping flows are LLM-mediated.*

## DD-006 — Single-writer discipline

**Decision.** All writes (Postgres and wiki) are serialized through one path; indexes commit in
the same logical transaction as the data they describe.

**Why.** Eliminates a class of races and index/data divergence bugs documented in prior art.
No background indexing after a request returns.

## DD-007 — Loopback-only by default, bearer auth to open up

**Decision.** The server binds `127.0.0.1` with no auth by default (safe single-user laptop).
Exposing it requires a bearer token (`/mcp`, `/hook`, `/handoff`, `/admin/*`, `/web/*`), HTTP
Basic on `/web`, and an allowed-hosts guard against DNS rebinding. TLS is terminated by a
reverse proxy (templates provided), not the app.

**Why.** Secure-by-default for the common case; explicit, well-trodden steps to go multi-user
or networked without baking TLS into the app.

**Multi-user mode (#39).** Setting a token pepper (`agent-memory.auth.token-pepper`) turns on
multi-user mode on top of the bearer auth above. Each user gets their own token
(`user add/list/expire/revive/rotate-token`); only its peppered SHA-256 hash is stored, never the
token. A per-user token authenticates as that user on the normal routes (`/api/v1`, `/mcp`,
`/hook`, `/handoff`, `/web`) and is **forbidden** on the mutating admin routes (project lifecycle,
`/reset`, `/reindex`, the time-travel mutations, `/users/*`, `/llm-test`), which require the shared
**root** token. The authenticated principal — a user slug, or `root` — is recorded as the `actor`
on every `observations` and `audit_log` row, so a shared server's capture log and audit trail are
attributable. Captures are resolved to an actor at the `/hook` boundary (the async write worker has
no security context) and threaded through; synchronous admin mutations resolve it on the request
thread. Single-user mode is unchanged: with no pepper the lone root token satisfies the admin gate
and rows carry no actor.

**auto_scope (#39, #87).** `agent-memory.scope.auto` governs the *default* `(workspace, project)` an
MCP call resolves to when it gives no explicit scope (DD-003): `single_slot` (default) keeps the prior
behavior — the server's globally most-recent project — `per_actor` resolves to the authenticated
caller's *own* most-recent activity (via `observations.actor`), so users on a shared server don't
default into each other's project, and `session_aware` (#87) narrows that one step further to *this
capture session's* most-recent activity (via `observations.session_id`), so two sessions of the **same**
user in different projects default into their own lanes. An explicit scope always wins; with no
authenticated actor `per_actor` falls back to the global default.

*The session_aware security property (#87): fail closed, never fall back.* Unlike the other modes,
`session_aware` will **never** silently widen to the global or per-actor scope when it lacks a session
id — that would leak one session's project into another. If no session id reached the request, a
no-scope call raises `ScopeUnresolvedException` (a clear error naming the missing `X-Agent-Memory-Session`
header) rather than guessing. This is the central guarantee, and it holds regardless of how the client
is wired, so a misconfigured or absent transport degrades to a hard error, not a cross-session leak.

*The transport (how a session id reaches the boundary).* Claude Code does not send its session id to a
remote MCP server, so #87 carries it explicitly. The native hook — which knows the session id and runs
in the project tree — records it at SessionStart under `<data_dir>/sessions/<workspace>/<project>`
(0600). Claude Code's MCP `headersHelper` then runs `agent-memory mcp-session-header` (with the project
identity baked into its arguments at install time, since the helper runs in an unspecified working
directory) to read that id back and emit the `X-Agent-Memory-Session` header. On the server an
`OncePerRequestFilter` validates the header as a UUID and binds it to the request thread; the
`ScopeResolver` reads it there and queries the per-session most-recent scope. *Isolation guarantee &
limits:* different projects use different files (keyed by cwd-derived identity), so they isolate
cleanly — the real win. Two sessions of the same user in the **same** project share one file
(last-writer-wins); that is a deliberate no-op, because the same project resolves to the same default
scope either way. And because `headersHelper` runs once per connection at session start, a connection
that races ahead of the SessionStart write reads the previous session's id (same project → benign) or
none (→ the fail-closed error above); never another project's scope. The OIDC device grant below brings
the session-bound verified identity this builds on.

**OIDC device auth for native hooks (#39 PR2).** A native hook runs headless, so it cannot do a
browser redirect; it uses the OAuth 2.0 Device Authorization Grant (RFC 8628) instead. Setting
`agent-memory.auth.oidc.issuer` (plus `audience`, and an optional explicit `jwks-uri`) makes the
server accept a JWT bearer **in addition** to the opaque root/per-user tokens: a token shaped like a
JWT is validated against the IdP — signature against the issuer's JWKS, plus `iss`, `aud` and expiry
— by Spring Security's `NimbusJwtDecoder` (never hand-rolled), and authenticates as its verified
subject claim (the `actor`, exactly like a per-user token). A JWT that fails validation is rejected
outright, never retried as an opaque token; OIDC subjects are ordinary users, **forbidden** on the
root-only admin routes. The client side (`agent-memory auth login oidc-device --issuer … --client-id
…`) runs the device flow — discover the endpoints, print the verification URL + user code, poll the
token endpoint (honoring `authorization_pending` / `slow_down`) — and stores the access token at
`~/.agent-memory/credentials.json` (0600). The native hook attaches that token as its bearer when no
explicit `$AGENT_MEMORY_TOKEN` (or `.agent-memory.toml` token) is set, so an interactive `login`
gives every subsequent headless capture a verified identity without putting a long-lived secret in
the environment. The server is the sole authority on token validity; the client only obtains, stores
and presents it.

*Security model (the access decision).* **Audience is the gate**: with an issuer set, `audience` is
config-required (startup fails without it) and the token's `aud` must contain it exactly — so only a
token minted *for this server* is accepted, never just "any token the IdP issued". The accepted JWS
algorithms are **pinned to the asymmetric set** (RSA / ECDSA / RSA-PSS), rejecting `alg:none` and any
HMAC and so closing the RS256→HS256 key-confusion attack. OIDC requires `auth.enabled=true` (an issuer
configured while auth is off fails fast — no half-on identity). **Revocation**: an OIDC subject has no
row in `users`, hence no local kill-switch — trust derives from the issuer + (tight) audience, and
revocation relies on the IdP plus short token TTLs; this is acceptable precisely *because* the audience
is exact and required. A per-subject allow-list (`agent-memory.auth.oidc.allowed-subjects`) is a named
follow-up if a local kill-switch is later wanted. On the client, an **expired** stored credential
yields no bearer and, on the events that reach the server, prints a clear "run `auth login
oidc-device`" hint rather than looping silently on 401s (`auth status` shows the same).

## DD-008 — Monorepo

**Decision.** `client/` (Go) and `server/` (Spring) live in one repository with shared docs and
CI.

**Why.** The hook payload schema, the MCP contract and the HTTP API are shared contracts;
keeping them in one repo keeps client and server in lockstep and simplifies cross-cutting
changes and release coordination.

## DD-009 — Toolchain: Go 1.23+, JDK 21+, Maven, Flyway, JGit

**Decision.** Client targets Go 1.23+. Server targets Spring Boot 4.x on JDK 21+ (JDK 25 works),
built with Maven (the Maven Wrapper `mvnw` is committed); Flyway for migrations; JGit for wiki commits.

**Why.** These are the toolchains present/expected on the maintainer's machine (JDK 25, Maven
3.9.9 and Go 1.26 all confirmed) and the mainstream choices for each ecosystem.

## DD-010 — Privacy sanitization is a typed boundary

**Decision.** Untrusted captured text reaches the store only through a `sanitize()` constructor;
there is no other way to build a storable observation.

**Why.** Makes "did this text get privacy-stripped?" a compile-time guarantee rather than a code
-review hope.
