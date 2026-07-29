# CONTEXT.md — Domain Glossary

## Deep modules (data/)

- **LlmRouter** — the single entry point for every LLM call; owns Ollama online-node selection,
  preferred-model matching, cloud-gateway routing, and the skills context appended to agent system
  prompts. No cloud/Gemini fallback. (`agent-os/standards/backend/swarm-engine-llm-pool`)

- **AgenticActionExecutor** — parses agent output for directives (git commands, `MCP_CALL:`,
  `WRITE_FILE:`) and performs the real side effects (JGit mirror/commit/push, MCP tool call, file
  write) behind the human-approval gate.

- **StepRunner** — the shared agent-step loop used by every coordination mode: set-active ->
  status -> THINKING placeholder -> delay -> stream into the step row in place -> parse directives
  -> replace with final output -> record metrics. Workflows supply only the agent, prompt, and
  action types via `StepRequest`.

- **Coordination mode** — the policy a `SwarmConfig` selects (`SEQUENTIAL`, `PEER_TO_PEER`,
  `CONSENSUS_VOTE`, `AGENTIC_LOOP`, `DYNAMIC_ROUTING`); a workflow in `SwarmEngine` that orders
  agents and builds prompts, delegating each step to `StepRunner`.

## Why these names

Before this refactor, the five coordination-mode workflows each inlined the same 8-beat agent-step
loop, scattered LLM routing logic across the engine, and let git/MCP/WRITE_FILE directive side
effects live as ad-hoc helpers. The three deep modules above are the natural decomposition: one
module owns the LLM call, one owns the directive side effects, one owns the step loop. The engine
becomes thin policy that composes them.
