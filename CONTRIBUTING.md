# Contributing

`agent-memory` is built milestone-by-milestone from the backlog in
[`docs/ROADMAP.md`](docs/ROADMAP.md). Whether you are a human or an autonomous agent, the
workflow is the same.

## Picking up work

1. Choose the **lowest-numbered open `feat` issue** in the current milestone whose `Depends on`
   are all closed. Build in milestone order (`M0` → `M9`).
2. Read the issue in full — every issue is self-contained: **Context · Scope · Out of scope ·
   Acceptance criteria · Implementation notes · Testing**.
3. Read [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and
   [`docs/design-decisions.md`](docs/design-decisions.md) first. The **cross-cutting invariants**
   (ARCHITECTURE §7) are binding.

## Branching & commits

- Branch from `main`: `feat/<issue-number>-<short-slug>` (e.g. `feat/4-postgres-schema`).
- [Conventional Commits](https://www.conventionalcommits.org): `feat(db): add pages table`,
  `test(hooks): cover spool drain`, `docs: …`, `chore: …`, `fix: …`.
- Reference the issue in the body: `Refs #4` (or `Closes #4` on the final commit).

## Definition of Done

An issue is done when **all** of its acceptance criteria are checked **and**:

- Tests cover the new behaviour and pass in CI (Go `go test ./...`; server `./gradlew test`).
- Lint passes (`golangci-lint run`; server static analysis / checkstyle if configured).
- Public behaviour is documented (README / `docs/` updated if the surface changed).
- No invariant from ARCHITECTURE §7 is violated.
- The change builds and runs against the docker-compose dev stack.

## Local dev (target)

```bash
# server + postgres
docker compose -f docker/compose.yml up -d

# client
cd client && go build ./... && go test ./...

# server
cd server && ./gradlew build
```

> The compose stack, Gradle wrapper and Go module are created by the **M0** scaffolding issue
> (#1). Until then this section is the target shape, not yet runnable.

## A note on the LLM requirement

The server **requires** a configured LLM provider (see DD-005). When implementing or testing
LLM-touching flows, gate real provider calls behind config and provide a deterministic test
double; never hard-code provider keys. All LLM calls must use structured-JSON output.
