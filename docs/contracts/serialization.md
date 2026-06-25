# Domain serialization contract

This is the normative wire contract for the **agent-memory** domain vocabulary (issue #3): the
typed 3-tuple identity `(workspace, project, path)`, the surrogate ids, the canonical
`ObservationKind` enum, and the `Page` / `Observation` / `Session` / `Link` / `Handoff` records.
It is the single source of truth both implementations are written against:

- **Server (Java):** `com.agentmemory.core` (Spring Boot, Jackson 3). No IO / framework deps —
  only Jackson annotations pin the shape.
- **Client (Go):** `client/internal/core`. No third-party deps — UUIDv7 and JSON tags are
  hand-rolled to match.

The golden fixtures under [`fixtures/`](fixtures/) are round-tripped by **both** languages
(`JsonRoundTripTest` on the JVM, `jsonroundtrip_test.go` on the Go side). If a change breaks the
contract, those tests fail in at least one language. Change the fixtures and both mirrors together.

> Scope note: this issue (#3) defines the domain shapes only. The hook-payload envelope (#7) and
> the persistence mapping (#4) build on these types but are specified in their own issues. The
> client-alias → canonical `ObservationKind` mapping is #7; only the canonical set lives here.

---

## 1. Conventions

- **Encoding:** UTF-8 JSON.
- **Field casing:** `lowerCamelCase` for every object field (`isLatest`, `sessionId`, `createdAt`,
  `fromSession`, `openQuestions`, `targetResolved`, …).
- **Field order:** records serialize in the order given in §4 (Java `@JsonPropertyOrder`, Go struct
  field order). Consumers MUST NOT depend on order — both round-trip suites compare **semantically**
  (object key order ignored) — but producers emit this order for stable, reviewable fixtures.
- **Null semantics:** **absent ⇔ null.** Optional fields are *omitted* when unset, never emitted as
  `"field": null`. Java uses `@JsonInclude(NON_NULL)`; Go uses pointer fields with `omitempty`.
  Deserializers MUST treat a missing optional field and an explicit `null` identically.
- **Empty list semantics:** list fields that are *always present* (the two `Handoff` lists) serialize
  as `[]` when empty — **never** omitted and **never** `null`. (Java coalesces a null list to empty;
  Go declares them without `omitempty`.)
- **Unknown object fields:** ignored on read (forward-compatible). Producers emit only the fields
  below.

## 2. Value types (serialize as bare JSON scalars)

The identity coordinates and the surrogate ids are **typed wrappers**, not loose strings/objects.
On the wire each is a single JSON scalar (via Java `@JsonValue`/`@JsonCreator` and Go
`MarshalJSON`/`UnmarshalJSON`):

| Type | Wire form | Normalization on construct/parse |
|---|---|---|
| `WorkspaceId` | string | trim → reject blank / `/` `\` NUL → ASCII-lowercase |
| `ProjectId`   | string | same as `WorkspaceId` |
| `PagePath`    | string | full path normalization, see §5 |
| `SessionId`, `ObservationId`, `PageId`, `LinkId`, `HandoffId` | string | canonical lowercase `8-4-4-4-12` UUID (see §3) |

`WorkspaceId` / `ProjectId` are **case-insensitive single segments** (one directory name). They are
lower-cased so `ACME` and `acme` are the same workspace; a value containing a path separator or NUL
is rejected.

## 3. ID strategy — UUIDv7

All surrogate ids are **UUID version 7** (RFC 9562 §5.7): a 48-bit Unix-millisecond timestamp in the
high bits, then the version/variant bits, then 74 random bits.

- **Why v7:** ids are *k-sortable* (sort by creation time), which keeps the append-heavy
  `sessions` / `observations` / `pages` indexes friendly without a separate ordering column
  (ARCHITECTURE §4.2). The leading-timestamp property is asserted in both test suites.
- **Wire form:** the canonical lowercase hyphenated string, e.g.
  `018f9c2a-7b3e-7c00-8a1d-2b6f4e9c1a55` (identical to `java.util.UUID.toString()`).
- **Distinct types:** `SessionId` ≠ `ObservationId` ≠ `PageId` ≠ `LinkId` ≠ `HandoffId` at the type
  level in both languages, even though all are UUIDs — they cannot be interchanged at a call site.
- **`PageId`** is a surrogate key for one *version* of a page (the `is_latest` / `supersedes`
  chain needs a per-version key). A page's human identity is still its `PagePath` within a project.
- Generation lives in the domain (`Uuid7` in Java, `uuid.go` in Go); neither pulls a UUID library.

## 4. The 3-tuple identity and the records

### 4.1 `Identity` — `(workspace, project, path)`

The typed tuple required on **every** domain record (ARCHITECTURE invariant #4). Serializes as a
nested object:

```json
{ "workspace": "acme", "project": "agent-memory", "path": "concepts/recall.md" }
```

- `workspace`, `project` — always present.
- `path` — present for **page-scoped** rows (`Page`, page-bound `Link` endpoints); **omitted** for
  **project-scoped** rows (`Session`, `Observation`, `Handoff`). Absent `path` ⇒ the identity names
  a project, not a page.

### 4.2 Records

Field name · JSON type · presence. Identity-bearing fields are always the `identity` object above
(except `Link`, which carries `source`/`target` identities).

**`Page`** — page-scoped.

| Field | Type | Presence |
|---|---|---|
| `id` | `PageId` string | required |
| `identity` | object | required (page-scoped: has `path`) |
| `title` | string | required |
| `body` | string | required (may be empty) |
| `isLatest` | boolean | required |
| `supersedes` | `PageId` string | optional (omitted for the first version) |
| `createdAt` | timestamp | required |
| `updatedAt` | timestamp | required |

**`Observation`** — project-scoped.

| Field | Type | Presence |
|---|---|---|
| `id` | `ObservationId` string | required |
| `sessionId` | `SessionId` string | required |
| `identity` | object | required (project-scoped: no `path`) |
| `kind` | `ObservationKind` string | required (see §6) |
| `sourceEvent` | string | optional (raw agent-native event name pre-canonicalization) |
| `extension` | string | optional (third-party namespace, ARCHITECTURE §5.4) |
| `payload` | string | required (may be empty; **unsanitized at this layer** — DD-010) |
| `createdAt` | timestamp | required |

**`Session`** — project-scoped.

| Field | Type | Presence |
|---|---|---|
| `id` | `SessionId` string | required |
| `identity` | object | required (project-scoped) |
| `agent` | string | optional (e.g. `claude-code`) |
| `startedAt` | timestamp | required |
| `endedAt` | timestamp | optional (omitted while the session is open) |

**`Link`** — directed wikilink between page-scoped identities.

| Field | Type | Presence |
|---|---|---|
| `id` | `LinkId` string | required |
| `source` | object | required (page-scoped) |
| `target` | object | optional (page-scoped; may be a **different** `(workspace, project)` — cross-project; omitted for a bare anchor) |
| `anchor` | string | optional (link text/token as written) |
| `targetResolved` | boolean | required (`false` for a deferred/forward link to a not-yet-existing page; cannot be `true` without a `target`) |

**`Handoff`** — project-scoped.

| Field | Type | Presence |
|---|---|---|
| `id` | `HandoffId` string | required |
| `identity` | object | required (project-scoped) |
| `fromSession` | `SessionId` string | required |
| `status` | `HandoffStatus` string | required (`open` \| `accepted` \| `expired`) |
| `summary` | string | required (may be empty) |
| `openQuestions` | string array | **always present** (`[]` when empty) |
| `nextSteps` | string array | **always present** (`[]` when empty) |
| `createdAt` | timestamp | required |
| `acceptedAt` | timestamp | optional (omitted while `open`) |

### 4.3 Timestamps

All timestamp fields are **ISO-8601 / RFC 3339 instants in UTC** with a trailing `Z`, e.g.
`2026-06-25T12:00:00Z`. Fractional seconds are permitted but the fixtures use whole seconds so the
Java (`Instant`) and Go (`time.Time`) encoders agree byte-for-byte. Consumers MUST accept a
fractional component.

## 5. `PagePath` normalization

Two spellings of the same page MUST collapse to one byte-identical key, or the page indexes twice.
The normal form (applied identically by `PathNormalizer` in Java and `normalizePagePath` in Go):

1. Backslashes `\` → forward slashes `/` (Windows clients spell paths natively).
2. Runs of slashes collapse to a single `/`.
3. A leading `/` or `./` is stripped — paths are **relative to the project root**.
4. Each segment is trimmed of surrounding whitespace; empty and `.` segments drop out.
5. `..` segments are **rejected** — paths never escape the project root (no traversal).
6. ASCII `A–Z` is lower-cased (case-insensitive identity); non-ASCII is left untouched (no Unicode
   case folding — the two languages do not fold identically).
7. Exactly one `.md` suffix is ensured (case-insensitive: a trailing `.MD` counts as present and is
   lower-cased).

A path that is null, blank, contains NUL, contains a `..` segment, or normalizes to empty (e.g.
`"/"`) is rejected. Examples (`raw → normal`):

| raw | normal |
|---|---|
| `concepts\recall.md` | `concepts/recall.md` |
| `/concepts//recall` | `concepts/recall.md` |
| `Concepts/Recall.MD` | `concepts/recall.md` |
| `./a/./b/c` | `a/b/c.md` |
| `../etc/passwd` | *rejected* |

## 6. `ObservationKind` (canonical set)

Kebab-case tokens; the canonical set, in order:

```
session-start  user-prompt  pre-tool-use  post-tool-use
pre-compact    notification  stop          session-end   other
```

- **Lenient parse:** unknown / blank tokens deserialize to `other` (a newer client emitting a kind
  this build doesn't know never breaks ingest). Matching is case-insensitive and treats `_` as `-`.
- `other` + the `extension` field (§5.4) lets third parties record events without growing the enum.
- The client-alias mapping (an agent's `PreToolUse`, `user-prompt-submit`, … → these canonical
  tokens) is **issue #7**, layered on top of this set.

`HandoffStatus` (`open` / `accepted` / `expired`) parses **strictly** — an unknown status is a data
error and is rejected, not silently coerced.

## 7. Fixtures

[`fixtures/`](fixtures/) holds one golden file per shape (plus minimal/edge variants that exercise
null-omission and empty lists). Each is deserialized → re-serialized → compared semantically to the
original, in both languages. They are the executable form of this document.
