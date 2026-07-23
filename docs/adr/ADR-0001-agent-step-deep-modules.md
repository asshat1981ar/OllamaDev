# ADR-0001: Extract agent-step deep modules out of SwarmEngine

- Status: Accepted
- Date: 2026-07-23

## Context

The five coordination-mode workflows in `SwarmEngine.kt` each inlined the same 8-beat agent-step
loop (set-active -> status -> THINKING placeholder -> delay -> stream into step row in place ->
parse directives -> replace with final output -> record metrics). The inline pattern was ~200
lines x 5 workflows. Changing how a step emits progress required editing five places; each mode
was only testable end-to-end via `executeTask`.

The LLM-routing logic (online-node selection, preferred-model matching, cloud-gateway preference,
skills context) was scattered through `generateOutputForAgentStreaming` and `generateFromFallbackPool`.
The directive-side-effect logic (git commands, `MCP_CALL:`, `WRITE_FILE:`) was a tangle of
helpers — `parseAndExecuteAgenticActions`, `executeAgenticGitCommand`, `executeAgenticMcpCall`,
`executeAgenticFileWrite` — and they reached into `PendingApprovalStore`, JGit, Keystore, and the
Room DB directly.

## Decision

Extract three deep modules out of `SwarmEngine`:

1. **`LlmRouter`** (seam `LlmRouterInterface`) — single Ollama-pool entry point. Owns
   online-node selection, preferred-model matching, cloud-gateway routing, and the skills context
   appended to agent system prompts. No cloud/Gemini fallback (preserves
   `agent-os/standards/backend/swarm-engine-llm-pool`).

2. **`AgenticActionExecutor`** (seam `AgenticActionExecutorInterface`) — parses agent output for
   directives and performs the real side effects (JGit mirror/commit/push, MCP tool call, file
   write) behind the human-approval gate. Reuses the injected `LlmRouter` for the `WRITE_FILE`
   content round-trip.

3. **`StepRunner`** (concrete, no interface per `interface-seam` standard since it touches no
   I/O directly) — the shared step loop. Owns: `AgentStateStore.setAgentActive` /
   `recordExecutionMetrics`, `TaskStepDao.insertStep` for the throttled streaming reinserts
   (preserving the `SwarmEngineStreamingTest` monotonicity contract), `AgenticActionExecutor
   .parseAndExecute`, and the inter-step `delay`.

`SwarmEngine` keeps its existing 7-arg public constructor and public `generateFreeform()` so all
8 pre-existing `SwarmEngine*Test.kt` files stay green without modification. The engine builds
the three modules internally as ordered `private val` property initializers (top-to-bottom
declaration order matters: `actionExecutor` depends on `llmRouter`; `stepRunner` depends on both).

The five coordination-mode workflows become thin policy: each composes a sequence of
`StepRunner.run(...)` calls with a per-step `StepRequest` (agent, prompt, actionTypes, label,
delays) plus a small amount of role-routing and context-accumulation logic that doesn't fit
the generic step shape (e.g. the consensus-vote moderator's JSON scoring step stays as a direct
`llmRouter.generateFreeformStreaming` call because it has no placeholder/metrics pattern; the
agentic loop's `autoCheckpoint` decision stays in the engine because it's per-iteration
policy).

## Consequences

- Step-emission behavior lives in one place (locality + leverage across all modes). Changing
  how a step emits progress is a single-file edit.
- Each module is unit-testable through its own interface (`LlmRouterTest`, `AgenticActionExecutorTest`,
  `StepRunnerTest`) without spinning up the full `executeTask` lifecycle.
- The Ollama single-entry-point standard is preserved (now `LlmRouter` owns the routing; the
  engine has exactly one `LlmRouter` instance and one `generateFreeform` delegate).
- `SwarmEngine` remains the composition root; construction is internal, so callers cannot yet
  inject a fake `StepRunner` from `SwarmViewModel`. Acceptable: `StepRunner` is pure
  orchestration covered via `LlmRouter` / `AgenticActionExecutor` fakes in the existing engine
  tests, which all pass without modification.
- The `8` existing `SwarmEngine*Test.kt` files are byte-identical to their pre-refactor versions
  on `main` (the only test edit on this branch predates this refactor: commit `4b92d47` fixed a
  monotonicity assertion that's still satisfied by the post-refactor `StepRunner` reinsert
  pattern). Mechanical proof: `git diff main..HEAD -- 'app/src/test/**/SwarmEngine*Test.kt'`
  shows the pre-existing assertion refinement only.

## Alternatives considered

- **Single combined `LlmRouter + StepRunner` module**: rejected — `LlmRouter` does I/O (node
  selection, service calls) and so requires a testable seam; `StepRunner` is pure orchestration
  with no I/O, so adding an interface for it would be a no-op stub in tests
  (per `agent-os/standards/data/interface-seam`).
- **Keep the inline 8-beat loop and only extract `LlmRouter` + `AgenticActionExecutor`**: rejected
  — leaves the largest source of duplication untouched. The deep-module split is what makes the
  workflows readable as policy.
