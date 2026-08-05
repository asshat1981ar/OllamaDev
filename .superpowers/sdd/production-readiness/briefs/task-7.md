# Task 7: Budget heuristic documentation (Tier 6.5)

## Task Description

The cloud-token cap uses a character-length heuristic
`((prompt.length + output.length) / 2) + 100`. Make the estimate's
nature and error bounds visible to users in the Budget UI copy.

## Files

- Modify: `/home/dev/OllamaDev/app/src/main/java/com/example/ui/BudgetScreen.kt`

## Acceptance

- BudgetScreen copy explains: the count is an *estimate* based on character
  length / 2 + 100 (not a real tokenizer), it is approximate (± variance vs
  real tokenizers), and the cap is enforced between loop iterations (an
  in-progress LLM call may exceed it). Cap 0 = unlimited/legacy.
- `./gradlew :app:compileDebugKotlin` green (background, poll log).

## Steps

1. Read `BudgetScreen.kt`; find where the cap/edit UI renders.
2. Add the explanatory copy (concise; follow existing UI tone).
3. Compile: `cd /home/dev/OllamaDev && nohup ./gradlew :app:compileDebugKotlin --console=plain >/tmp/t7.log 2>&1 &` then poll until done.
4. Commit: `docs: document budget token heuristic (tier 6.5)` (only BudgetScreen.kt).

## Context

- Repo: /home/dev/OllamaDev, branch main.
- Estimate formula used by TaskBudgetTracker/AgentStateStore:
  `(prompt.length + output.length) / 2 + 100` characters.
- Never change gradle.properties tuning; JAVA_HOME unset = OpenJDK 17.
