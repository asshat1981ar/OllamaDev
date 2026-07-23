# Feature Spec: In-App Task Analytics

Tier 3 · Backlog ref: `agent-os/backlog.md` #7

## Problem

`SwarmTask.executionTimeMs` and `SwarmTask.tokenUsage` are recorded on
every task completion (`SwarmEngine.executeTask`) but nothing in the app
aggregates or displays them. There's no way to answer "is the harness
actually working" (success rate, `[UNRESOLVED]` frequency, cost trend)
without manually reading through task history one at a time.

## Approach

1. New flat screen `app/src/main/java/com/example/ui/AnalyticsScreen.kt`
   (or fold into `ManageScreen.kt` as a new tab/section — decide based on
   how crowded `ManageScreen` already is; prefer a new top-level screen if
   it would otherwise overflow).
2. New `SwarmViewModel` state: aggregate queries over `SwarmTaskDao`/
   `TaskStepDao` — e.g. per-`SwarmConfig` average `executionTimeMs`/
   `tokenUsage`, and `[UNRESOLVED]` rate computed by scanning `PLAN` step
   content for the literal marker (matches the existing string-based
   convention, no schema change needed).
3. Wire into `MainActivity.kt`'s `when (activeTab)` per the existing
   flat-screen/no-`NavHost` convention.
4. Simple visualizations: a sparkline or bar-per-config for
   time/cost/success-rate — reuse whatever charting approach (if any)
   already exists in this codebase; if none exists, keep it to simple
   Compose-drawn bars/text stats rather than pulling in a new charting
   dependency for a first version.

## Acceptance criteria

- [ ] A new screen shows, per `SwarmConfig`, at minimum: task count,
      average execution time, average token usage, and `[UNRESOLVED]` rate
      (for `AGENTIC_LOOP` configs specifically).
- [ ] Data updates live as new tasks complete (StateFlow-driven, not a
      manual refresh button, per existing app conventions).
- [ ] No new charting library dependency unless justified — check
      `gradle/libs.versions.toml` for anything already available first.

## Files touched

- New: `app/src/main/java/com/example/ui/AnalyticsScreen.kt` (or extends `ManageScreen.kt`)
- `app/src/main/java/com/example/viewmodel/SwarmViewModel.kt` (aggregate state)
- `app/src/main/java/com/example/data/Daos.kt` (new aggregate queries if needed)
- `app/src/main/java/com/example/MainActivity.kt` (tab wiring, if new top-level screen)

## Standards to follow

- `agent-os/standards/state/sharing-started-strategy.md` — new DB-backed
  aggregate flows should use `WhileSubscribed(5000)`.
- `CLAUDE.md` §2 (screen conventions), §7 (flat `ui/` structure).

## Out of scope

- Cross-device/exported analytics — everything stays local to this Room
  database, no telemetry pipeline (this app has none).
- Historical data backfill — only tasks completed after this feature ships
  need to populate cleanly; don't attempt to reconstruct trends from
  pre-existing `SwarmTask` rows beyond what their existing columns already
  capture.
