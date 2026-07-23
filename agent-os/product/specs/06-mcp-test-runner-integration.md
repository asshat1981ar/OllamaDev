# Feature Spec: Real MCP Test-Runner Integration

Tier 3 · Backlog ref: `agent-os/backlog.md` #6 · PRD ref: key risk (reliability of "verification")

## Problem

The verify phase's whole value proposition is "invoke real tooling instead
of fabricating a result" — but no MCP server anywhere in this project
actually runs tests. Every `SwarmEngineVerifyLoopTest` scripts a
`FakeMcpClient` returning a canned pass/fail string. In production use, a
QA agent's `MCP_CALL: Run Tests | {}` has no real server to reach unless
the user has separately configured and connected one via the registry
browser — and no such server is documented as a known-good option today.

## Scoping decision required before starting

Pick (a) or (b) explicitly — don't start building without deciding:

**(a) Build a minimal reference execution-sandbox MCP server.** A small,
separate companion project (not part of this Android app) exposing one
tool (e.g. `run_tests`) that shells out to `gradle test`/`pytest`/etc.
inside a container and returns pass/fail + output. Highest control, but
real infra to build, host, and secure — this is a server the user's device
would need to reach over the network, with real execution risk (arbitrary
code execution by design) that needs careful sandboxing.

**(b) Wire up and document an existing open-source MCP test-runner.**
Search the MCP registry (`RegistryBrowserDialog.kt`'s existing browse flow)
for an already-built, already-hosted-or-self-hostable test-execution
server, and write setup documentation for connecting it to OllamaDev. Much
smaller scope, but depends on ecosystem availability and quality of an
external project.

Recommendation: start with (b) — search the registry first; only fall back
to (a) if nothing suitable exists, and if so scope (a) down hard (single
tool, single language/runtime, explicit sandboxing story) before writing
any code.

## Approach (once (a) or (b) is chosen)

1. Get a real server connected and `Connected` status in `McpServer`.
2. Exercise a real `initialize` → `tools/list` → `tools/call` round trip
   through the existing `McpClient` (per
   `agent-os/standards/mcp/dual-response-parsing.md` — this is exactly the
   kind of real-tool observation that standard's precedent already covers).
3. Bind it to a `ClaudeSkill` (existing skill-binding flow, `sourceToolName`)
   with `requiredMcpServerType` matching the new server's `type`.
4. Update the seeded "Autonomous Coding Harness" `SwarmConfig` demo (or
   document setup instructions) so a fresh install can exercise real
   verification without manual configuration, if practical.
5. Add annotations (`destructiveHint`/`readOnlyHint`) if the chosen server
   provides them, so `isRiskyMcpCall` gets real signal instead of falling
   back to keyword inference for this specific tool.

## Acceptance criteria

- [ ] A documented, repeatable setup path exists for connecting a real
      test-runner MCP server to this app (README or `agent-os/` doc).
- [ ] At least one manual end-to-end run of the "Autonomous Coding Harness"
      swarm config produces a genuine `EXEC_RESULT`/`EXEC_RESULT_FAILED`
      step from real tool output, not a scripted fake.
- [ ] If (a) was chosen: the sandbox's execution boundary/security model is
      written down explicitly, not left implicit.

## Files touched

Depends on (a) vs (b) — likely `app/src/main/java/com/example/data/AppDatabase.kt`
(seed data), documentation files, and possibly a new companion repo (if (a)).

## Standards to follow

- `agent-os/standards/mcp/remote-only-transport.md` — any server must be
  reachable over Streamable-HTTP; no stdio.
- `agent-os/standards/mcp/dual-response-parsing.md`.

## Out of scope

- Multi-language/multi-runtime sandbox support in v1 — pick one target
  runtime (e.g. Gradle/JVM, matching this app's own stack) and prove the
  concept before generalizing.
