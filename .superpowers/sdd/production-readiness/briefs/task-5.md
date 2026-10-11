# Task 5: AGENTIC_LOOP verify-prompt regression (Tier 6.1)

## Task Description

Backlog/Tier-6.1: "confirm-or-fix: AGENTIC_LOOP verify-prompt `decision` bug".
RELEASE_NOTES flagged "verify prompt interpolates an unresolved `decision`
variable before it is declared."

Current evidence in `SwarmEngine.kt` (~line 430): `verifyPrompt` is built from
`actResult.output` (`actResult` declared above at ~line 419) — the bug **already
looks fixed**. This task: confirm with evidence (compile + targeted test) and
make sure a regression test asserting the verify prompt contains the act-step
output is committed and green.

A regression test ALREADY EXISTS as an untracked WIP file:
`app/src/test/java/com/example/data/SwarmEngineVerifyPromptTest.kt`
(contains `agenticLoop_verifyPromptContainsThatIterationsActOutput`). It is
expected to pass against the current engine.

## Files

- File to verify + commit: `app/src/test/java/com/example/data/SwarmEngineVerifyPromptTest.kt`
- Read-only reference: `app/src/main/java/com/example/data/SwarmEngine.kt` (~line 430)

## Steps

1. Confirm the fix: quote the `verifyPrompt` construction lines from
   `SwarmEngine.kt` in your report (shows actResult.output is used, not an
   unresolved variable).
2. Run the regression test (background, cold runtime 2–4 min):
   `cd /home/dev/OllamaDev && nohup ./gradlew :app:testDebugUnitTest --tests 'com.example.data.SwarmEngineVerifyPromptTest' --console=plain >/tmp/t5.log 2>&1 &` then poll `/tmp/t5.log` until BUILD SUCCESSFUL/FAILED.
3. If the test fails, fix it OR the engine minimally (respect run-ollamadev
   constraints: never change gradle.properties tuning). Iterate until green.
4. Commit the regression test file (ONLY that file):
   `fix(swarm): regression test for AGENTIC_LOOP verify prompt output (tier 6.1)`
   (plan's exact commit message is `fix(swarm): AGENTIC_LOOP verify prompt uses
   act output (tier 6.1)` — use the plan message if the fix made no code change
   otherwise; the test commit is what matters).
5. Note for the ledger: this file is committed by THIS task, so the WIP
   reconciliation task must NOT commit it again.

## Context

- Repo: /home/dev/OllamaDev, branch main.
- JAVA_HOME unset → OpenJDK 17; gradle tuning is deliberate — do not change.
- No emulator; JVM/Robolectric only. Poll logs — commands time out at 30s.
- run-ollamadev skill: /home/dev/OllamaDev/.claude/skills/run-ollamadev/SKILL.md
