# OllamaDev Comprehensive Roadmap — Q3 2026

> **For Hermes:** Use subagent-driven-development skill to implement this plan task-by-task.

**Goal:** Turn the OllamaDev Android agentic-coding harness into the strongest mobile-native AI-coding product by systematically extending shipped features, filling trust/UX gaps, and entering the sharing/ecosystem layer.

**Architecture:** Keep deepening the existing deep modules (`SwarmEngine`, `AgenticActionExecutor`, `StepRunner`, `LlmRouter`, `PendingApprovalStore`) rather than adding parallel orchestrators. Prefer in-app, on-device-first experiences that differentiate OllamaDev from desktop IDE agents.

**Tech Stack:** Native Android (Kotlin 2.2.10, Jetpack Compose, Material 3, Room, JGit, OkHttp/MCP Streamable-HTTP, Ollama local + optional cloud gateway, Robolectric/Roborazzi for tests).

---

## Current state snapshot (based on systematic codebase review)

### Shipped (Tier 1–5 + headless approval)
- `AGENTIC_LOOP` workflow: plan → act → verify → checkpoint (`SwarmEngine.kt`).
- Human-approval gates for `GIT_PUSH` and destructive `MCP_CALL` (`PendingApprovalStore`, global dialog in `MainActivity`).
- Batched `WRITE_FILE:` review with line-level diff (`DiffUtils`, `AgenticActionExecutor`).
- MCP risk reasoning surfaced in approval dialog.
- Cloud-token budget guardrail (`TaskBudgetTracker`, `BudgetScreen`).
- `TaskStep` icon/color dispatch (`TaskStepComponents`).
- "Awaiting Approval" agent status.
- In-app analytics (`AnalyticsScreen`).
- Background execution (`AgenticLoopService`) + headless approval auto-decline policy (just shipped: `APPROVAL_SKIPPED_HEADLESS`).
- SDLC sprint workflow DB + orchestrator + `SprintPlannerScreen` tab.
- Workspace panel: file explorer, code editor, git integration, self-healing patch dialog (`IdeWorkspaceScreen`).
- Voice capture in `SessionScreen`.
- Seeded OllamaDev Sandbox MCP server + pytest/gradle runner skills.

### Domain decomposition (semantic modules)
| Domain | Key files | What it does |
|---|---|---|
| LLM routing | `LlmRouter.kt`, `LlmRouterInterface.kt` | Node selection, cloud routing, skills context |
| Agent step loop | `StepRunner.kt` | Shared stream/metrics/status lifecycle |
| Orchestration | `SwarmEngine.kt` | Coordination modes and `AGENTIC_LOOP` |
| Side-effect execution | `AgenticActionExecutor.kt` | Parses git/MCP/WRITE_FILE directives |
| Approval gating | `PendingApprovalStore.kt` | Singleton bridge for human-in-the-loop |
| Persistence | `AppDatabase.kt`, `Entities.kt`, `Daos.kt` | Room entities and DAOs |
| Workspace | `IdeWorkspaceScreen.kt` | File tree, editor, git, self-healing |
| Session / chat | `SessionScreen.kt` | Chat + step timeline + voice + background dispatch |
| Dashboard / manage | `DashboardScreen.kt`, `ManageScreen.kt`, `AgentScreen.kt`, `NodeScreen.kt`, `McpSkillsScreen.kt` | Swarms, agents, nodes, MCP skills |
| Sprint / SDLC | `SprintPlannerScreen.kt`, `SprintOrchestrator.kt` | Six-phase sprint cycle |
| Analytics / budget | `AnalyticsScreen.kt`, `BudgetScreen.kt`, `TaskBudgetTracker.kt` | In-app metrics and spend guardrail |
| Notifications | `AgenticLoopService.kt`, `NotificationHelpers.kt` | Background execution |
| Theme | `ui/theme/{Color,Theme,Type}.kt`, `CLAUDE.md` | Compose design system |

### Remaining known gaps
1. `AgenticLoopService` headless policy is implemented but Gradle tests are currently blocked in this WSL environment — needs verification.
2. `WorkspaceViewModel.kt` (642 lines) is an orphan extraction from `SwarmViewModel`; wiring/verdict pending.
3. `AGENTIC_LOOP` verify-prompt `decision` bug needs a final regression-test verdict.
4. `fallbackToDestructiveMigration(true)` risks data loss on next Room bump.
5. Budget token heuristic uses char-count, not a real tokenizer.
6. `LlmRouter.buildSkillsContext()` does not teach agents the `WRITE_FILE:`/git directive formats.
7. No GitHub PR export / shareable artifact output.
8. No persistent-notification quick actions.
9. No MCP skill marketplace / discovery UI beyond the registry dialog.
10. No on-device model download/manager.

---

## Phase 0 — Stabilize and verify the just-shipped headless approval policy

### Task 0.1: Fix Gradle daemon connectivity in WSL and run headless-approval tests
**Objective:** Unblock the test runner so the new `AgenticActionExecutorHeadlessApprovalTest` and existing regression suite can run.

**Files:**
- Modify: `gradle.properties` (if `org.gradle.java.home` / IPv6 settings need tuning)
- Test: `app/src/test/java/com/example/data/AgenticActionExecutorHeadlessApprovalTest.kt`
- Test: all files under `app/src/test/...`

**Step 1:** Diagnose daemon issue.
Run: `wsl bash -c 'cd /home/dev/OllamaDev && ./gradlew --version --no-daemon'`
Expected: Gradle wrapper version prints.

**Step 2:** If the daemon still fails silently, try:
- `wsl bash -c 'cd /home/dev/OllamaDev && ./gradlew --no-daemon --offline :app:testDebugUnitTest --tests "*HeadlessApprovalTest*"'`
- Set `GRADLE_OPTS="-Dorg.gradle.daemon=false -Djava.net.preferIPv4Stack=true"`
- Use WSL1 or a different OpenJDK if the issue is JVM-related.

**Step 3:** Once tests run, capture output.
Expected: targeted tests pass.

**Step 4:** Commit.
```bash
git add -A
git commit -m "ci: restore Gradle test runner in WSL for headless approval tests"
```

### Task 0.2: Add regression test for verify-prompt `decision` bug
**Objective:** Close backlog item 6.1 with evidence and a regression test.

**Files:**
- Read: `app/src/main/java/com/example/data/SwarmEngine.kt` around `runAgenticLoopWorkflow` verify step
- Test: create `app/src/test/java/com/example/data/SwarmEngineVerifyPromptRegressionTest.kt`

**Step 1:** Inspect `SwarmEngine.kt` lines 390–440 to confirm `actResult.output` is interpolated into `verifyPrompt`.

**Step 2:** Write a failing test that asserts the verify prompt contains the act output; run it.

**Step 3:** If it fails, fix the variable reference; if it passes, the bug is already fixed — write the regression test and add a comment in code.

**Step 4:** Run `./gradlew :app:testDebugUnitTest --tests "*VerifyPromptRegressionTest*"`
Expected: PASS.

**Step 5:** Update `agent-os/backlog.md` to mark 6.1 done.

**Step 6:** Commit.

### Task 0.3: Decide the fate of `WorkspaceViewModel.kt`
**Objective:** Close backlog item 6.2 with a documented verdict and, if needed, a wiring plan.

**Files:**
- Read: `app/src/main/java/com/example/viewmodel/WorkspaceViewModel.kt`
- Read: `app/src/main/java/com/example/viewmodel/SwarmViewModel.kt` (workspace-related members)
- Create: `agent-os/plans/workspace-viewmodel-verdict.md`

**Step 1:** Map duplicated members between the two ViewModels.

**Step 2:** Determine whether `WorkspaceViewModel` is referenced from any screen, test, or manifest.

**Step 3:** Produce verdict.md with either:
- "Wire-in" plan (which screens get it, constructor changes, tests), or
- "Delete" plan (confirm no references, delete file, remove unused tests).

**Step 4:** If the verdict is "delete", perform the deletion and run tests.
If the verdict is "wire-in", create a follow-up implementation plan and do not change production code yet.

**Step 5:** Update `agent-os/backlog.md` to mark 6.2 done.

**Step 6:** Commit.

---

## Phase 1 — Trust, reliability, and data safety (high leverage)

### Task 1.1: Add Room migration safeguard
**Objective:** Prevent destructive migration from wiping user data on the next schema bump.

**Files:**
- Read: `app/src/main/java/com/example/data/AppDatabase.kt`
- Read: `agent-os/standards/data/interface-seam.md`
- Modify: `app/src/main/java/com/example/data/AppDatabase.kt`
- Create: `docs/adr/ADR-0002-room-migration-safeguard.md`

**Step 1:** Remove or gate `fallbackToDestructiveMigration(true)` behind a debug flag.

**Step 2:** Add an explicit migration path: export schema JSON, write `MIGRATION_14_15`, and add a startup backup/export hook.

**Step 3:** Add tests in `app/src/test/java/com/example/data/AppDatabaseMigrationTest.kt`.

**Step 4:** Run `./gradlew :app:testDebugUnitTest --tests "*MigrationTest*"`
Expected: PASS.

**Step 5:** Update `agent-os/backlog.md` to mark 6.4 done.

**Step 6:** Commit.

### Task 1.2: Improve budget token heuristic precision
**Objective:** Close backlog item 6.5.

**Files:**
- Modify: `app/src/main/java/com/example/data/TaskBudgetTracker.kt`
- Modify: `app/src/main/java/com/example/ui/BudgetScreen.kt`
- Test: `app/src/test/java/com/example/data/SwarmEngineBudgetGuardrailTest.kt`

**Step 1:** Choose between (a) documenting the heuristic error bounds in `BudgetScreen` copy, or (b) swapping to a lightweight tokenizer.

**Step 2:** Implement the chosen option.

**Step 3:** Update existing budget tests if behavior changes.

**Step 4:** Run targeted tests.

**Step 5:** Update `agent-os/backlog.md` to mark 6.5 done.

**Step 6:** Commit.

### Task 1.3: Augment agent prompts with directive reference
**Objective:** Close backlog item 6.6 — teach agents the `WRITE_FILE:`/git/MCP directive syntax and approval awareness.

**Files:**
- Modify: `app/src/main/java/com/example/data/LlmRouter.kt` (`buildSkillsContext()` or new `generateForAgent` context)
- Test: `app/src/test/java/com/example/data/LlmRouterTest.kt`
- Test: `app/src/test/java/com/example/data/SwarmEngineAgenticLoopTest.kt`

**Step 1:** Add a compact directive reference block to the context appended only for agent generation (not routing/distillation).

**Step 2:** Ensure char budget is respected.

**Step 3:** Write tests asserting the reference block appears in generated system prompts and not in routing prompts.

**Step 4:** Run targeted tests.

**Step 5:** Update `agent-os/backlog.md` to mark 6.6 done.

**Step 6:** Commit.

---

## Phase 2 — Research: competitor analysis and market landscape

### Task 2.1: Define research questions and competitor shortlist
**Objective:** Before scraping, know what we are looking for and why.

**Files:**
- Create: `agent-os/research/2026-08-competitor-research-brief.md`

**Research questions:**
1. Which mobile-native AI coding agents exist today? (Replit mobile, GitHub Copilot mobile, Claude app code mode, etc.)
2. What is the current desktop-agent baseline for: chat-driven coding, workspace file editing, git integration, human approval, background execution, voice input, analytics, sharing/PR export?
3. What is the competitive gap OllamaDev can own? (on-device, local-LLM, multi-agent swarm, MCP, voice, SDLC sprint)
4. What user jobs are underserved? (coding on phone/tablet without IDE, local-first privacy, low-cost background agents)
5. What pricing models are competitors using?

**Competitor shortlist (initial):**
- Replit Agent / Replit mobile
- GitHub Copilot (IDE + mobile app)
- Claude Code / Claude iOS
- Cursor / Windsurf
- OpenCode / Codex CLI
- Bolt.new / v0
- Lovable / Tempo

**Step 1:** Write the brief and get it approved.

**Step 2:** Commit.

### Task 2.2: Collect competitor evidence
**Objective:** Gather current public information about each competitor.

**Files:**
- Create: `agent-os/research/2026-08-competitor-profiles.md`

**Step 1:** For each competitor, collect:
- Product name + URL
- Primary platform (web/desktop/mobile)
- Key features (chat, file edit, git, voice, background, approval, analytics, sharing)
- Pricing tier
- Mobile presence
- Local/on-device angle
- Evidence links

**Step 2:** Use web search and official docs only; do not fabricate data.

**Step 3:** Commit.

### Task 2.3: Market synthesis and gap map
**Objective:** Distill evidence into a decision-ready gap map.

**Files:**
- Create: `agent-os/research/2026-08-market-gap-map.md`

**Step 1:** Score each feature dimension across competitors (1–5).

**Step 2:** Identify dimensions where no competitor scores high AND OllamaDev has an existing advantage.

**Step 3:** List 5–7 candidate features ranked by strategic fit.

**Step 4:** Commit.

---

## Phase 3 — Pick and design the next big feature

### Task 3.1: Run weighted scoring on Phase 2 candidates
**Objective:** Use the existing scoring framework to rank Phase 3 candidates.

**Files:**
- Read: `agent-os/plans/next-feature-scoring.md`
- Create: `agent-os/plans/2026-08-next-feature-scoring.md`

**Step 1:** Copy the 7-criteria scoring table.

**Step 2:** Score each candidate from Phase 2.

**Step 3:** Choose the top feature.

**Step 4:** Commit.

### Task 3.2: Write a feature PRD for the winner
**Objective:** Produce a product-requirements document the implementer can build from.

**Files:**
- Create: `agent-os/product/specs/10-<winning-feature>.md`

**Contents:**
- Overview and problem
- Goals and non-goals
- Functional requirements table
- User scenarios
- Success metrics
- Open questions

**Step 1:** Draft PRD.

**Step 2:** Review against actual codebase to ensure it maps to real classes.

**Step 3:** Commit.

### Task 3.3: Write HLD for the winner
**Objective:** Define architecture, changed components, and data flow.

**Files:**
- Create: `agent-os/product/specs/10-<winning-feature>-hld.md`

**Contents:**
- System context diagram
- Component responsibility table
- Sequence diagrams for key flows
- Architectural decisions

**Step 1:** Draft HLD referencing real files.

**Step 2:** Commit.

---

## Phase 4 — Candidate features (pre-ranked, to be validated by research)

These are the likely high-value directions. Phase 2/3 research will confirm or reorder them.

### Feature A: GitHub PR export from sprint/workspace artifacts
**Maps to existing code:** `IdeWorkspaceScreen.GithubIntegrationSection`, `GitService`, `SprintOrchestrator`, `WorkspaceFile`.

**Why it scores high:** bridges local agent work to shareable review; no mobile competitor does this natively.

**Implementation sketch:**
1. Add `GitHubPrExporter` deep module.
2. Authenticate via `SecurePrefs` PAT.
3. Push branch, create PR via GitHub REST API, attach sprint artifacts as PR body.
4. Add "Export PR" action to `SprintPlannerScreen` and workspace git panel.
5. Tests with mocked GitHub API.

### Feature B: Persistent notification quick actions
**Maps to existing code:** `AgenticLoopService`, `NotificationHelpers`, `PendingApprovalStore`.

**Why it scores high:** mobile-native UX; lets users approve/decline/retry from the notification shade without opening the app.

**Implementation sketch:**
1. Add `BroadcastReceiver` for notification actions.
2. Wire actions to `PendingApprovalStore` resolution.
3. Update notification builder in `NotificationHelpers`.
4. Tests via Robolectric notification shadow.

### Feature C: MCP skill marketplace / discovery UI
**Maps to existing code:** `McpSkillsScreen.kt`, `McpRegistryClient`, `AppDatabase` seeded servers/skills.

**Why it scores high:** unlocks ecosystem; users can browse, install, and bind MCP servers to skills.

**Implementation sketch:**
1. Add a registry-browsing marketplace surface.
2. Add "install skill" flow that writes to `AppDatabase` skills table.
3. Surface installed skills in `LlmRouter.buildSkillsContext()`.
4. Tests with fake registry responses.

### Feature D: Voice-driven follow-up and session memory
**Maps to existing code:** `SessionScreen` voice capture, `SwarmViewModel.chatMessages`, `TaskStep` history.

**Why it scores high:** differentiated mobile input modality; follow-up context reduces typing.

**Implementation sketch:**
1. Persist recent task summaries as session memory.
2. Allow voice follow-up that references prior task context.
3. Surface memory chips in `SessionScreen` composer.

### Feature E: On-device model download / manager
**Maps to existing code:** `OllamaNode`, `NodeScreen`, `LlmRouter`.

**Why it scores high:** strongest local-first/privacy differentiation; large effort.

**Implementation sketch:**
1. Add model list/pull/delete UI.
2. Proxy Ollama `/api/pull` and `/api/delete`.
3. Track download progress.

---

## Phase 5 — Documentation and roadmap hygiene

### Task 5.1: Update `agent-os/backlog.md`
After each phase, check off completed items and add new ones discovered during research.

### Task 5.2: Keep `RELEASE_NOTES.md` current
Add a "Next" section summarizing shipped phases.

### Task 5.3: Refresh scoring plan
If market research changes priorities, regenerate `agent-os/plans/2026-08-next-feature-scoring.md`.

---

## Appendix — Verification commands

```bash
# Run all unit tests
wsl bash -c 'cd /home/dev/OllamaDev && ./gradlew --no-daemon :app:testDebugUnitTest'

# Run a single test class
wsl bash -c 'cd /home/dev/OllamaDev && ./gradlew --no-daemon :app:testDebugUnitTest --tests "com.example.data.SwarmEngineBudgetGuardrailTest"'

# Build debug APK
wsl bash -c 'cd /home/dev/OllamaDev && ./gradlew --no-daemon :app:assembleDebug'

# Screenshot smoke test (after UI change)
wsl bash -c 'cd /home/dev/OllamaDev && ./gradlew --no-daemon :app:testDebugUnitTest --tests "com.example.ui.ScreenshotDriverTest"'
```

Expected outcomes:
- Unit tests: green or explicit failure reason documented.
- APK: `app/build/outputs/apk/debug/app-debug.apk` exists.
- Screenshots: under `app/build/outputs/roborazzi/` if Roborazzi is enabled.

---

## Open questions

1. Is the Gradle daemon issue environmental (this WSL session) or a project-level config problem?
2. Does the user want Phase 2 research performed now, or should we proceed directly with a pre-selected feature?
3. Should `WorkspaceViewModel` be wired in or deleted?
4. What is the target release cadence — one PR per task, or batched by phase?
