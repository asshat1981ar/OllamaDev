# Spec 09 — Autonomous SDLC Sprint Workflow Chain

Status: design · feeds `agent-os/backlog.md` Tier 1–3 backlog items

## Problem

The existing `AGENTIC_LOOP` mode works a single flat checklist inside one `SwarmTask`. It
has no concept of SDLC phases — every task is undifferentiated: plan, act, verify, done.
As a result:

- A complex feature request produces one giant checklist where design decisions, code
  changes, and verification steps intermingle with no separation of concerns.
- Context from a "discovery" step at the top of the checklist is gone from the LLM's
  window by the time a "verification" step runs at the bottom.
- There is no mechanism to carry learnings from one feature cycle into the next — each
  run starts cold.
- Coordination modes are picked per-task statically; no workflow chains multiple modes
  across phases of the same feature.

## Solution: SprintOrchestrator + six-phase sprint cycle

A `SprintOrchestrator` class (data layer, owned by `SwarmViewModel`) sequences a fixed
set of sprint phases, each executed as its own `AGENTIC_LOOP` (or peer-to-peer) `SwarmTask`.
It stores a `SprintArtifact` row in Room for every completed phase, then distils each
artifact into a ≤300-token summary that is injected as prior context into the next
sprint's system prompt. The cycle is self-healing: if `VERIFICATION` produces more than
two `[UNRESOLVED]` items, the orchestrator automatically re-queues `IMPLEMENTATION`.

The sprint chain closes on itself: `RETROSPECTIVE` writes an updated backlog and hands it
to the next cycle's `DISCOVERY` sprint as seed context, so every cycle inherits the
synthesised learnings of the one before.

## Six sprint phases

| # | Phase | Coordination mode | Lead role(s) | Artifact produced |
|---|---|---|---|---|
| 0 | DISCOVERY | AGENTIC_LOOP | Architect | `sprint-N-discovery.md` |
| 1 | DESIGN | PEER_TO_PEER | Architect + Programmer | `sprint-N-design.md` |
| 2 | IMPLEMENTATION | AGENTIC_LOOP | Programmer | WRITE_FILE directives → WorkspaceFile rows + git checkpoint |
| 3 | VERIFICATION | AGENTIC_LOOP | QA | `sprint-N-verification.md` |
| 4 | INTEGRATION | CONSENSUS_VOTE | Architect + Programmer + QA | `sprint-N-integration.md` |
| 5 | RETROSPECTIVE | SEQUENTIAL | Architect | Updated `agent-os/backlog.md`, `sprint-N-retro.md` |

Each phase maps to a `SwarmConfig` template injected by `SprintOrchestrator.buildSwarmConfig()`.

## How sprints feed into each other

```
SprintCycle (goal: "add X")
│
├─ Sprint 0: DISCOVERY ──────────────────────────────────┐
│   Output: sprint-0-discovery.md                        │
│   Distilled summary injected into ↓                    │
│                                                         │
├─ Sprint 1: DESIGN ─────────────────────────────────────┤
│   Input:  sprint-0 summary                             │
│   Output: sprint-1-design.md                          │
│   Distilled summary injected into ↓                    │
│                                                         │
├─ Sprint 2: IMPLEMENTATION ─────────────────────────────┤
│   Input:  sprint-0 + sprint-1 summaries               │
│   Output: WorkspaceFile rows, git commit               │
│   Changed-file manifest injected into ↓                │
│                                                         │
├─ Sprint 3: VERIFICATION ───────────────────────────────┤
│   Input:  sprint-0..2 summaries + changed files        │
│   Output: sprint-3-verification.md                     │
│   ┌── if unresolvedCount > 2 ───────────────────────┐  │
│   │   re-queue Sprint 2 with failure context        │  │
│   │   reimplCount++                                 │  │
│   └─────────────────────────────────────────────────┘  │
│                                                         │
├─ Sprint 4: INTEGRATION ────────────────────────────────┤
│   Input:  all prior summaries                          │
│   Output: sprint-4-integration.md, final git checkpoint│
│                                                         │
└─ Sprint 5: RETROSPECTIVE ──────────────────────────────┘
    Input:  all prior summaries
    Output: sprint-5-retro.md, updated backlog.md
    Next cycle Sprint 0 receives: retro summary as seed context
```

### Context distillation mechanism

Between every two phases, `SprintOrchestrator.distilArtifact()` makes a single focused
LLM call (no agent-step UI, no DB row):

```
System: "You are a context distiller. Summarize the following sprint artifact
         in ≤300 tokens. Focus only on: [phase-specific relevance criteria].
         Output only the summary, no preamble."
User:   <full artifact content>
```

The distilled output is stored in `SprintArtifact.distilledSummary` and prepended to
every subsequent sprint's system prompt as:

```
=== Prior Sprint Context ===
[DISCOVERY] <distilledSummary>
[DESIGN] <distilledSummary>
...
===========================
```

This keeps system prompts bounded regardless of artifact size, and keeps the context
phase-appropriate (the IMPLEMENTATION prompt gets a design-focused distillation, not a
raw dump of architecture musings).

## Data model

Two new Room entities:

```kotlin
// FK: SprintCycle.id ← SprintArtifact.cycleId
// FK: SwarmTask.id   ← SprintArtifact.taskId
// FK: WorkspaceFile  ← SprintArtifact.artifactPath (path key, not int FK)

SprintCycle(
    id, goal, status,        // RUNNING | PAUSED | COMPLETED | FAILED
    currentPhase,            // SprintPhase.name
    startedAt, completedAt,
    unresolvedCount,         // cumulative across all VERIFICATION phases
    reimplCount              // number of IMPLEMENTATION re-runs this cycle
)

SprintArtifact(
    id, cycleId, phase, taskId,
    artifactPath,            // WorkspaceFile.filePath for the phase's output doc
    distilledSummary,        // ≤300-token digest used by subsequent phases
    unresolvedItems,         // newline-separated [UNRESOLVED] items from this phase
    gitCommitHash,           // set if this phase produced a git checkpoint
    completedAt
)
```

Migration: `AppDatabase` version bump; `SprintCycleDao` + `SprintArtifactDao` added to
`Daos.kt`; `@Database(entities = [..., SprintCycle::class, SprintArtifact::class])` in
`AppDatabase.kt`.

## SprintOrchestrator API

```kotlin
interface SprintOrchestratorInterface {
    val activeCycle: StateFlow<SprintCycle?>
    val cycleProgress: StateFlow<SprintProgress>  // phase + step label + artifact count
    fun runCycle(goal: String, seedContext: String = "")
    fun pauseCycle()
    fun resumeCycle()
    fun cancelCycle()
}

data class SprintProgress(
    val phase: SprintPhase,
    val phaseLabel: String,
    val completedPhases: Int,
    val totalPhases: Int,
    val currentTaskId: Int?,
    val lastArtifactSummary: String
)
```

`runCycle` launches a coroutine in `viewModelScope` (via SwarmViewModel delegation).
`SwarmViewModel` re-exposes `activeCycle` and `cycleProgress` as `StateFlow`s following
the existing `_backing: MutableStateFlow` / `public: StateFlow via asStateFlow()` pattern.

## SwarmConfig templates per phase

Each phase's `buildSwarmConfig()` call produces a `SwarmConfig` with:
- `coordinationMode` from the table above
- `agentIds` selected by role from the live agent roster (same role-matching logic as
  `SwarmEngine.pickAgentForRole()` — no new matching logic needed)
- `description` = `"[Sprint ${cycle.id} / ${phase.name}] ${cycle.goal}"`

The phase goal prompt (which becomes the `SwarmTask.prompt`) is constructed inline in
`SprintOrchestrator.buildPhasePrompt()` following this template:

```
=== Prior Sprint Context ===
<injected distilled summaries>
===========================

Sprint goal: <cycle.goal>

Your task for the <PHASE> phase:
<phase-specific instructions>

Deliverables:
- WRITE_FILE: sprint-<cycleId>-<phase>.md  ← required final artifact
- <phase-specific extra deliverables>

Constraints:
- Every claim must reference a prior sprint artifact or an existing file.
- Do not re-derive context already summarised above.
- Mark any item you cannot resolve as [UNRESOLVED: reason].
```

## Phase-specific instructions

**DISCOVERY:**
- Enumerate existing files relevant to the goal (via workspace file list from DB)
- Identify gaps between current state and goal requirements
- Produce a prioritised requirement list (REQ-1, REQ-2, …)

**DESIGN:**
- Assign each REQ-N to a component: new file, existing file, or schema change
- Produce interface signatures and data-model changes (not full implementations)
- Flag cross-cutting concerns (DB migration, new StateFlow, new tab entry)

**IMPLEMENTATION:**
- Emit `WRITE_FILE:` directives for each component from DESIGN
- Each file write is gated by `PendingApprovalStore.requestFileChangeReview()` (existing)
- After all files are approved, engine calls `autoCheckpoint` (existing)

**VERIFICATION:**
- For each REQ-N: emit `MCP_CALL:` to invoke a connected test-runner if available,
  else verify by re-reading the written file and checking it satisfies the requirement
- Produce a PASS / FAIL / UNRESOLVED line per requirement
- If `unresolvedCount > 2`: orchestrator re-queues IMPLEMENTATION with the failure list
  as additional context (up to `reimplLimit = 2` total re-runs before INTEGRATION proceeds
  with remaining unresolved items noted)

**INTEGRATION:**
- All three agent roles vote (CONSENSUS_VOTE mode) on whether the combined changes are
  integration-safe: no duplicate symbols, no schema conflicts, no broken imports
- Produce a single `sprint-N-integration.md` with the consensus verdict and any
  remaining concerns

**RETROSPECTIVE:**
- Summarise what was built, what was left unresolved, and what should change in the next
  cycle's approach
- Emit `WRITE_FILE: agent-os/backlog.md` to update the backlog with new items discovered
  during this cycle (goes through existing file-review approval gate)
- Distilled retro summary is stored as the next cycle's seed context

## UI: SprintPlannerScreen

New flat file `ui/SprintPlannerScreen.kt`, wired into the `when (activeTab)` switch in
`MainActivity.kt` as tab "Sprints" with `Icons.Rounded.AutoAwesomeMotion`.

Layout:
- Header: cycle status chip + phase progress stepper (6 steps)
- Goal input field (single `OutlinedTextField`) + "Start Cycle" button
- Current phase card: phase name, active task ID link to `SessionScreen`, step count
- Artifact list: `LazyColumn` of completed `SprintArtifact` rows, each expandable to
  show `distilledSummary`
- Unresolved items section (shown only when `unresolvedCount > 0`): list of
  `[UNRESOLVED]` items colour-coded with `#EF4444` (existing error-red semantic colour)

Phone layout: stacked `Column`. Tablet/foldable (`screenWidthDp >= 600`): `Row` with
phase stepper on left (fixed width), artifact list on right.

State in `SwarmViewModel`: `activeCycle: StateFlow<SprintCycle?>`,
`sprintCycleProgress: StateFlow<SprintProgress>`, `sprintArtifacts: StateFlow<List<SprintArtifact>>`.

## Integration with existing backlog items

| Backlog item | Sprint phase that exercises it |
|---|---|
| FR12 MCP risk reasoning visible | IMPLEMENTATION (risky git push during phase 2) |
| FR13 cost/budget guardrail | All phases (each phase launches preferCloud AGENTIC_LOOP) |
| FR14 TaskStep icon/color | All phases (step timeline in SessionScreen during each sprint) |
| FR15 Awaiting approval badge | IMPLEMENTATION (WRITE_FILE approval gates) |
| FR16 Batched file review | IMPLEMENTATION (multiple WRITE_FILE in one act step) |
| Tier 3: real MCP test runner | VERIFICATION (MCP_CALL: in verify phase) |
| Tier 3: task analytics | SprintPlannerScreen aggregate view across a cycle |
| Tier 3: background execution | Each sprint phase is a candidate for the foreground Service |

## Acceptance criteria

- `SprintOrchestratorTest` (plain-JVM Robolectric): given a 3-agent roster, a full
  6-phase cycle completes in order with 6 `SprintArtifact` rows written to the DB.
- A VERIFICATION phase with 3 `[UNRESOLVED]` items triggers exactly one IMPLEMENTATION
  re-run (not two), confirmed by `reimplCount == 1` on the `SprintCycle` row.
- The RETROSPECTIVE phase's `WRITE_FILE: agent-os/backlog.md` directive routes through
  `PendingApprovalStore` (approval gate exercised, not bypassed).
- Each phase's system prompt contains the distilled summaries of all prior phases
  (verified by capturing the prompt string in the test's fake `LlmRouter`).
- `SprintPlannerScreen` renders without crash under Roborazzi headless screenshot.
