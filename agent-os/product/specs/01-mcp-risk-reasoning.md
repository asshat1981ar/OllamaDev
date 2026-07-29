# Feature Spec: Surface MCP Risk Reasoning

Tier 1 · Backlog ref: `agent-os/backlog.md` #1 · PRD ref: FR12

## Problem

`isRiskyMcpCall()` (`SwarmEngine.kt`) silently decides whether an MCP call
is destructive — via a real tool annotation (`destructiveHint`) or a
keyword match against the skill/tool name+description. That reasoning is
computed and then discarded; the approval dialog the user sees only shows
`PendingApproval.description` ("Call 'X' (tool) on server"), never *why*
it was flagged. A gated call currently looks arbitrary.

## Approach

`PendingApproval.detail` already exists and is currently used for the raw
`argsJson`. Extend `isRiskyMcpCall` to return the matched reason alongside
the boolean (e.g. a sealed result or a nullable reason string), and prepend
it to `detail` when calling `PendingApprovalStore.requestApproval` from
`executeAgenticMcpCall`. Render it in the `MainActivity.kt` approval
dialog's existing `detail.isNotBlank()` block.

Suggested reason strings:
- `"Flagged by tool: destructiveHint=true"` (annotation-based)
- `"Flagged by keyword: contains 'deploy'"` (keyword-based, name the
  matched keyword specifically, not just "keyword match")

## Acceptance criteria

- [ ] A risky MCP call's approval dialog shows the specific reason it was
      flagged, distinguishing annotation-based from keyword-based.
- [ ] A `git push` approval (which has no `isRiskyMcpCall` check — it's
      always risky) is unaffected; its `detail` stays as-is or gets a
      simple "local git safety gate" note for consistency, at author's
      discretion.
- [ ] Existing `SwarmEngineApprovalGateTest` assertions on `PendingApproval`
      fields still pass (extend rather than replace).

## Files touched

- `app/src/main/java/com/example/data/SwarmEngine.kt` (`isRiskyMcpCall`, `executeAgenticMcpCall`)
- `app/src/main/java/com/example/MainActivity.kt` (approval dialog rendering)

## Standards to follow

- `agent-os/standards/mcp/registry-type-inference.md` — same keyword-inference
  precedent already established for `inferServerType()`.

## Out of scope

- Changing the risk-classification logic itself (keywords list, annotation
  precedence) — this spec only makes the *existing* decision visible.
