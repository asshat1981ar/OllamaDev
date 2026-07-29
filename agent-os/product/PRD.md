# PRD — Agentic Coding Harness

Status: shipped (PR #1, `7e72f8c`) · this document guides the next phase (see `agent-os/backlog.md`)

## Overview

OllamaDev's `SwarmEngine` runs several fixed multi-agent coordination modes
(sequential, peer-to-peer, consensus-vote, dynamic-routing) plus one
open-ended mode, `AGENTIC_LOOP`, that autonomously plans and works a
checklist — the closest thing in the app to a Replit-Agent/Manus-AI-style
coding assistant. Before this work, `AGENTIC_LOOP` was a thin loop: one
agent, no verification, no real tool execution, no way to actually edit a
workspace file, and no human oversight on risky actions. This PRD covers
what "autonomous coding harness" means as a product surface now that it's
built, and sets requirements for the next phase.

## Problem

A user asks the swarm to "add X to the project." Today (pre-harness) an
`AGENTIC_LOOP` run drafts a checklist and works through it with one agent,
but:
- Nothing verifies a step was actually done — the agent's own claim is the
  only signal.
- The agent cannot durably change a workspace file — there's no write path
  in the loop, only git/MCP directives.
- Every git push or MCP tool call fires immediately, with no human in the
  loop for anything irreversible.
- Progress isn't checkpointed, so a bad run has no undo.

A developer using this on a phone/tablet (no terminal, no IDE) has no way
to safely delegate real work to the swarm.

## Goals

1. **Verified execution.** Every checklist step gets checked by a
   QA-role agent before being marked done, using real MCP tooling when
   available rather than the agent grading its own work.
2. **Durable file changes.** The loop can propose and land real edits to
   `WorkspaceFile` rows, reviewed as a diff before they apply.
3. **Human oversight on risk.** Git pushes and destructive-flagged MCP
   calls pause for explicit approval; everything else stays immediate.
4. **Recoverable progress.** Verified steps checkpoint automatically via
   git commits, with a way to revert if a run goes wrong.
5. **Fits the existing swarm model.** Reuses the Architect/Programmer/QA
   agent roster already seeded in the app — this is a deeper mode of the
   existing swarm concept, not a parallel system.

## Non-goals

- Structured/native tool-calling (the app's only tool-invocation
  mechanism is parsing directives out of freeform LLM text — this PRD
  does not change that).
- On-device code execution — Android cannot run arbitrary code; "real
  execution" always means routing through a Connected MCP server.
- A general-purpose diff/merge editor — the diff view built for this is
  read-review-only (accept whole file / reject whole file), not a patch
  editor.
- Multi-user approval workflows — the approval gate assumes one person
  (the device owner) is the sole approver.

## Users & scenarios

**Primary user:** the OllamaDev app's own user — a developer who wants to
delegate a bounded coding task to the swarm from their phone/tablet and
trust the result without watching every step.

**Key scenarios:**
1. *Happy path* — user runs "Autonomous Coding Harness" swarm config with a
   task; the loop plans, works each step with the right-role agent,
   verifies, checkpoints, and produces a final summary — no interruptions
   needed if nothing risky came up.
2. *Approval interrupt* — mid-run, an agent proposes `git push` or a
   destructive MCP call; the run visibly pauses (global dialog, any
   screen) until the user approves or rejects.
3. *File review* — an agent proposes a new/modified file; the user sees a
   real line diff before it lands.
4. *Verification failure* — a step fails its check, retries up to twice,
   and if still failing is marked `[UNRESOLVED]` in the checklist rather
   than silently claimed done or aborting the whole task.
5. *Recovery* — after a run the user doesn't like, they revert the
   workspace to an earlier checkpoint from the Git panel's commit history.

## Functional requirements

| # | Requirement | Status |
|---|---|---|
| FR1 | Checklist items are tagged/inferred to Architect/Programmer/QA and routed to the matching agent | Shipped |
| FR2 | Every act step is followed by a QA-role verify step | Shipped |
| FR3 | Verify step may invoke real MCP tooling via the existing `MCP_CALL:` directive | Shipped |
| FR4 | A failed verify retries the same step (max 2) before being marked `[UNRESOLVED]` and the loop continues | Shipped |
| FR5 | An agent can propose a file write via `WRITE_FILE:` `<path>`, generated via a dedicated LLM round-trip | Shipped |
| FR6 | Proposed file changes are gated behind a human-reviewed line diff | Shipped |
| FR7 | `git push` and MCP calls flagged risky (annotation or keyword) pause for approval | Shipped |
| FR8 | Approval/file-review dialogs are reachable from any screen, not scoped to one tab | Shipped |
| FR9 | Verified steps auto-checkpoint via git commit, traceable to the originating task | Shipped |
| FR10 | User can revert the workspace to an earlier checkpoint | Shipped |
| FR11 | Loop prefers the Ollama Cloud Gateway node for its own reasoning when online | Shipped |
| FR12 | The gated action's risk reasoning (why it was flagged) is visible to the user, not just the fact that it was | Backlog (Tier 1) |
| FR13 | Runaway cloud-model spend is bounded or made visible before it happens | Backlog (Tier 1) |
| FR14 | New `TaskStep` action types render with distinct icon/color, not plain text | Backlog (Tier 1) |
| FR15 | An agent blocked on approval is visually distinguishable in the agent roster, not just via the dialog | Backlog (Tier 2) |
| FR16 | A task touching multiple files gets one batched review, not N sequential dialogs | Backlog (Tier 2) |

## Success metrics

No analytics pipeline exists in this app (local-only, no telemetry) — treat
these as qualitative acceptance bars, verified via the Robolectric harness
and manual review rather than production metrics:
- An `AGENTIC_LOOP` run against a 3-agent roster (Architect/Programmer/QA)
  demonstrably routes verify steps to the QA agent 100% of the time
  (`SwarmEngineRoleAssignmentTest`).
- A scripted failing verification retries exactly twice then marks
  `[UNRESOLVED]` and the loop continues rather than aborting
  (`SwarmEngineVerifyLoopTest`).
- A risky action visibly blocks (`PendingApprovalStore.pendingApproval`
  non-null) until resolved, in both approve and reject directions
  (`SwarmEngineApprovalGateTest`).
- Full regression suite (`./gradlew testDebugUnitTest`) stays green outside
  of the one documented pre-existing unrelated flake.

## Key risks

- **Cost risk (open).** Cloud-preferred routing has no spend visibility or
  cap yet — see FR13, Tier 1 backlog.
- **Trust risk (open).** Risk-gating reasoning is invisible to the user —
  see FR12, Tier 1 backlog.
- **Reliability of "verification."** The verify phase's failure detection
  is a text-keyword heuristic over whatever an MCP tool reports, not a
  structured pass/fail — no MCP tool in this app declares one today.
  Tier 3 backlog item ("real MCP test-runner integration") is the
  highest-leverage fix for this.
