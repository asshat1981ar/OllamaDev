# Task 1: WIP reconciliation — commit valuable work, delete the orphan

## Task Description

The OllamaDev working tree holds intentional uncommitted work (Tier-6/antigenic
subagent system + headless-approval tests + infra tuning) mixed with an orphan
file that must be deleted. Reconcile to a clean, committed baseline.

## Current WIP inventory (as of this task)

**Modified tracked files (review each diff; KEEP intended work, revert incidental):**
- `app/src/main/java/com/example/data/AgenticActionExecutor.kt`
- `app/src/main/java/com/example/data/PendingApprovalStore.kt`
- `app/src/main/java/com/example/data/SprintOrchestrator.kt`
- `app/src/main/java/com/example/data/SwarmEngine.kt`
- `app/src/main/java/com/example/data/TaskBudgetTracker.kt`
- `app/src/main/java/com/example/service/AgenticLoopService.kt`
- `app/src/main/java/com/example/ui/TaskStepComponents.kt`
- `app/src/test/java/com/example/data/SwarmEngineBudgetGuardrailTest.kt`
- `gradle.properties`, `settings.gradle.kts` (infra tuning — review: keep only intentional changes; gradle.properties heap/workers tuning is deliberate per run-ollamadev)
- `.claude/skills/run-ollamadev/SKILL.md` (WSL env notes — keep)
- `agent-os/backlog.md` (Tier-6 updates — keep)

**Untracked main/test sources (KEEP + commit if they compile):**
- `app/src/main/java/com/example/data/AntigenicDispatcher.kt`, `AntigenicOrchestrator.kt`, `AntigenicResponse.kt`, `AntigenicSignal.kt`, `AntigenicSignalStore.kt`
- `app/src/main/java/com/example/data/SubagentLauncher.kt`
- `app/src/test/java/com/example/data/AgenticActionExecutorHeadlessApprovalTest.kt`
- `app/src/test/java/com/example/data/AntigenicDispatcherTest.kt`, `AntigenicOrchestratorTest.kt`, `AntigenicSignalStoreTest.kt`, `AntigenicSignalTest.kt`

**Untracked docs/plans/design (judgment):** `agent-os/design/`, `agent-os/plans/`, `docs/superpowers/plans/2026-07-30-antigenic-tier6-workflow.md`, `docs/superpowers/specs/2026-07-29-augmented-team-prompt-design.md` — these are the project's own planning docs; commit them if they are non-runtime, non-generated artifacts.

**DELETE (orphan):** `app/src/main/java/com/example/viewmodel/WorkspaceViewModel.kt` (verdict: `agent-os/plans/workspace-viewmodel-verdict.md` — unreferenced duplicate; delete, confirm no main/test references).

**Do NOT commit (runtime/generated/secret):** `store/tool_call_history.json`, `store/agent_memory.json`, `store/*.log`, `.env`, `node_modules/`, `package.json/package-lock.json` in repo root if created, `build/`, `.gradle/`.

## IMPORTANT (from prior tasks)

- `app/src/test/java/com/example/data/SwarmEngineVerifyPromptTest.kt` is ALREADY committed (Task 5). Do NOT re-add it.
- `LlmRouter.kt`/`LlmRouterTest.kt` committed (Task 8); `BudgetScreen.kt` (Task 7); `AppDatabase.kt`/`MainActivity.kt`/ADR-0002 (Task 6). Do not touch their already-committed content.

## Steps

1. Inventory: `cd /home/dev/OllamaDev && git status --short`. 
2. For each modified tracked file, `git diff <file>`; decide keep vs revert. Revert incidental changes with `git checkout -- <file>` (or `git restore`).
3. Compile kept work: `nohup ./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --console=plain >/tmp/t1.log 2>&1 &` then poll. Record failures — they gate commit eligibility.
4. `git rm app/src/main/java/com/example/viewmodel/WorkspaceViewModel.kt`; confirm `grep -rn WorkspaceViewModel app/src` has no main/test references.
5. `git add` the kept modified files + untracked sources that compiled + the judgment docs/plans. Commit: `chore: reconcile WIP (antigenic subagent system, headless approval tests, infra tuning)`.
6. Leave runtime/generated/secret files untracked. List anything left untracked in your report.

## Context

- Repo: /home/dev/OllamaDev, branch main. JAVA_HOME unset = OpenJDK 17.
- Never change gradle.properties tuning (the heap/workers values are deliberate).
- If maven deps missing due to WSL IPv4 loopback, use the run-ollamadev skill workaround.
- Do NOT push.