# Cloudflare Computer integration

[Cloudflare Computer](https://github.com/cloudflare/computer) is a preview
SQLite-backed **virtual filesystem** for Cloudflare Durable Objects, built for
agents. It provides a durable, portable workspace (`workspace.fs`), pluggable
execution backends (`workspace.runtime.exec`: container shell, isolated worker
shell, isolated JavaScript), a git client (`workspace.git`, isomorphic-git),
and ready-made AI tools (`@cloudflare/computer/tools`).

This repo integrates it in two ways:

1. **Companion MCP server** (`ollamadev-mcp-server`) → a `cloudflare_computer`
   tool module that lets OllamaDev agents drive a remote Cloudflare Computer
   workspace over its HTTP surface (`read`/`write`/`ls`/`exec`/`git`).
2. **Agent skills/docs** → this guide + the `cloudflare-computer` skill so
   in-repo agents know how to reach and use it.

## Where the code lives

| Repo | Local path | Purpose |
| --- | --- | --- |
| `cloudflare/computer` | `/home/dev/computer` | The Cloudflare Computer package + docs + examples |
| `asshat1981ar/ollamadev-mcp-server` | `/home/dev/ollamadev-mcp-server` | Companion MCP server (Python) |
| `asshat1981ar/OllamaDev` | `/home/dev/OllamaDev` | The Android app (this repo) |

## Key Cloudflare Computer concepts

- **`Workspace.fs`** — node:fs-like async surface: `readFile`, `writeFile`,
  `mkdir`, `readdir`, `rm`, `stat`, `grep`, `find`, `ls`. Paths are absolute
  and POSIX. `readFile` returns a stream by default; pass `"utf8"` for a string.
- **`Workspace.runtime.exec(source, { backend })`** — one execution entry point.
  Backends: `container-shell` (full Linux userland), `worker-shell` (just-bash
  in a Dynamic Worker), `worker-javascript` (isolated ECMAScript module).
- **`Workspace.git`** — `add`, `clone`, `commit`, `log`, `push`, `pull`, `diff`,
  `status`, `branch`, `checkout`, `stash`, `tag`, `remote`, `fetch`, `merge`,
  `reset`, `rm`, `show`, `clean`, `config`, `ls-files`, `ls-tree`, `cat-file`,
  `rev-parse`, `symbolic-ref`, `switch`, `update-ref`. HTTPS/HTTP/file only (no
  SSH). Enabled via `createGitClient()` from `@cloudflare/computer/git`.
- **Agent tools** — `@cloudflare/computer/tools` ships `read`, `write`, `edit`,
  `ls`, `exec`, `publish` for the Vercel AI SDK.

## HTTP surface (what the MCP tools proxy)

The computer repo's example Workers (`examples/container`, `examples/worker-shell`)
expose a simple HTTP surface:

```
PUT  {base}/c/{workspace}/file/workspace/{path}   write a file
GET  {base}/c/{workspace}/file/workspace/{path}   read a file
POST {base}/c/{workspace}/exec                    run a shell command (JSON)
```

## Enabling the MCP tools

Set the env vars for the companion server, then start it:

```bash
cd /home/dev/ollamadev-mcp-server
uv sync --extra dev --prerelease=allow
export CF_COMPUTER_BASE_URL=http://127.0.0.1:8787   # Worker hosting the DO
export CF_COMPUTER_WORKSPACE=compute
uv run serve
```

The server exposes these tools (see `cf_workspace_status` first to verify the
connection):

| Tool | Purpose |
| --- | --- |
| `cf_workspace_status` | Connectivity + config + circuit-breaker health |
| `cf_list_workspace` | List a directory (via exec surface) |
| `cf_read_workspace_file` | Read a file (base64 for binary via `binary=True`; 2 MiB cap) |
| `cf_write_workspace_file` | Write/overwrite a file (`content=` or base64 `content_b64=`; audited) |
| `cf_exec_workspace` | Run a shell command or safely-quoted `argv` (gated + audited) |
| `cf_git_workspace` | Run a validated `git` subcommand in the workspace (gated if mutating + audited) |

`cf_exec_workspace` and `cf_git_workspace` are annotated `destructiveHint=true`
so OllamaDev's MCP risk gate requires human approval before they run. The module
also validates config (http(s) base URL, workspace name charset, bounded
timeouts), retries transient network errors with backoff, gates a broken
endpoint behind a circuit breaker, sanitizes paths (rejects `..`, control
chars), refuses shell metacharacters in `git` args, and caps exec output at
256 KiB — see the mcp-server README for details.

## Status

Cloudflare Computer is **PREVIEW ONLY** — APIs are unstable and design is subject
to change. Suitable for experiments and prototypes, not production. See the
package's `docs/` for the forward-looking spec.