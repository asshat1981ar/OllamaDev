# Feature Spec: "Awaiting Approval" Agent Status

Tier 2 · Backlog ref: `agent-os/backlog.md` #4 · PRD ref: FR15

## Problem

`AgentMetrics.status` (`AgentStateStore.kt`) is free text with only two
visual states in `AgentScreen.kt` — active (green, showing the status
string) and idle (gray). When an agent's action is blocked on
`PendingApprovalStore`, the only signal is the modal dialog; if the user is
on another tab, the agent roster shows nothing unusual.

## Design decision required before implementing

`executeAgenticGitCommand`/`executeAgenticMcpCall` only receive
`agentName: String`, not an `agentId: Int` — `AgentStateStore.setAgentActive`
requires an id. Two options, pick one explicitly:
1. **DB lookup by name** inside the gate-wiring code
   (`db.agentDao().getAllAgents()` then match by name) — simplest, one
   extra query per gated call, acceptable given gates are already rare/slow
   (human-latency-bound).
2. **Thread an `agentId` through the call chain** from
   `runAgenticLoopWorkflow` down into `parseAndExecuteAgenticActions` →
   `executeAgenticGitCommand`/`executeAgenticMcpCall` — no extra query, but
   touches every call site of those functions across all 5 coordination
   modes (they currently only pass `agentName`).

Option 1 is recommended: smaller diff, doesn't touch the other 4
coordination modes' call sites, and the query cost is negligible relative
to human approval latency.

## Approach

1. Before calling `PendingApprovalStore.requestApproval`/
   `requestFileChangeReview`, resolve the agent id (option 1 above) and call
   `AgentStateStore.setAgentActive(agentId, true, "Awaiting Approval")`.
2. After the gate resolves (approved or rejected), restore the previous
   status the same way the rest of the loop already does
   (`AgentStateStore.setAgentActive(agentId, false, "Idle")` or back to
   whatever the surrounding step sets next).
3. In `AgentScreen.kt`'s agent-card status rendering, add a third visual
   state for `status == "Awaiting Approval"` using `#FF9800` (documented
   recurring warning color, `CLAUDE.md` §1) instead of the current
   binary active(green)/idle(gray) logic.

## Acceptance criteria

- [ ] While a `PendingApproval` or `PendingFileChange` is pending, the
      requesting agent's card shows a distinct "Awaiting Approval" badge,
      not the generic green "active" treatment.
- [ ] The badge clears correctly on both approve and reject paths.
- [ ] No agentId threading through the other 4 coordination modes'
      unrelated call sites (confirms option 1 was used, per the
      recommendation above, unless option 2 was deliberately chosen
      instead).

## Files touched

- `app/src/main/java/com/example/data/SwarmEngine.kt` (gate wiring sites)
- `app/src/main/java/com/example/ui/AgentScreen.kt` (status badge rendering)

## Standards to follow

- `CLAUDE.md` §1 (reuse `#FF9800`, don't invent a new warning hex).
- `agent-os/standards/state/singleton-store-for-cross-cutting-state.md` —
  `AgentStateStore` is the existing precedent for this exact kind of
  cross-cutting write from `SwarmEngine`.

## Out of scope

- A dedicated "who is this waiting on" indicator beyond the existing
  dialog + badge — no new notification/toast for this.
