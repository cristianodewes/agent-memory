# Auto-improve loop (issue #30)

The auto-improve loop turns a finished session into durable-memory edits, out of band. It runs on its own
cadence — **never** on a hook / ingest / admission path — so capture and recall stay fast and a slow or
failing review can never block or corrupt capture. It is **off by default**.

```
sessions ─▶ AutoImproveScheduler ─▶ ProposalSource ─▶ AutoImproveGate ─▶ EvalGate ─▶ pending_writes
 (finished)    (watermark + claims)   (#29 curator)     (approval gate)    (#31, off)  (proposed→applied/rejected)
                                                                                │
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
2. **ProposalSource** (the review engine) — turns a project's state into `ProposedWrite`s. Production is
   the **#29 curator adapter** (`CuratorProposalSource`): it renders the rule-based curator's findings into
   the canonical `_lint/report.md` page (mirroring `memory_lint`) and stays **quiescent** when that report
   is unchanged. The seam is an interface, so unit tests substitute a fake; if no source is wired the
   scheduler logs and returns **before** claiming anything, never burning a session against an empty engine.
   (Turning findings into corrective *actions* — forget/fix — is the scope-level **curator-action loop**
   below, issue #101.)
3. **Approval gate** (`AutoImproveGate`) — records every proposal in `pending_writes`, then **gates every
   apply through the #31 [eval gate](./eval-gate.md)**: a `BLOCKED` verdict rejects the proposal
   (fail-closed, never applied); `PASSED`/`SKIPPED` proceed, and the verdict is recorded in `eval_result`.
   Applying goes through the **`ProposalApplier`** seam (production: the #19/#20 `MemoryWriteService` —
   atomic page write + git commit + audit). The eval gate is **off by default**, so by default every
   proposal is `SKIPPED`.

## Approval state machine (`pending_writes.status`)

The proposal store is the pre-existing `pending_writes` table (V8); V14 adds only the scheduler state and
a `session_id` link. Status values match the V8 `CHECK`:

| from       | event                                            | to         |
|------------|--------------------------------------------------|------------|
| —          | submit, `require-approval=false`, eval allows     | `applied`  |
| —          | submit, eval **blocks**                           | `rejected` |
| —          | submit, `require-approval=true`                   | `proposed` |
| `proposed` | `approve`, eval allows                            | `applied`  |
| `proposed` | `approve`, eval **blocks**                         | `rejected` |
| `proposed` | `reject`                                          | `rejected` |

`applied` writes through the normal durable path; `rejected` leaves memory untouched. Only a `proposed`
row can be approved/rejected (a second decision is a conflict, not a silent re-apply). The [eval-gate
(#31)](./eval-gate.md) runs immediately before every apply; its verdict is recorded in the row's
`eval_result` column whenever the gate actually ran (`null` when `SKIPPED` — the default off state).

## Curator corrective actions (issue #101)

The #30 loop above is **report-shaped**: the curator source renders findings into a `_lint/report.md`
page (Curator-*content*, #100). The **curator-action loop** is the **action-shaped** progression — it
turns each actionable finding into a real corrective edit, on its own scope-level cadence:

```
pages ─▶ CuratorActionScheduler ─▶ CuratorActionProposalSource ─▶ AutoImproveGate ─▶ EvalGate ─▶ pending_writes
(scope)    (per-project tick)       (#29 finding → action)         (approval gate)   (#31, off)   │
                                                                                                   ▼
                                                            DispatchingProposalApplier (forget / link.fix / content)
```

- **Scope-level cadence.** `CuratorActionScheduler` is a separate off-by-default timer. Unlike the
  per-finished-**session** `AutoImproveScheduler`, its unit of work is a **project (scope)**: each tick
  audits every project that has pages. It stays quiescent by skipping a finding whose action is already
  `proposed`/`rejected`; an *applied* forget/link-fix removes the finding itself, so it cannot recur.
- **Per-finding granularity.** `CuratorActionProposalSource` maps each actionable finding to its own
  `ProposedWrite` (vs. #100's single scope-level report):

  | curator rule (#29)        | action kind   | applier effect                                          |
  |---------------------------|---------------|---------------------------------------------------------|
  | `COLD_EPISODIC`           | `page.forget` | soft-delete the cold page via the forget sweep (#25).   |
  | `DANGLING_CROSS_PROJECT`  | `link.fix`    | prune the broken `[[link]]` from the source body (#27). |
  | `DUPLICATE_TITLE`         | `page.merge`  | **deferred follow-up** — no action emitted yet.         |
  | `STALE_SLOT`              | `slot.refresh`| **deferred follow-up** — no action emitted yet.         |

- **Action-capable model.** `pending_writes.kind` goes beyond the content upsert: the machine-readable
  action arguments (e.g. `link.fix`'s dangling `target` as `workspace/project/path`) ride in the
  `proposal` JSON's `params`, and `path` is the affected page. **V15** promotes `kind` to a schema
  allowlist (`page.edit`, `page.forget`, `link.fix`) — kept in lockstep with `ProposalKinds`; a deferred
  kind is added only when its source + applier land (nothing dormant).
- **Appliers behind the seam.** The production `ProposalApplier` is a `DispatchingProposalApplier` that
  routes on `kind`; content `page.edit` still applies through `MemoryWriteService` unchanged. An unknown
  kind **fails closed** (throws, leaving the row recoverable), belt-and-braces with the V15 CHECK.
- **Eval-gated like everything else.** Every action runs through the same `AutoImproveGate` → `EvalGate`,
  fail-closed (a `BLOCKED` verdict rejects it, recorded in `eval_result`); the action's affected `path`
  drives the gate's prefix selection. Off by default via `curator-actions.enabled` (see below).

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
      enabled: false              # master switch for the out-of-band (per-session) timer (default: off)
      interval: 15m               # delay between non-overlapping ticks (> 0)
    curator-actions:              # the scope-level curator corrective-action loop (#101)
      enabled: false              # master switch (default: off) — forget/link-fix actions never fire
      interval: 60m               # delay between non-overlapping scope-review ticks (> 0)
      max-scopes-per-tick: 50     # bound on a single tick's work
    eval:                         # the optional eval gate — see contracts/eval-gate.md
      enabled: false
```

The `[auto_improve]` namespace is the shared umbrella; `[auto_improve.eval]` (#31) binds to its own
properties type and nests under it. The loop is fully wired (curator source + eval gate) but **off by
default**: nothing runs until `scheduler.enabled=true`, and the eval gate stays `SKIPPED` until
`eval.enabled=true` with a configured `command` (see [eval-gate.md](./eval-gate.md)).
