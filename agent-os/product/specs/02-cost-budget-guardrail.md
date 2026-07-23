# Feature Spec: Cost/Budget Guardrail for Cloud-Preferred Routing

Tier 1 · Backlog ref: `agent-os/backlog.md` #2 · PRD ref: FR13 (risk: cost)

## Problem

`preferCloud = true` routes every plan/act/verify/synthesis/file-content
call in an `AGENTIC_LOOP` run to the (paid) Ollama Cloud Gateway node when
online. A single run can make up to `16 iterations × 2 calls` (act +
verify) plus plan/synthesis/file-content calls — with zero cost visibility
and no cap. This is real-money risk introduced by this session's work, not
a nice-to-have.

## Approach

1. **Estimate.** Reuse the existing token-approximation pattern already in
   `SwarmEngine` (`(prompt.length + output.length) / 2 + 100`, see
   `AgentStateStore.recordExecutionMetrics` call sites) to accumulate a
   running per-task token estimate across all `preferCloud` calls in a run.
2. **Surface it.** Add a running cost/token counter visible during
   execution — either a new `TaskStep`-adjacent field or a `SwarmTask`
   field surfaced in the UI (`SessionScreen`/`ManageScreen`, wherever task
   progress is already shown).
3. **Cap it.** Add a configurable soft cap (e.g. a `SwarmConfig` field or a
   global setting) — when exceeded mid-loop, either (a) stop preferring
   cloud for the rest of the run (fall back to local pool) or (b) pause and
   ask the user to continue, mirroring the existing approval-gate UX
   pattern. Decide (a) vs (b) explicitly before implementing — (a) is
   cheaper to build and non-blocking; (b) is more conservative and reuses
   `PendingApprovalStore`.

## Acceptance criteria

- [ ] A running estimate is visible during an in-progress `AGENTIC_LOOP`
      task, not only after completion.
- [ ] Exceeding the cap has a defined, tested behavior (whichever of (a)/(b)
      is chosen) — not a silent no-op.
- [ ] The cap is configurable, not hardcoded (even a single global setting
      is acceptable for v1 — per-`SwarmConfig` caps are a stretch goal).

## Files touched

- `app/src/main/java/com/example/data/SwarmEngine.kt` (accumulate estimate, check cap)
- `app/src/main/java/com/example/data/Entities.kt` (if a persisted cap/estimate field is added)
- `app/src/main/java/com/example/viewmodel/SwarmViewModel.kt` (surface state)
- UI: wherever task progress renders (`SessionScreen.kt` or similar)

## Standards to follow

- `agent-os/standards/backend/swarm-engine-llm-pool.md` — this is the
  single entry point (`generateFromFallbackPool`) all estimate accumulation
  should hook into, not scattered per-call-site tracking.
- `agent-os/standards/state/sharing-started-strategy.md` — if exposing a
  new StateFlow for the running estimate.

## Out of scope

- Real dollar-cost tracking (Ollama Cloud pricing isn't modeled anywhere in
  this app) — token-count estimate only, same fidelity as existing metrics.
- Per-agent or per-model budget breakdowns — one running total per task is
  sufficient for v1.
