# Task 3: Fix `suggest_next_action` NameError (mcp-server)

## Task Description

Fix the live `NameError: cannot access local variable 'model'` thrown by the
`suggest_next_action` tool in `ollamadev-mcp-server`.

## Files

- Modify: `/home/dev/ollamadev-mcp-server/ollamadev_mcp_server/tools/meta.py`
  (the `suggest_next_action` handler where `model` is referenced before
  assignment / shadowed)
- Test: extend `/home/dev/ollamadev-mcp-server/tests/test_meta.py`

## Interfaces / Acceptance

- `suggest_next_action(...)` returns a parsed recommendation dict with keys
  `tool_name`, `arguments`, `reasoning`, `confidence` — or a clean,
  documented error string. No `NameError`.
- Both `provider == "cloud"` and `provider == "ollama"` branches must
  compile and never reference an unbound local.

## Reproduce

With the server running:
```bash
cd /home/dev/ollamadev-mcp-server
WORKSPACE_ROOT=/home/dev/OllamaDev .venv/bin/python server.py &
# then call `tools/call suggest_next_action` via the mcp client
```
Confirm the NameError JSON (`data.reasoning` currently shows
"Could not parse model response: cannot access local variable 'model'...",
reported by the live server on 2026-08-05).

## Steps

1. Reproduce the failure (see above).
2. Write a failing test in `/home/dev/ollamadev-mcp-server/tests/test_meta.py`
   that drives the affected code path and asserts no `NameError` and a
   tool-shaped recommendation. Run `uv run pytest tests/test_meta.py -q` and
   confirm it fails on the bug.
3. Fix the variable scoping/assignment in `meta.py`.
4. Verify: `uv run pytest tests/test_meta.py -q` passes; then full suite
   `uv run pytest tests/ -q` all green.
5. Restart the server and re-call `suggest_next_action` via the MCP client;
   confirm it returns valid JSON (or a clean documented error) — record the
   live evidence in your report.
6. Commit: `fix(meta): resolve suggest_next_action NameError`

## Context

- Repo: `/home/dev/ollamadev-mcp-server` (branch `main`, in sync with origin).
- Venv: use `.venv/bin/python` / `uv run` (project < 1; `uv run` works from repo root).
- 480 tests currently pass; do not regress the rest of the suite.
- `meta.py` imports `with_retry`, `LLM_RETRY`, circuit breakers, and
  `_ask_anthropic` / `_ask_ollama*` helpers. The bug is in the
  `suggest_next_action` provider-selection code.
- Do NOT commit anything outside this task's scope (no unrelated fixes).
