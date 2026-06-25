# Auto-improve loop (issue #30)

The auto-improve loop turns a finished session into durable-memory edits, out of band. It runs on its own
cadence — **never** on a hook / ingest / admission path — so capture and recall stay fast and a slow or
failing review can never block or corrupt capture. It is **off by default**.

```
sessions ──▶ AutoImproveScheduler ──▶ ProposalSource ──▶ AutoImproveGate ──▶ pending_writes
 (finished)     (watermark + claims)    (review engine)    (approval gate)     (proposed → applied/rejected)
                                          [deferred #29/#19]                    │
                                                                ProposalApplier ▼  (apply = #19/#20 write path)
```

## Stages

1. **Scheduler** (`AutoImproveScheduler`) — a single non-overlapping timer. Each tick establishes a
   per-project **watermark**, finds freshly-finished sessions **due** for review, and runs each under a
   per-session **claim**. Idempotent and resumable.
   - **Watermark** (`auto_improve_watermark`) — established once per project at first sight (`now()`).
     Sessions finished at/before it are **not** retro-reviewed, so enabling the loop on an existing
     project doesn't review its whole history.
   - **Claim** (`auto_improve_session_review`) — a per-session row (`PRIMARY KEY (session_id)`) that
     de-dupes overlapping ticks, records the outcome (`claimed → done | failed`), and caps retries via
     `attempts` so a permanently-failing session stops being fed.
2. **ProposalSource** (the review engine) — turns a session into `ProposedWrite`s. This is the **only**
   coupling to the review machinery and is **deferred**: this build ships no production binding (it will
   be the #29 curator and/or #19 consolidation). With no source wired the scheduler logs and returns
   **before** claiming anything — it never burns sessions against an empty engine.
3. **Approval gate** (`AutoImproveGate`) — records every proposal in `pending_writes` and decides its
   fate (below). Applying goes through the **`ProposalApplier`** seam (production: the #19/#20
   `MemoryWriteService` — atomic page write + git commit + audit).

## Approval state machine (`pending_writes.status`)

The proposal store is the pre-existing `pending_writes` table (V8); V14 adds only the scheduler state and
a `session_id` link. Status values match the V8 `CHECK`:

| from       | event                                          | to         |
|------------|------------------------------------------------|------------|
| —          | submit, `require-approval=false` (default)     | `applied`  |
| —          | submit, `require-approval=true`                | `proposed` |
| `proposed` | `memory_auto_improve action=approve`           | `applied`  |
| `proposed` | `memory_auto_improve action=reject`            | `rejected` |

`applied` writes through the normal durable path; `rejected` leaves memory untouched. Only a `proposed`
row can be approved/rejected (a second decision is a conflict, not a silent re-apply). The optional
[eval-gate (#31)](./eval-gate.md) verdict lands in the row's `eval_result` column; that wiring is added
when the proposal/eval engines are integrated (this build leaves it null).

## `memory_auto_improve` MCP tool

The human surface over the gate. Not read-only (approve applies a write).

| arg         | actions          | notes                                                        |
|-------------|------------------|--------------------------------------------------------------|
| `action`    | all              | `report` (default), `approve`, `reject`.                     |
| `id`        | approve, reject  | proposal id (required for those).                            |
| `limit`     | report           | max proposals (default 20, max 100).                         |
| `workspace` / `project` | report | scope; defaults to the most recently active project.    |

`report` returns recent proposals and their status; `approve`/`reject` act on a held proposal by id (it
already carries its own scope).

## Configuration (`agent-memory.auto-improve`)

```yaml
agent-memory:
  auto-improve:
    require-approval: false       # false (default): proposals apply immediately; true: hold as 'proposed'
    max-attempts: 3               # per-session review attempt cap (a failing session stops retrying)
    max-sessions-per-tick: 20     # bound on a single tick's work
    scheduler:
      enabled: false              # master switch for the out-of-band timer (default: off)
      interval: 15m               # delay between non-overlapping ticks (> 0)
    eval:                         # the optional eval gate — see contracts/eval-gate.md
      enabled: false
```

The `[auto_improve]` namespace is the shared umbrella; `[auto_improve.eval]` (#31) binds to its own
properties type and nests under it. Even with `scheduler.enabled=true`, the loop is inert until a
`ProposalSource` is wired (#29/#19).
