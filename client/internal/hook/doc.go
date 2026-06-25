// Package hook defines the agent lifecycle hook vocabulary and the wire payload the client spools
// and drains to the server (issue #7; ARCHITECTURE §2.1, §5.4).
//
// Two pieces make up #7's surface:
//
//   - ParseEvent (event.go) is the client-alias normalizer: it maps each agent's native event
//     spelling ("PostToolUse", "user-prompt-submit", "post_tool_use", …) onto one canonical
//     core.ObservationKind so no real hook is silently dropped, falling back to core.KindOther
//     (never a panic) for unknown events. The alias table is the extension seam — adding an agent's
//     spelling is a one-line change that cannot break parsing of existing or unknown events.
//
//   - Payload (payload.go) is the wire envelope POSTed to /hook: identity tuple, session id,
//     canonical kind, raw source event, title/body, tool name/input/response and timestamp, plus an
//     extension namespace. ToolResponse is raw JSON so an array-shaped tool response (prior-art
//     "Bug A") survives ingest intact.
//
// It mirrors the Java com.agentmemory.hooks package; the cross-language golden fixtures under
// docs/contracts/fixtures/hook_*.json are round-tripped by both. The sanitizer (#9) and the spool/
// drain (#10) build on these types in their own issues.
package hook
