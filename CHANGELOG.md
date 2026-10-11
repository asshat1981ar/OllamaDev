# Changelog

All notable changes to **OllamaDev** (Android app) and its companion
**ollamadev-mcp-server** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased] — production readiness polish

### Added

- **RELEASE build type + env-gated signing placeholder** (`235df28`).
  `app/build.gradle.kts` now declares a `release` `buildType`. A `release`
  `signingConfig` is created only when `OLLAMADEV_KEYSTORE_PATH` is set in the
  environment (with `OLLAMADEV_KEYSTORE_PASS` / `OLLAMADEV_KEYSTORE_ALIAS`); when
  the env vars are absent the release build is **unsigned** (safe for local
  builds/CI). Real keystore + store-upload automation remains explicitly out of
  scope for now.
- **VersionScheme — versionCode/versionName derived from git** (`e0d7a35`).
  New `scripts/version.sh` (`code` = `git rev-list --count HEAD`; `name` = latest
  semver tag or `1.0.0`, appending `-<short-sha>` when the worktree is dirty).
  `app/build.gradle.kts` wires these into `defaultConfig` via
  `versionCodeValue` / `versionNameValue`.
- **`suggest_next_action` NameError fix** (`c463b3c`, mcp-server). Resolved
  variable shadowing so `model` is always bound in both the `cloud` and `ollama`
  provider branches; the tool now returns a parsed recommendation dict or a clean
  error string instead of a `NameError`.
- **AGENTIC_LOOP verify-prompt regression coverage** (`d1d6c18`, tier 6.1).
  Added `SwarmEngineVerifyPromptTest` asserting the verify prompt actually
  contains the act-step output, closing the flagged
  "verify prompt interpolates unresolved `decision`" failure mode with a
  regression test (the bug was confirmed already-fixed).
- **Room migration policy** (`9df041b`, tier 6.4). New
  `docs/adr/ADR-0002-room-migration-policy.md` documents that
  `fallbackToDestructiveMigration(true)` is a qualified fallback and that every
  schema-version bump MUST ship an explicit `MIGRATION_*` object (following the
  existing `MIGRATION_13_14` pattern). `AppDatabase`/`MainActivity` carry a
  startup note pointing at the policy.
- **Budget token heuristic documentation** (`467428f`, tier 6.5). `BudgetScreen`
  copy now explains the cloud-token estimate is character-length based and
  approximate vs. real tokenizers, with the cap enforced between iterations.
- **LlmRouter agent-directive context** (`949f28b`, tier 6.6).
  `LlmRouter.generateForAgent` now appends a compact `WRITE_FILE:` / git /
  `MCP_CALL:` directive reference (plus approval-gate awareness and a one-line
  standards reminder) to agent system prompts. Scoped to `generateForAgent`
  only — `generateFreeform` / `routePrompt` callers stay clean. Covered by
  `LlmRouterTest`.
- **mcp-server ops: launch script + CI + README** (`eef6fbf`, mcp-server).
  Added `scripts/launch_server.sh` (venv-aware, sets `WORKSPACE_ROOT` when unset,
  logs to `server_run.log`), a `.github/workflows/test.yml` CI gate running
  `uv run pytest tests/ -q`, and an Ops/run section in the server README
  (quick start, env-var table, health-check curl).
- **WIP reconciliation** (`08fb522`). Committed the previously-untracked
  antigenic subagent system (`Antigenic*.kt`, `SubagentLauncher.kt`), headless
  approval tests, and infra tuning; deleted the orphan `WorkspaceViewModel.kt`
  (see `agent-os/plans/workspace-viewmodel-verdict.md`).

### Changed

- Versioning is now git-derived (see Added). A clean build on `main` reports
  `versionCode` = commit count and `versionName` = latest tag (or `1.0.0`).
- Antigenic subagent system and headless-approval logic are now part of the
  committed baseline (reconciled in `08fb522`).

### Fixed

- **`suggest_next_action` no longer raises `NameError`** (`c463b3c`, mcp-server).
- **AGENTIC_LOOP verify prompt verified to carry the act-step output**
  (`d1d6c18`, tier 6.1) — regression-tested.

### Security

- No secrets are committed. Release signing keys are environment-only
  placeholders (`OLLAMADEV_KEYSTORE_*`); `.env` contents remain untracked.

### Known limitations (pre-existing, not addressed by this work)

- Plain `:app:assembleRelease` fails a fatal lint
  (`InvalidFragmentVersionForActivityResult` at `MainActivity.kt:42`,
  resolved via `androidx.fragment` 1.1.0 through `play-services-basement`).
  Use `-x lintVitalRelease` for release builds; a follow-up (lint baseline or
  fragment bump) is still open.
- `:app:testDebugUnitTest` has a pre-existing failing class
  (`AntigenicOrchestratorTest`) and one Robolectric class that hangs on this
  host. Targeted non-Robolectric data-layer tests pass.
- The `AGENTIC_LOOP` cloud-token budget uses a character-length heuristic, not a
  real tokenizer; the cap is enforced at iteration boundaries.
---

## [0.1.0] — 2026-07-29 — PRs #7–10

> See `RELEASE_NOTES.md` for the full PR #7–10 narrative.

Merged PRs #7 through #10 add a debug APK CI job, Tier 1/2 trust-and-oversight
guardrails, seeded OllamaDev Sandbox tooling, and Tier 3b/c in-app analytics
plus background execution. The backlog now marks Tier 1–5 items complete.

### Added

- **CI / Build**: debug APK build job in `.github/workflows/android-ci.yml`
  (`assembleDebug` artifact upload); `watch-ci.sh` local helper (`#7`).
- **Tier 1 — Trust & Visibility**: MCP risk reasoning surfaced in the approval
  dialog (`isRiskyMcpCallReason()`); cloud token budget guardrail
  (`TaskBudgetTracker`, `cloud_token_cap`); new Budget sub-tab
  (`BudgetScreen.kt`); `TaskStep` icon/color dispatch
  (`stepIconAndColorFor()`) (`#8`).
- **Tier 2 — Oversight & Review**: "Awaiting Approval" agent status badge;
  batched multi-file `WRITE_FILE:` review (`PendingFileChangeBatch`) with a
  diff dialog (`#8`).
- **Tier 3 — Sandbox, Analytics & Background**: seeded `OllamaDev Sandbox` MCP
  server + `Pytest`/`Gradle` sandbox runner skills; in-app `AnalyticsScreen.kt`;
  foreground `AgenticLoopService` background execution with notifications
  (`#9`, `#10`).
- **Backlog housekeeping**: `agent-os/backlog.md` marks Tier 1–5 complete
  (`#10`).

### Fixed

- N/A for this release.

### Known limitations (at 0.1.0)

- The seeded `OllamaDev Sandbox` MCP server points to `http://localhost:5000/mcp`;
  the companion `ollamadev-mcp-server` must be running separately.
- Cloud-token budget uses a character-length heuristic, not a real tokenizer.
- MCP risk gating prefers tool annotations, falling back to keyword matching.
- Background execution requires `POST_NOTIFICATIONS` on Android 13+.
