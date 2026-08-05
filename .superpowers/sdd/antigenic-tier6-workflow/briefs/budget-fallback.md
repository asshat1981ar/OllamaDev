# Antigenic Fix Brief: Budget Fallback

You are responding to an antigenic signal in OllamaDev.

Signal: `BUDGET_OVERRUN`
Context: taskId=${taskId}, detail=${detail}

## Goal
Make the AGENTIC_LOOP gracefully fall back to local Ollama nodes when the cloud-token cap is exceeded, instead of halting entirely. Preserve the existing `BUDGET_HALT` TaskStep behavior so the user still sees why.

## Constraints
- Re-read `app/src/main/java/com/example/data/TaskBudgetTracker.kt` and `app/src/main/java/com/example/data/SwarmEngine.kt` before editing.
- Add a test in `SwarmEngineBudgetGuardrailTest` that asserts fallback-to-local is attempted after cap exceeded.
- Follow TDD: RED → GREEN.
- Verify via `run-ollamadev` skill.
- Do not commit.

## Deliverables
1. `TaskBudgetTracker` exposes a `preferCloudAfterCap` boolean (default false) for the current task.
2. `SwarmEngine` flips this flag on `BUDGET_OVERRUN` and uses `preferCloud = false` for subsequent calls in the same task.
3. Test passes.
4. Report to `.superpowers/sdd/antigenic-tier6-workflow/reports/budget-fallback-report.md`.
