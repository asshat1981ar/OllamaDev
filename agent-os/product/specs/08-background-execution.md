# Feature Spec: Background Execution + Notification

Tier 3 · Backlog ref: `agent-os/backlog.md` #8 · highest infra cost on the backlog

## Problem

A long `AGENTIC_LOOP` run (up to 16 iterations × multiple LLM calls) only
progresses while `SessionScreen` (or wherever `executeTask` was launched
from) stays composed and the process stays alive. There's no way to start
a task and leave the app / lock the device and have it keep running, nor
any notification when it finishes or hits an approval gate.

## Approach

1. **Foreground `Service`** hosting the `viewModelScope`-independent
   execution of `SwarmEngine.executeTask` — needs to survive the
   originating screen's lifecycle. Likely means moving task execution
   ownership from a `ViewModel`-scoped coroutine to a `Service`-scoped one,
   or having the `Service` hold its own reference to the shared
   `SwarmEngine`/`AppDatabase` singleton and the ViewModel observes DB
   state reactively (already possible today — `TaskStep`/`SwarmTask` flows
   are DB-backed, not held only in ViewModel memory) rather than needing
   the ViewModel to be the one that launched the coroutine.
2. **Notification channel** for: task started, task completed, and
   critically, **approval required** — since `PendingApprovalStore` blocks
   the whole run until resolved, a background run with a pending approval
   and no notification would silently hang forever from the user's
   perspective.
3. **Runtime notification permission** (API 33+, `POST_NOTIFICATIONS`) —
   request at the point the user first starts a background-capable task,
   not at app launch.
4. Tapping the approval notification should deep-link into the
   approval/file-diff dialog (already global in `MainActivity`, so this
   may be as simple as bringing the activity to the foreground — the
   dialogs already render based on `PendingApprovalStore` state
   regardless of which tab is active).

## Acceptance criteria

- [ ] Starting an `AGENTIC_LOOP` task and backgrounding the app (or
      locking the device) does not stop the run.
- [ ] A pending approval mid-run posts a notification while backgrounded.
- [ ] Task completion posts a notification with a summary (reuse the
      existing `FINAL_RESPONSE` step content).
- [ ] Notification permission is requested contextually, not at cold start.

## Files touched

- New: a `Service` class (foreground service) for task execution
- `app/src/main/AndroidManifest.xml` (service declaration, notification
  permission)
- `app/src/main/java/com/example/viewmodel/SwarmViewModel.kt` (how task
  launch is triggered/owned)
- Possibly `app/src/main/java/com/example/ui/PermissionHandler.kt` (extend
  with notification-permission request, following its existing pattern —
  note the SDK-branching bug fixed there this session as a cautionary
  example of what to get right)

## Standards to follow

- `CLAUDE.md` — this is the first background-service pattern in the
  codebase; there's no existing convention to follow for the service
  itself, so document the pattern chosen (propose a new standard under
  `agent-os/standards/` once shipped, per the `/discover-standards`
  workflow already used elsewhere in this project).

## Out of scope

- Running multiple background tasks concurrently — v1 assumes one
  in-flight task at a time, matching the current single-`swarmEngine`
  instance-per-ViewModel assumption.
- Wear OS / other surfaces for the notification — phone/tablet
  notification only.
