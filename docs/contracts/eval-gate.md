# Eval-gate contract (`eval-gate/v1`)

The executable eval gate (issue #31) is an **optional, project-supplied external validator** that the
auto-improve loop (#30) runs over a high-stakes proposal — pages under a configured prefix such as
`_rules/` or `procedures/` — **after** LLM validation and **before** the proposal is staged/approved.
It is defense in depth on top of the LLM check, is **off by default**, and is **never** run on a hook
path.

The server invokes the configured command once per applicable proposal, passing the proposal as JSON on
**stdin** and reading the verdict as JSON from **stdout**.

## Input (stdin)

One JSON object:

```json
{
  "version": "eval-gate/v1",
  "action": "upsert",
  "path": "_rules/security.md",
  "title": "Security rules",
  "body": "## Always validate untrusted input\n..."
}
```

| field     | type   | notes                                                            |
|-----------|--------|------------------------------------------------------------------|
| `version` | string | contract version; currently `eval-gate/v1`.                      |
| `action`  | string | the content actions `upsert` (create/update) / `delete`, or a curator corrective-action verb (#101): `page.forget` (soft-delete the page at `path`) / `link.fix` (prune a dangling link from the page at `path`). A gate keyed only on `path` can ignore the verb. |
| `path`    | string | target/affected page path; matched against the configured prefixes. |
| `title`   | string | proposed page title, or a short action label (may be empty).    |
| `body`    | string | proposed page body, or a human description of the action (may be empty). |

## Output (stdout)

One JSON object, with the process exiting `0`:

```json
{ "pass": true, "reasons": ["all checks passed"] }
```

| field     | type           | notes                                                          |
|-----------|----------------|----------------------------------------------------------------|
| `pass`    | boolean        | **required.** `true` approves; `false` rejects the proposal.   |
| `reasons` | array<string>  | optional; surfaced in the verdict / audit trail.               |

## Verdict mapping

| gate result                                              | verdict                |
|---------------------------------------------------------|------------------------|
| exit `0`, `{"pass": true}`                               | **PASSED** (allowed)   |
| exit `0`, `{"pass": false}`                              | **BLOCKED**            |
| disabled, no command, or `path` matches no prefix       | **SKIPPED** (allowed)  |
| timeout, non-zero exit, empty/invalid output, no `pass` | **BLOCKED** (fail-closed) |

Only an explicit `pass: true` lets a proposal through. Every untrustworthy outcome **fails closed**
(blocks), with a reason recorded.

## Execution environment (untrusted command)

The command is treated as untrusted:

- **Scrubbed environment** — the gate runs with a clean environment plus only a minimal infrastructure
  allowlist (`PATH`/`PATHEXT` and OS-loader vars such as `SystemRoot`, plus `TEMP`/`TMP`/`HOME`/`LANG`/`TZ`).
  None of the server's secrets (API keys, tokens) are inherited. Use an **absolute** `command[0]`.
- **Hard timeout** — a single run is bounded by `timeout`; on expiry the process is force-killed and the
  proposal is blocked.
- **Captured, capped output** — stdout/stderr are captured up to `maxOutputBytes`; the excess is drained
  and discarded so the gate cannot deadlock on a full pipe.

## Configuration (`agent-memory.auto-improve.eval`)

```yaml
agent-memory:
  auto-improve:
    eval:
      enabled: false            # master switch (default: off)
      prefixes: ["_rules/", "procedures/"]   # proposal path prefixes the gate applies to
      command: ["/usr/bin/python3", "/opt/gates/rules_gate.py"]   # absolute argv; empty = no gate
      timeout: 10s              # hard per-run budget (> 0)
      max-output-bytes: 65536   # cap on captured stdout/stderr
      working-dir:              # optional; blank inherits the server's
```
