# Task 8: LlmRouter agent-prompt directive docs (Tier 6.6)

## Task Description

`LlmRouter.buildSkillsContext()` appends only the MCP skills block to agent
system prompts — the `WRITE_FILE:` / `MCP_CALL:` / git directive formats that
`AgenticActionExecutor` actually parses are undocumented to agents. Extend the
appended context for **`generateForAgent` only** (NOT `generateFreeform` /
`routePrompt` callers such as the routing orchestrator, consensus moderator,
distillation) with:
- a compact directive reference (`WRITE_FILE: <path>`, `MCP_CALL: <tool> | <json>`, git commands),
- approval-gate awareness (git push / risky MCP calls pause for human approval),
- one-line standards reminder.
Char-budget conscious (the agent prompt already carries a skills block).

## Files

- Modify: `/home/dev/OllamaDev/app/src/main/java/com/example/data/LlmRouter.kt`
- Test: `/home/dev/OllamaDev/app/src/test/java/com/example/data/LlmRouterTest.kt` (or a new `LlmRouterDirectiveDocsTest.kt`)

## Acceptance (TDD)

- `generateForAgent` prompt contains the directive reference (e.g. a token like
  `WRITE_FILE:` plus `MCP_CALL:`).
- `generateFreeform` / `routePrompt` prompts do NOT contain it.
- Existing tests pass; no unrelated changes.

## Steps

1. Read `LlmRouter.kt` — find `buildSkillsContext()` and the `generateForAgent`
   scope (follow existing `FakeAppDatabase`/test patterns).
2. Write failing test(s) asserting the above acceptance; run in background:
   `cd /home/dev/OllamaDev && nohup ./gradlew :app:testDebugUnitTest --tests '*LlmRouter*' --console=plain >/tmp/t8.log 2>&1 &` and poll. Expect the generateForAgent-assertion to FAIL first.
3. Implement the scoped augmentation in LlmRouter.
4. Re-run until green (targeted), then the surrounding `LlmRouterTest` class.
5. Commit: `feat(swarm): document agent directives in LLM context (tier 6.6)` (only these files).

## Context

- Repo: /home/dev/OllamaDev, branch main.
- Environment: gradle background+poll; cold Robolectric 2-4 min; WSL IPv4
  workaround documented in run-ollamadev skill; never change gradle.properties.
