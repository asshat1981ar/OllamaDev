# Antigenic Fix Brief: Test Failure Fix

Signal: `TEST_FAILURE`
Context: taskId=${taskId}, detail=${detail}

## Goal
A verification step invoked via the OllamaDev Sandbox MCP server returned a failing test result. Identify the root cause and apply the minimal code fix.

## Constraints
- Re-read the relevant production and test code first.
- Use the systematic-debugging skill: reproduce, trace, then fix.
- Add or update a regression test.
- Verify via `run-ollamadev`.
- Do not commit.

## Deliverables
1. Root cause stated.
2. Fix implemented.
3. Regression test passes.
4. Report to `.superpowers/sdd/antigenic-tier6-workflow/reports/test-failure-${taskId}-report.md`.
