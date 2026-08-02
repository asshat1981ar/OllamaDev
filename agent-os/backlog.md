# OllamaDev — Agentic Harness Backlog (self-prompt)

Paste this whole file back to me (or run it via `/loop`) to resume systematic,
highest-impact-first work on the agentic coding harness shipped in PR #1
(`7e72f8c`). Work top to bottom — each tier is ordered by impact-per-effort,
not by dependency, so within a tier pick whichever the user cares about most.

## How to work through this

For each item:
1. **Re-read the relevant code** (file paths are given below) before touching
   anything — the backlog was written from a snapshot, code may have moved.
2. **Plan briefly in chat** (not a new plan-mode session unless the change is
   large/ambiguous) — one paragraph on the approach, referencing existing
   patterns from `CLAUDE.md` / `agent-os/standards/`.
3. **Implement.**
4. **Verify** via the `run-ollamadev` skill: build + relevant unit tests at
   minimum; screenshot if the change touches UI.
5. **Stop and summarize** — do not commit, push, or open a PR without being
   asked again. Each tier is a natural checkpoint to report back at.
6. **Check off the item below** (edit this file) so re-runs don't repeat work.

Don't batch multiple backlog items into one commit later — keep them
separable, matching how PR #1 was scoped.

---

## Tier 1 — cheap, high leverage (do these first)

- [x] **Surface MCP risk-reasoning to the user.** `isRiskyMcpCallReason()` in
      `AgenticActionExecutor.kt` now computes *why* a call was gated (real
      `destructiveHint`/`readOnlyHint` annotation vs. keyword match) and
      threads the reason into `PendingApproval.detail`. The approval dialog in
      `MainActivity.kt` renders it as `Reason: ${approval.detail}`, and an
      `MCP_CALL_GATED` `TaskStep` is recorded before the dialog so the timeline
      explains the pause even if the user dismisses the dialog. Unit tests in
      `AgenticActionExecutorTest` and `SwarmEngineMcpRiskReasoningTest` cover
      both annotation and keyword paths. Done.
- [x] **Cost/budget guardrail for cloud-preferred routing.** Added
      `TaskBudgetTracker` which stores a user-configurable `cloud_token_cap`
      in `ollama_swarm_prefs` (0 = unlimited/legacy behavior). `SwarmEngine`
      now tracks per-task estimated tokens using the same `(prompt.length +
      output.length) / 2 + 100` heuristic as `AgentStateStore`. When the cap is
      non-zero and exceeded, the agentic loop halts before the next iteration,
      marks remaining todos `[BUDGET HALT]`, and records a `BUDGET_HALT`
      `TaskStep`. A new **Budget** sub-tab in `SystemConfigScreen` lets users
      view session totals and edit the cap. Unit tests in
      `SwarmEngineBudgetGuardrailTest` cover both halting (low cap) and
      disabled (cap = 0) behavior. Done.
- [x] **TaskStep icon/color dispatch.** `TaskStepTimelineItem` now maps
      each known `actionType` to a semantic icon + color chip (planning =
      purple, output = blue, verification = teal, failures/declines/halt =
      red, MCP calls = amber, git commits/push = green, etc.) using a
      `stepIconAndColorFor()` helper. The badge replaces the plain monospace
      actionType text in the timeline card header. `agentRole` also got a few
      extra color mappings (QA, Architect) so the timeline dot stays useful.
      Compose test `TaskStepTimelineItemTest` verifies badges/icons render for
      PLAN, OUTPUT, MCP_CALL_FAILED, and BUDGET_HALT. Done.

## Tier 2 — moderate effort, closes real UX gaps

- [x] **"Awaiting Approval" agent status.** Threaded `agentId` through
      `AgenticActionExecutorInterface.parseAndExecute()` and `autoCheckpoint()`,
      from `StepRunner.run(req.agent.id)` down into the git-push and MCP-call
      approval gates. Before `PendingApprovalStore.requestApproval()` suspends,
      the executor calls `AgentStateStore.setAgentActive(agentId, true,
      "Awaiting Approval")`; after the user approves/declines it restores the
      agent to idle. `AgentScreen.kt` now renders an orange (`#FF9800`)
      "AWAITING APPROVAL" badge when `AgentMetrics.status` matches. Tests in
      `AgenticActionExecutorTest` and `AgentScreenAwaitingApprovalTest` cover
      the status transition and UI rendering. Done.
- [x] **Batch multi-file WRITE_FILE review.** `AgenticActionExecutor`
      now scans an act step's full output, deduplicates all `WRITE_FILE:`
      paths, and generates content for each before opening a single
      `PendingFileChangeBatch` review. `PendingApprovalStore` gained
      `pendingFileChangeBatch`, `requestFileChangeBatchReview`,
      `setBatchFileDecision`, `confirmFileChangeBatch`, and
      `rejectAllBatchFileChanges`. `MainActivity.kt` renders a scrollable
      batch dialog with per-file Accept/Reject toggles, diff previews,
      Confirm, and Reject All actions. `SwarmViewModel` exposes the batch
      flow and delegates to the store. Tests in
      `AgenticActionExecutorBatchFileWriteTest` cover collecting multiple
      directives into one batch and applying a mix of accept/reject
      decisions. Done.

## Tier 3 — bigger bets (highest ceiling, most scope)

- [x] **Real MCP test-runner integration.** Chose option (a): extended
      the companion `ollamadev-mcp-server` with a new `sandbox.py` module
      exposing four tools: `run_pytest`, `run_gradle_test_command`,
      `run_shell_command` (annotated `destructiveHint=true`), and
      `get_sandbox_status`. The tools run commands in `WORKSPACE_ROOT` and
      return structured JSON pass/fail output. The server bootstrap now
      registers the sandbox module; `meta.py` catalog and version were bumped
      to 0.4.0; README was updated with the new Sandbox section and risk
      gating note. In the Android app, `AppDatabase` seeder and
      `FakeAppDatabase` now include an `OllamaDev Sandbox` MCP server of
      type `Sandbox` and two new skills (`Pytest Sandbox Runner`, `Gradle
      Sandbox Runner`) bound to `run_pytest` / `run_gradle_test_command`.
      Tests in `ollamadev-mcp-server/tests/test_sandbox.py` cover status,
      missing-pytest handling, shell success, and shell failure. Done.
- [x] **In-app task analytics.** Added `AnalyticsScreen.kt` with aggregate
      cards (total tasks, total tokens, execution time, unresolved rate), a
      per-`SwarmConfig` breakdown table, and a 7-day task volume bar chart.
      The screen is wired into `MainActivity.kt` as a new "Analytics" tab
      in both bottom navigation and navigation rail. `SwarmViewModel.kt`
      exposes `analyticsSummary`, `analyticsPerConfig`, and
      `analyticsTimeSeries` StateFlows derived from `allTasks` and
      `allSwarmConfigs`. Tests in `AnalyticsScreenTest` and
      `SwarmViewModelAnalyticsTest` cover rendering and aggregation. Done.
- [x] **Background execution + notification.** Added
      `AgenticLoopService`, a foreground `Service` (type `dataSync`) that
      owns its own `SwarmEngine` and runs `executeTask` outside the UI.
      It creates a notification channel on first run, starts itself in the
      foreground with a persistent progress notification, listens to
      `TaskStep` updates for the active task to refresh the notification,
      and finalizes the notification on completion/failure before stopping.
      `AndroidManifest.xml` declares the service and permissions
      (`FOREGROUND_SERVICE`, `FOREGGROUND_SERVICE_DATA_SYNC`,
      `POST_NOTIFICATIONS`). `SwarmViewModel` gained `runSwarmInBackground()`,
      `ensureNotificationPermission()`, and a permission-rationale StateFlow.
      `MainActivity.kt` hosts the `RequestPermission()` launcher and renders
      the rationale dialog; `SessionScreen` gates the cloud icon button on the
      permission. Added `SwarmViewModelBackgroundRunTest` to verify the
      ViewModel method records the background marker chat message without
      crashing. Done.

---

## Tier 4 — Autonomous SDLC Sprint Workflow Chain (new, spec 09)

These items implement the six-phase sprint cycle designed in
`agent-os/product/specs/09-sdlc-sprint-workflows.md`. Dependencies flow
downward — 4a must ship before 4b; 4b before 4c/4d; 4c/4d are independent.

- [x] **4a. DB migration: SprintCycle + SprintArtifact entities.**
      Add `SprintCycle` and `SprintArtifact` to `Entities.kt` (already
      designed, see that file), add `SprintCycleDao` + `SprintArtifactDao` to
      `Daos.kt`, bump `AppDatabase.version` to the next integer, add a
      `Migration(N, N+1)` that creates `sprint_cycles` and `sprint_artifacts`
      tables, and add both `*::class` entries to `@Database(entities=[...])`.
      Wire new DAOs through `AppDatabaseInterface`. Verify: `./gradlew
      testDebugUnitTest` stays green; no `IllegalStateException: Room cannot
      verify the data integrity` at cold launch.

- [x] **4b. SprintOrchestrator wired into SwarmViewModel.**
      `SprintOrchestrator.kt` is written (data layer). Now:
      (1) Instantiate it in `SwarmViewModel` alongside `SwarmEngine` — inject
          `db`, `llmRouter`, `engine`, `pendingApprovalStore` from the same
          constructor args already present.
      (2) Expose `activeCycle: StateFlow<SprintCycle?>`,
          `sprintCycleProgress: StateFlow<SprintProgress>`, and
          `sprintArtifacts: StateFlow<List<SprintArtifact>>` on the ViewModel
          following the `_backing / .asStateFlow()` pattern.
      (3) Add `fun startSprintCycle(goal: String)`,
          `fun pauseSprintCycle()`, `fun resumeSprintCycle()`,
          `fun cancelSprintCycle()` to `SwarmViewModel` delegating to the
          orchestrator.
      (4) `SprintOrchestrator.collectPhaseArtifact` polls
          `db.swarmTaskDao().getTaskById()` — this requires
          `AppDatabaseInterface` to expose `swarmTaskDao()`,
          `workspaceFileDao()`, `gitCommitDao()`, `sprintCycleDao()`, and
          `sprintArtifactDao()`. Add the missing methods following the
          existing interface-seam pattern (`agent-os/standards/data/interface-seam.md`).
      Verify: unit-test `SprintOrchestratorTest` with fake DAOs confirms
      6-phase cycle writes 6 `SprintArtifact` rows in order.

- [x] **4c. LlmRouterInterface.routePrompt() extension for distillation.**
      `SprintOrchestrator.distilArtifact()` calls
      `llmRouter.routePrompt(prompt, systemPrompt, preferCloud)` — this
      method doesn't exist on `LlmRouterInterface` yet. Add it as a new
      suspend fun returning `String`. Implement in `LlmRouter.kt` using the
      same node-selection logic as the existing `route()` / `stream()` path,
      but returning the full response as a single string (no streaming needed
      for distillation). Mark `preferCloud = false` for distillation calls —
      they are short, cheap, and don't benefit from cloud routing.
      Verify: `LlmRouterTest` covers the new overload with a fake node.

- [x] **4d. SprintPlannerScreen wired into MainActivity tab switch.**
      `SprintPlannerScreen.kt` is written. Now:
      (1) Add `"Sprints"` to the tab list in `MainActivity.kt` alongside
          the existing tabs. Follow the exact same `BottomNavigationItem`
          pattern used by the other tabs. Icon: `Icons.Rounded.AutoAwesomeMotion`.
      (2) Add `SprintPlannerScreen(viewModel = viewModel,
          onNavigateToSession = { taskId -> activeTab = "Session"; ... })`
          to the `when (activeTab)` branch.
      (3) The `onNavigateToSession` callback should set `activeTab = "Session"`
          and pass the task ID to `SessionScreen` — look at how
          `DashboardScreen.onNavigateToSwarm` does this to thread a config
          through to the session; do the analogous thing for a task ID.
      Verify: Roborazzi screenshot of `SprintPlannerScreen` renders without
      crash. Check phone (360dp) and tablet (720dp) width snapshots.

---

## Tier 6 — Remote build delegation to GitHub (new, spec 10)

These items offload `gradlew`/build/screenshot work from the slow aarch64 QEMU-shimmed
local container to real x86_64 GitHub Actions runners, and pull the artifacts back.
Designed in `agent-os/product/specs/10-remote-build-delegation.md`.

- [x] **6a. `remote-build.yml` generic on-demand runner.** Added
      `.github/workflows/remote-build.yml`: `workflow_dispatch` with `task` + optional `ref`
      inputs; single `ubuntu-latest` job runs `./gradlew ${{ inputs.task }}` and uploads
      `app/build/outputs/**` + `app/build/reports/**` as a `remote-build-outputs` artifact
      (`if: always()`). Covers APK, JUnit reports, and real Roborazzi screenshots.
- [x] **6b. `scripts/delegate-build.sh` local driver.** One-command wrapper: optional
      commit+push (`--commit`, PAT-over-HTTPS via `gh auth setup-git`), trigger dispatch,
      `gh run watch --exit-status`, `gh run download` into `ci-artifacts/`, non-zero exit on
      failure. Runnable as `scripts/delegate-build.sh '<gradle task>'`. Handles the
      default-branch visibility constraint (new workflows can't be dispatched until they
      land on `main`): falls back to the standard `Android CI` gate for the default task,
      with guidance otherwise.

---

## Tier 7 — CI delegation strategies (new, spec 11)

Parallelize the merge gate and make every gradle/job delegate through one reusable runner,
per `agent-os/product/specs/11-ci-delegation-strategies.md`.

- [x] **7a. Reusable gradle runner.** `.github/workflows/gradle-task.yml` (`workflow_call`):
      checkout → JDK 17 → `gradle/actions/setup-gradle@v3` → run task → upload outputs
      (`if: always()`). Inputs: `task`, `ref`, `artifact_name`, `upload_patterns`,
      `cache_read_only`, `timeout_minutes`. All other workflows call it.
- [x] **7b. Parallel gate.** `android-ci.yml` runs the unit suite as three matrix shards
      (`ui` / `data` / `rest`) concurrently with `:app:assembleDebug` (no `needs: test`);
      gate wall-clock ≈ max(test, build) instead of test + build.
- [x] **7c. New delegation targets.** `Roborazzi Screenshots` (real PNGs, impossible locally),
      `Nightly Maintenance` (`cron 0 3 * * *`: test + build + lint on main), `Android Lint`
      (dispatch-only), and `Remote Build Delegation` refactored onto the reusable runner
      (same interface, so `delegate-build.sh` is unchanged).
- [x] **7d. Local cache warm-up.** `scripts/pull-gradle-cache.sh` restores the
      arch-independent parts of the main-branch gradle cache (`caches/modules-2`, wrapper
      dists) into `~/.gradle` via the `gh-actions-cache` extension; skips x86_64-only
      compiled caches.
- [x] **7e. Spec + docs.** `specs/11-ci-delegation-strategies.md` documents the design,
      tradeoffs, verification commands, and deferred items (issue-driven task queue,
      devcontainer, larger/self-hosted runners, other CI providers).
- [x] **6c. On-demand merge gate.** Added `workflow_dispatch:` to `android-ci.yml` so the
      standard `test` + `build` jobs can be re-run on any branch without a PR.
- [x] **6d. Spec + docs.** `agent-os/product/specs/10-remote-build-delegation.md` documents
      the mechanism, constraints (pushed-ref-only, actions minutes, injection surface), and
      verification commands.

---



These were flagged as explicit open decisions in the original plan and are
intentionally *not* on this backlog — revisit only if they start causing
real friction:
- `OllamaNode.isCloud` explicit field vs. the current name/URL heuristic in
  `isCloudGatewayNode()`.
- `GitCommit.stepId` FK (currently only `taskId`) for finer per-step
  checkpoint traceability.


### Tier 5 — MCP write tool integration test
**Priority:** low

- [x] Verify that VERIFICATION agents can emit WRITE_FILE directives via `write_workspace_file` and have them land on disk. Added `tests/test_filesystem.py` to the companion `ollamadev-mcp-server`: it registers the filesystem tools on a test `MCPServer`, calls `write_workspace_file` through `mcp.call_tool`, and asserts the file (including parent directories) appears on disk and is returned by `list_workspace_files`. All six MCP server tests (filesystem + sandbox) pass. Done.
