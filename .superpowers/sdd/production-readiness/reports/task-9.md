# Task 9 Report: MCP server ops — launcher + CI + README

**Status:** DONE
**Commit:** `eef6fbf` — `ops: launch script + CI + README for mcp-server`
**Repo:** `/home/dev/ollamadev-mcp-server` (branch `main`)

## What I did

1. **`scripts/launch_server.sh`** (new, executable `100755`):
   - Venv-aware: prefers `<repo>/.venv/bin/python`, falls back to `python3` on PATH, errors out if neither.
   - Exports `WORKSPACE_ROOT=/home/dev/OllamaDev` only when the env var is unset (does not clobber an explicit override).
   - Prints the MCP URL (`http://localhost:${PORT}/mcp`, honoring `SERVER_PORT`).
   - Logs server output to `<repo>/server_run.log` (already gitignored via `*.log`), `exec`s `server.py` so signals/PIDs map directly to the server.
   - `set -euo pipefail`, `bash -n` syntax-checked.

2. **`.github/workflows/test.yml`** (new): runs on `push` + `pull_request` to `main`; `ubuntu-latest`, Python 3.11 via `astral-sh/setup-uv@v5`, `uv sync --extra dev --prerelease=allow`, `uv run pytest tests/ -q`. (The older `python-ci.yml` was left untouched — task only asked to add `test.yml`.) YAML validated.

3. **README.md**: replaced the minimal `## Run` block with an **Ops / Run** section containing quick start (launcher + uv), a server host/port hint, an env-vars table (`WORKSPACE_ROOT`, `OLLAMA_URL`, `CF_COMPUTER_BASE_URL`/`WORKSPACE`/`TIMEOUT`, `METRICS_PORT` + `METRICS_HOST`), a curl `initialize` health-check snippet, and a note that the default `WORKSPACE_ROOT` is `/home/userland/OllamaDev` and **should be set** to the real workspace.

## Verification evidence

1. **Launcher + MCP handshake** (single background session, then killed):
   - Launcher output: `WORKSPACE_ROOT unset - defaulting to /home/dev/OllamaDev`, `python: /home/dev/ollamadev-mcp-server/.venv/bin/python`, `MCP URL: http://localhost:5000/mcp`.
   - `server_run.log`: `INFO: Uvicorn running on http://0.0.0.0:5000`.
   - `curl -X POST http://localhost:5000/mcp -H 'Content-Type: application/json' -H 'MCP-Protocol-Version: 2025-06-18'` with an `initialize` payload returned:
     `event: message` / `data: {"jsonrpc":"2.0","id":1,"result":{...,"protocolVersion":"2025-06-18","serverInfo":{"name":"OllamaDev Toolbox","version":""}}}` (JSON-RPC result — no 406 needed since `Content-Type` + `MCP-Protocol-Version` were sent).
   - Server killed afterward; `pgrep` confirmed no leftover `server.py` process.

2. **Test suite**: `uv run pytest tests/ -q` → `481 passed in 5.70s` (matches expectation, no regression).

3. **Commit hygiene**: `git show --stat HEAD` = exactly `.github/workflows/test.yml`, `README.md`, `scripts/launch_server.sh`; script staged as `create mode 100755`. Pre-existing uncommitted `client_demo.py` change was left out and untouched.

## Files changed

- `scripts/launch_server.sh` (new, +42, mode 100755)
- `.github/workflows/test.yml` (new, +32)
- `README.md` (modified, +41/−11)

## Self-review

- Launcher honors explicit env override but defaults sensibly; hardcodes `/home/dev/OllamaDev` default per task brief (repo-local; noted in README that the server's own code default is `/home/userland/OllamaDev`).
- CI matches brief exactly (branch triggers, runner, Python 3.11, uv commands). Pre-release pin `mcp[cli]==2.0.0rc1` is accommodated by `--prerelease=allow`.
- Run-level verification done locally; CI itself will fully execute on push/PR to `main` (no remote push performed — repo log shows 1 unpushed commit on top of the pre-existing unpushed state).

## Concerns

- None blocking. Minor: CI will run both `test.yml` (uv/3.11) and the legacy `python-ci.yml` (pip/3.12), so a single push triggers two test jobs — harmless duplication; consider retiring `python-ci.yml` in a future ops task.
- Remote verification requires actually pushing to `origin/main`, which was outside this task's scope; the workflow definition is YAML-validated.

Report file: `/home/dev/OllamaDev/.superpowers/sdd/production-readiness/reports/task-9.md`
