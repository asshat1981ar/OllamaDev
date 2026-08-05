# Task 9: MCP server ops — launcher + CI + README

## Task Description

Make the OllamaDev MCP server operational: a launcher script (venv-aware,
WORKSPACE_ROOT-aware), a CI workflow that runs the test suite, and an ops
section in the README.

## Files

- Create: `/home/dev/ollamadev-mcp-server/scripts/launch_server.sh`
- Create: `/home/dev/ollamadev-mcp-server/.github/workflows/test.yml`
- Modify: `/home/dev/ollamadev-mcp-server/README.md` (Ops/run section)

## Acceptance

- `bash scripts/launch_server.sh` starts the server (reuse `.venv/bin/python`),
  exports `WORKSPACE_ROOT=/home/dev/OllamaDev` when the env var is unset,
  logs to a file or console, prints the MCP URL, and supports a
  `server.py`-equivalent start. Must be executable (`chmod +x`).
- `.github/workflows/test.yml` runs on push/PR to main: ubuntu-latest,
  Python 3.11, `uv sync --extra dev --prerelease=allow`, `uv run pytest tests/ -q`.
- README gets an "Ops / run" section: quick start, env vars table
  (WORKSPACE_ROOT, OLLAMA_URL, CF_COMPUTER_BASE_URL/WORKSPACE/TIMEOUT,
  METRICS_PORT), health-check snippet (curl initialize), and a note that the
  default WORKSPACE_ROOT is `/home/userland/OllamaDev` and should be set.

## Steps

1. Write `scripts/launch_server.sh` (see acceptance). `chmod +x`.
2. Write `.github/workflows/test.yml`.
3. Update README ops section.
4. Verify: `bash scripts/launch_server.sh` in background, curl/ping the MCP
   endpoint (handshake), then kill it. Run `uv run pytest tests/ -q` all green.
5. Commit: `ops: launch script + CI + README for mcp-server` (only these files).

## Context

- Repo: `/home/dev/ollamadev-mcp-server`, branch main, in sync with origin.
- Server starts via `server.py` with `WORKSPACE_ROOT` env override; listen
  host/port come from config (default 0.0.0.0:5000, streamable-http at /mcp).
- 481 tests pass; do not regress.
