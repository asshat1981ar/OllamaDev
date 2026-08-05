# Task 8 Report: LlmRouter agent-prompt directive docs (Tier 6.6)

## Status
DONE

## What I implemented
Extended `LlmRouter` so agent-generated prompts document the action-directive formats that
`AgenticActionExecutor` parses, plus approval-gate awareness and a standards reminder — **scoped to
`generateForAgent` only** (NOT `generateFreeform`/`routePrompt`).

### Change in `app/src/main/java/com/example/data/LlmRouter.kt`
- `generateForAgent` now appends `buildDirectiveContext()` after `buildSkillsContext()`:
  `agent.systemPrompt + buildSkillsContext() + buildDirectiveContext()`.
- New private `buildDirectiveContext()` returns a compact directive reference block:
  - `WRITE_FILE: <path>` — writes the file (content diff goes through human review)
  - `MCP_CALL: <tool name> | <json arguments object>` — invoke a connected MCP tool
  - `git <subcommand>` — e.g. `git commit -am "msg"`, `git push`
  - Approval-gate line: `git push` and risky/destructive MCP calls pause for human approval;
    declined actions are skipped.
  - One-line standards reminder.
- `generateFreeform` / `generateFreeformStreaming` / `routePrompt` are untouched — they never call
  `buildDirectiveContext()`, so their prompts stay clean.

The directive formats mirror exactly what `AgenticActionExecutor.parseAndExecute` recognizes
(`git ` / `$ git `, `MCP_CALL:`, `WRITE_FILE:`) and the git-push / risky-MCP approval gates in
`executeAgenticGitCommand` / `executeAgenticMcpCall`.

### Test changes in `app/src/test/java/com/example/data/LlmRouterTest.kt`
Added 3 tests (2 new + counted into suite):
- `generateForAgent_documentsDirectiveReference` — asserts the agent system prompt contains
  `WRITE_FILE: <path>`, `MCP_CALL: <tool`, `git `, and substrings "approval" and "standards".
- `generateFreeform_doesNotContainDirectiveReference` — asserts freeform prompt does NOT contain
  `WRITE_FILE:` or the `AGENT DIRECTIVES` marker.
- `routePrompt_doesNotContainDirectiveReference` — asserts routePrompt prompt does NOT contain
  `WRITE_FILE:` or the `AGENT DIRECTIVES` marker.
- Added `import org.junit.Assert.assertFalse`.

## TDD RED/GREEN evidence

### RED (before implementation)
Command:
```
cd /home/dev/OllamaDev && JAVA_TOOL_OPTIONS='-Djava.net.preferIPv6Addresses=true' ./gradlew :app:testDebugUnitTest --tests '*LlmRouter*' --console=plain
```
Run in background via `setsid ... &` (the run_commands wrapper kills foreground children at 30s;
setsid detaches so the build survives). Polled /tmp/t8.log.

Key output:
```
> Task :app:testDebugUnitTest
LlmRouterTest > generateForAgent_documentsDirectiveReference FAILED
    java.lang.AssertionError at LlmRouterTest.kt:59
> Task :app:testDebugUnitTest FAILED
BUILD FAILED in 3m 24s
```
The new `generateForAgent_documentsDirectiveReference` failed as expected (WRITE_FILE not yet
documented). The negative tests passed (freeform/routePrompt don't contain the block).

### GREEN (after implementation)
Same command, fresh log /tmp/t8-green.log. Output:
```
BUILD SUCCESSFUL in 2m 49s
32 actionable tasks: 7 executed, 25 up-to-date
```
Test report `TEST-com.example.data.LlmRouterTest.xml`:
```
<testsuite name="com.example.data.LlmRouterTest" tests="6" skipped="0" failures="0" errors="0">
```
All 6 tests pass, including the 2 untouched existing tests
(`generateForAgent_appendsSkillsContextToSystemPrompt`, `generateFreeform_picksOnlineNode`,
`generateFreeform_noOnlineNode_returnsConfigureMessage`).

## Files changed
- `app/src/main/java/com/example/data/LlmRouter.kt` (modified, +17/-1)
- `app/src/test/java/com/example/data/LlmRouterTest.kt` (modified, +57)

## Environment note
WSL IPv4 loopback is broken; every Gradle JVM needed
`JAVA_TOOL_OPTIONS='-Djava.net.preferIPv6Addresses=true'` (documented in the run-ollamadev skill).
No maven deps were missing (all cached), so no /tmp/m2repo workaround was needed. `gradle.properties`
was NOT modified.

## Self-review
- SCOPED: directive block only added in `generateForAgent`; freeform/routePrompt untouched —
  verified by negative tests and by code review.
- Matches executor parsing exactly (WRITE_FILE / MCP_CALL / git prefixes) and the git-push +
  risky-MCP approval gates.
- Char-budget conscious: single terse block appended after the skills block.
- Existing LlmRouterTest tests still pass (no regressions).
- Only the two intended files were git-staged/committed.

## Concerns
None. (Note: repo working tree contains many other pre-existing modified/untracked files from other
tasks; I committed only the two files for this task.)