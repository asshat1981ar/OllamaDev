# Antigenic Fix Brief: Verify Prompt Regression Lock

Signal: `VERIFICATION_UNRESOLVED` or explicit regression request
Context: taskId=${taskId}

## Goal
Confirm and lock the AGENTIC_LOOP verify-prompt construction so `actResult.output` is always passed into the QA prompt. Add a regression test that fails if the prompt is built from an unresolved or missing variable.

## Constraints
- Re-read `app/src/main/java/com/example/data/SwarmEngine.kt` lines ~386–450.
- Add `SwarmEngineVerifyPromptTest` asserting the QA prompt contains the act output.
- Do not commit.

## Deliverables
1. Regression test passes.
2. If a bug exists, fix it minimally.
3. Report to `.superpowers/sdd/antigenic-tier6-workflow/reports/verify-prompt-report.md`.
