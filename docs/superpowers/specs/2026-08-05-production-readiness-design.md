# OllamaDev Production-Readiness Design

**Date:** 2026-08-05
**Status:** Approved for planning (`writing-plans` next)
**Owner:** asshat1981ar · orchestrated via sub-agent driven development
**Scope:** OllamaDev (Android app) + ollamadev-mcp-server (companion)

## Problem

OllamaDev ships an impressive but unfinished feature surface. The app is at
`versionCode=1 / versionName="1.0"` with no reproducible release path, the
companion MCP server works but has at least one confirmed runtime defect, and
the working tree holds substantial unverified WIP. "Production ready and
polished" means: a green, reproducible build + test baseline; known defects
fixed and regression-locked; a versioned, releasable artifact path; a
hardened, documented MCP server; and docs/state that match reality.

## Current-state findings (2026-08-05)

### OllamaDev app
- Kotlin/Compose Material-3, single `SwarmViewModel`, `SwarmEngine` agentic
  harness, Streamable-HTTP MCP client, JGit, Room.
- Backlog Tiers 1–5 complete; Tier 6: 6.1 open, 6.2 and 6.3 done, 6.4/6.5/6.6 open.
- `versionCode=1`, `versionName="1.0"`, `applicationId com.ollamaswarm.app`,
  `minSdk=24`, `targetSdk=34`.
- No built APK on disk; build state unverified.
- Working tree dirty: ~12 modified tracked files, ~24 untracked paths
  (new `Antigenic*` subagent system, orphan `WorkspaceViewModel.kt`, modified
  `settings.gradle.kts` / `gradle.properties`).
- No `.env` file (only `.env.example`); `OLLAMA_API_KEY` absent locally.
- 46 KotlinUnit-test files on disk.
- Known defects: verify-prompt `decision` bug (6.1), Room
  `fallbackToDestructiveMigration` data-wipe risk (6.4), char-length token
  heuristic (6.5), LlmRouter agent prompt missing directive docs (6.6).
- Stale README (signing step), 195 raw hex colors bypassing design tokens,
  dead Android Studio template resources.

### ollamadev-mcp-server
- Verified ONLINE in `.venv` (Python 3.11.15); 56 tools registered; `ping`,
  `describe_tools`, `list_workspace_files`, `read_workspace_file`,
  `get_file_outline` all verified through the real MCP client.
- 480 unit tests pass.
- Live defect: `suggest_next_action` throws
  `NameError: cannot access local variable 'model'`.
- Default `WORKSPACE_ROOT` points at non-existent `/home/userland/OllamaDev`;
  server must be launched with `WORKSPACE_ROOT` set.
- No CI or launcher workflow in the mcp-server repo; README documents manual
  `uv run serve` only.

## Design

Five workstreams, each owned by one subagent, orchestrated centrally:

| WS | Title | Goal / exit criteria |
|----|-------|---------------------|
| WS1 | Green baseline | `assembleDebug` + `testDebugUnitTest` green on HEAD; reconcile WIP (commit valuable work: Antigenic system + tests; delete orphan `WorkspaceViewModel.kt`; keep infra tuning); restore `.env` from `.env.example`; add a `RELEASE` build type and signing placeholder guarded by env. |
| WS2 | Defect sweep | Fix `suggest_next_action` NameError; verify/fix AGENTIC_LOOP verify prompt (6.1) + regression test; Room migration safeguard (6.4) via `MIGRATION_14_15` + documented policy; budget heuristic documentation (6.5); LlmRouter directive docs in agent prompts (6.6). Each fix TDD + targeted unit test. |
| WS3 | Release engineering | Version scheme from git (`versionCode` = commits or CI build number, `versionName` = git describe); `CHANGELOG.md` + release-notes template; CI release workflow wiring for signed APK (env-secret guarded); README refresh. |
| WS4 | MCP server ops | Fix live bug; `scripts/launch_server.sh` (venv-aware, WORKSPACE_ROOT-aware, health check); `.github/workflows/test.yml` for the mcp-server repo; README ops section; config validation doc. |
| WS5 | Docs & hygiene | CHANGELOG/README/RELEASE_NOTES sync; PRD/HLD/LLD status alignment; design-token cleanup guidance; CI status note; verify whole repo builds + tests one final time. |

## Constraints / guardrails

- Respect the container: 2 core / ~5.3 GB RAM Gradle tuning in
  `gradle.properties` is deliberate — do not raise heap.
- `run-ollamadev` skill is the authority for build/test/screenshot commands
  and known environment traps (WSL IPv4 loopback, QEMU aapt2, no emulator).
- All changes land on `main` via separate focused commits; no force pushes.
- Secrets: no `.env` contents committed; signing keys use env-only placeholders.
- Each subagent verifies with real commands before declaring done
  (compile/test), per `verification-before-completion`.

## Risks

- Long cold Gradle/Robolectric runs (2–4 min first class) — budget time,
  keep `forkEvery=1` semantics.
- WIP reconciliation could discard intended work — subagent must diff-review
  each modified file before dropping.
- Dependency scans are report-gated (critical-only) — acceptable for now.

## Success metrics

- `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` green.
- `suggest_next_action` returns a suggestion (or a clean, documented error)
  against a live server.
- `versionCode`/`versionName` derived reproducibly; a release APK path exists.
- mcp-server test suite green; launcher script + CI present.
- Docs match the codebase.
