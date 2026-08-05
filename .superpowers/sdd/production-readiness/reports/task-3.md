# Task 3 Report: Fix `suggest_next_action` NameError (mcp-server)

## Status
DONE

## Summary
The `suggest_next_action` MCP tool threw a live `UnboundLocalError: cannot access
local variable 'model' where it is not associated with a value` (reported/shown as a
`NameError` in `data.reasoning`) whenever the Ollama provider was selected while the
server was pointed at Ollama Cloud (`OLLAMA_URL` containing `ollama.com`).

Root cause: in `ollamadev_mcp_server/tools/meta.py`, `_ask_ollama._do_request` (a
closure) assigned `model = (DEFAULT_CLOUD_MODEL or ...)` inside the `is_cloud` branch.
Per Python scoping rules, the presence of that assignment made `model` a **local**
variable of `_do_request`, so the read of `model` in the very next guard condition
`if is_cloud and (not model or model == "llama3")` hit an unbound local **before** any
network call — on Python 3.11 the error message is exactly
`"cannot access local variable 'model' where it is not associated with a value"`.
The exception was swallowed by the handler's `except Exception` branch and surfaced as
`"Could not parse model response: cannot access local variable 'model'..."`.

## Files Changed
- `ollamadev_mcp_server/tools/meta.py` — fixed variable scoping in `_ask_ollama._do_request`.
- `tests/test_meta.py` — added regression test
  `test_suggest_next_action_ollama_cloud_no_nameerror`.

Commit: `c463b3c fix(meta): resolve suggest_next_action NameError` (branch `main`).

## Root Cause Detail
The original code:

```python
is_cloud = "ollama.com" in OLLAMA_URL
if is_cloud and (not model or model == "llama3"):
    model = (DEFAULT_CLOUD_MODEL or "kimi-k2.7-code").split(":")[0]   # rebinds closure var
```

Because `_do_request` is a nested function, assigning `model` inside it makes `model`
local to `_do_request` for the whole frame. `not model` in the condition therefore
referenced the unbound local. This fired unconditionally whenever `is_cloud` was True,
regardless of the model argument.

Fix: read the parameter into a genuinely local `request_model` and never rebind the
closure variable:

```python
request_model = model
if is_cloud and (not request_model or request_model == "llama3"):
    request_model = (DEFAULT_CLOUD_MODEL or "kimi-k2.7-code").split(":")[0]
...
"model": request_model,   # both is_cloud and non-cloud payload branches
```

`request_model` is always bound (assigned from the parameter immediately), so neither
branch can reference an unbound local.

## TDD Evidence

### RED (before fix)
New regression test `test_suggest_next_action_ollama_cloud_no_nameerror` drives the tool
through `mcp.call_tool("suggest_next_action", ...)` with `provider="ollama"`, a
monkeypatched `meta.OLLAMA_URL = "https://ollama.com"`, a pass-through circuit breaker,
and a faked `requests.post` so the model response itself is valid. It asserts a
`run_gradle_tests` recommendation and that the reasoning does not contain
`cannot access local variable`.

```
$ uv run pytest tests/test_meta.py -q
[ 92%]                                                              ...................F....
>       assert data["tool_name"] == "run_gradle_tests"
E       AssertionError: assert None == 'run_gradle_tests'
FAILED tests/test_meta.py::test_suggest_next_action_ollama_cloud_no_nameerror
1 failed, 10 passed in 1.00s
```

### GREEN (after fix)
```
$ uv run pytest tests/test_meta.py -q
...........                                    [100%]
11 passed in 0.83s
```

### Full suite (no regressions)
```
$ uv run pytest tests/ -q
481 passed in 5.74s
```
(480 pre-existing tests pass, plus the 1 new regression test; brief's baseline of 480
confirmed green.)

## Live Verification Steps

1. Reproduced the failure with a live server on `0.0.0.0:5000`:
   ```
   WORKSPACE_ROOT=/home/dev/OllamaDev OLLAMA_URL=https://ollama.com \
   OLLAMA_API_KEY=dummy-key .venv/bin/python server.py
   ```
   then `tools/call suggest_next_action` via the MCP HTTP client (pattern from
   `client_demo.py`; helper script at `/tmp/mcp_call_suggest.py`).

   Before fix (0.15 ms — failed before any network I/O):
   ```json
   {
     "success": true,
     "tool": "suggest_next_action",
     "duration_ms": 0.15,
     "data": "{\n  \"tool_name\": null,\n  \"arguments\": {},\n  \"reasoning\": \"Could not parse model response: cannot access local variable 'model' where it is not associated with a value\",\n  \"confidence\": 0.0\n}"
   }
   ```

2. Restarted the server with the same env after committing the fix and re-called the
   tool. The cloud branch now reaches the provider (4,494 ms — the actual HTTP request
   executes) and returns a clean, documented provider error instead of a NameError:
   ```json
   {
     "success": true,
     "tool": "suggest_next_action",
     "duration_ms": 4494.83,
     "data": "{\n  \"tool_name\": null,\n  \"arguments\": {},\n  \"reasoning\": \"Could not parse model response: 401 Client Error: Unauthorized for url: https://ollama.com/api/chat\",\n  \"confidence\": 0.0\n}"
   }
   ```
   The 401 is expected here — this sandbox has no real Ollama API key; the point is
   that the provider-selection code no longer raises the unbound-local NameError and
   the tool always returns valid JSON with the documented key set (`tool_name`,
   `arguments`, `reasoning`, `confidence`).

3. Background servers were killed afterwards: no `server.py` process remains and TCP
   port 5000 was verified free.

Notes on the demo environment: a pre-existing `server.py` (pid 3410) was bound to port
5000 and was stopped to allow a controlled server restart for this task; it had been
started with only `WORKSPACE_ROOT` set (default localhost `OLLAMA_URL`), so it could not
hit the cloud branch. The local Ollama at `:11434` only exposes `kimi-k2.7-code:cloud`
which requires a paid subscription, and `ollama.com` requires an API key
(unauthenticated `/api/chat` returns 401), so a fully successful recommendation could
not be produced in this sandbox; the live proof above (NameError → executed request with
documented error) is the faithful reproduction of the reported condition.

## Self-Review
- Both branches of `_ask_ollama` (`is_cloud` chat path and legacy generate path) now use
  `request_model`, which is always bound — nothing can reference an unbound local.
- `provider == "anthropic"` (`_ask_anthropic`) was audited: it reads `model` only (never
  rebinds it), so it was never affected.
- New test exercises the exact previously-broken path through the public MCP tool
  interface, with the model provider mocked so it is deterministic and network-free.
- Only task-scope files were committed. `client_demo.py` carried a pre-existing
  uncommitted change (`localhost` → `127.0.0.1` SERVER_URL) that predates this task; it
  was deliberately excluded from the commit.
- Full suite green (481 passed), no regressions.

## Concerns
- None functional. Two environmental notes, not blocking:
  1. This sandbox lacks a real Ollama cloud API key, so live verification of the cloud
     branch stops at a documented 401 error rather than a generated recommendation.
     In an environment with valid credentials the same code path returns the parsed
     recommendation (covered by the passing regression test with a mocked provider).
  2. The MCP client (`mcp==2.0.0rc1`) emitted a teardown exception-group traceback
     after receiving a successful response; this appears to be a client/SDK teardown
     noise (also present pre-fix) and is unrelated to this task.

