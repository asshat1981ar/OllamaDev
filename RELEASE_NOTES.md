# OllamaDev Release Notes — PRs #7-10

**Date:** 2026-07-29

Merged PRs #7 through #10 add a debug APK CI job, Tier 1/2 trust-and-oversight guardrails, seeded OllamaDev Sandbox tooling, and Tier 3b/c in-app analytics plus background execution. The backlog now marks Tier 1–5 items complete.

---

## What's New

### CI / Build

- **#7 `ci: add debug APK build job`**
  - `.github/workflows/android-ci.yml` gains a `build` job that runs after `test`, calls `./gradlew :app:assembleDebug`, and uploads `app-debug.apk` as the `app-debug-apk` artifact (14-day retention).
  - `watch-ci.sh` is a new local helper that lists open backlog PRs, polls `gh pr checks`, and downloads the latest successful APK artifact via `gh run download`.

### Tier 1 — Trust & Visibility

- **#8 `feat: Tier 1+2 trust & oversight foundation`**
  - **MCP risk reasoning surfaced in the approval dialog.** `AgenticActionExecutor.isRiskyMcpCallReason()` prefers real `destructiveHint` / `readOnlyHint` annotations, then falls back to keyword matching. The matched reason is recorded as an `MCP_CALL_GATED` `TaskStep`, shown in the `MainActivity` approval dialog, and threaded through `PendingApproval.detail`.
  - **Cloud token budget guardrail.** `TaskBudgetTracker` stores a user-configurable `cloud_token_cap` in `ollama_swarm_prefs` (`0` = unlimited/legacy). `SwarmEngine` estimates per-call tokens with the existing `(prompt.length + output.length) / 2 + 100` heuristic and halts the `AGENTIC_LOOP` before the next iteration when the cap is exceeded, marking remaining todos `[BUDGET HALT]` and recording a `BUDGET_HALT` step.
  - **New Budget sub-tab.** `BudgetScreen.kt` (inside `SystemConfigScreen`) shows session token/cost totals and lets users edit the cap.
  - **TaskStep icon/color dispatch.** `TaskStepTimelineItem` now maps each `actionType` to a semantic icon and color via `stepIconAndColorFor()` (planning = purple, output = blue, verification = teal, failures/declines/halt = red, MCP = amber, git = green). `agentRole` also gets additional color mappings (QA, Architect).

### Tier 2 — Oversight & Review

- **#8 (continued)**
  - **"Awaiting Approval" agent status.** `agentId` is now threaded through `AgenticActionExecutorInterface.parseAndExecute()` and `autoCheckpoint()` down to the git-push and MCP-call gates. The executor sets `AgentStateStore` status to `"Awaiting Approval"` while the dialog is open and restores idle after the user responds. `AgentScreen` renders an orange `#FF9800` badge.
  - **Batched file-write review.** `AgenticActionExecutor` collects every `WRITE_FILE:` directive from a single act step, deduplicates paths, generates proposed content for each, and opens one `PendingFileChangeBatch` review. `MainActivity` shows a scrollable batch dialog with per-file Accept/Reject toggles, diff previews, Confirm, and Reject All.

### Tier 3 — Sandbox, Analytics & Background Execution

- **#9 `feat: Tier 3a seed OllamaDev Sandbox MCP server config`**
  - `AppDatabase` now seeds an `OllamaDev Sandbox` MCP server (`type=Sandbox`, `http://localhost:5000/mcp`) and two new skills: `Pytest Sandbox Runner` bound to `run_pytest`, and `Gradle Sandbox Runner` bound to `run_gradle_test_command`.
  - `FakeAppDatabase` is updated so tests see the same seeded server/skills.

- **#10 `feat: Tier 3b+c analytics + background service`**
  - **In-app analytics.** `AnalyticsScreen.kt` is added as a new "Analytics" tab in both bottom navigation and navigation rail. It displays aggregate cards (total tasks, total tokens, execution time, unresolved rate), a per-`SwarmConfig` breakdown table, and a 7-day task volume bar chart. `SwarmViewModel` exposes `analyticsSummary`, `analyticsPerConfig`, and `analyticsTimeSeries`.
  - **Background agentic-loop service.** `AgenticLoopService` is a foreground `Service` (`foregroundServiceType="dataSync"`) that owns its own `SwarmEngine`, executes tasks outside the UI, creates a notification channel, posts a persistent progress notification, updates it from the active task's `TaskStep`s, and finalizes the notification on completion/failure.
  - `AndroidManifest.xml` declares the service and adds `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, and `POST_NOTIFICATIONS` permissions.
  - `SessionScreen` adds a cloud icon to dispatch `runSwarmInBackground()`. `MainActivity` hosts the `RequestPermission()` launcher and renders the notification-permission rationale dialog. `SwarmViewModel` tracks permission state via `ensureNotificationPermission()` and `onNotificationPermissionResult()`.
  - `RealSecurePrefs` constructor is widened from `Application` to `Context` so the service can build the same secure-prefs seam used by the ViewModel.

### Backlog Housekeeping

- PR #10 updates `agent-os/backlog.md` to mark Tier 1–3 complete and also checks off Tier 4 (SDLC sprint DB migration, `SprintOrchestrator` wiring, `LlmRouter.routePrompt()` distillation overload, `SprintPlannerScreen` tab integration) and Tier 5 (MCP filesystem write integration test).

---

## Known Limitations / Next Steps

- The seeded `OllamaDev Sandbox` MCP server points to `http://localhost:5000/mcp`. The companion `ollamadev-mcp-server` is not bundled in this repository and must be running separately.
- The cloud-token budget uses a character-length heuristic, not a real tokenizer count. The cap is enforced at iteration boundaries, so an in-progress LLM call may exceed the cap. Set the cap to `0` to disable the guardrail.
- MCP risk gating uses tool annotations when a live server provides them; otherwise it falls back to keyword matching over skill/tool names and descriptions.
- Background execution requires `POST_NOTIFICATIONS` on Android 13+. If denied, the foreground start is gated by a rationale dialog. If a background run hits a human-approval gate, it will block until the app returns to the foreground because the dialog cannot be shown from a headless service.
- The background service instantiates its own `SwarmEngine` and ephemeral git workdir; UI state is not mirrored in real time.
- The `AGENTIC_LOOP` verify prompt currently interpolates an unresolved `decision` variable before it is declared. A follow-up fix is needed to pass the act-step output into the QA prompt.
- `AppDatabase` uses `fallbackToDestructiveMigration(true)`, so Room version bumps will wipe local data on upgrade. The current version is `14` with an explicit `MIGRATION_13_14`.

---

**Contributors:** @asshat1981ar
