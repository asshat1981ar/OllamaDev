# Spec 10 — Remote Build Delegation (gradlew/build tasks → GitHub Actions)

Status: implemented · feeds `agent-os/backlog.md` Tier 6

## Problem

The active development environment is an **aarch64 Android userland container** with two
hard constraints that make local Gradle work painful and partially broken:

1. **Incorrect-arch build tools.** The Android SDK/AGP ship x86_64-only `aapt2` binaries.
   They cannot execute natively on aarch64, so the full build only works under the
   `scripts/setup_aapt2_qemu.sh` QEMU+glibc-sysroot shim (slow, fragile, re-run on every SDK
   update). See `CLAUDE.md` "Build environment note".
2. **No native graphics.** Robolectric's Skia/native-graphics stack isn't available for
   linux-aarch64 under this proot, so Roborazzi screenshots are *not* real anti-aliased
   pixels (they're a misplaced semantics-tree debug dump), and some Robolectric unit-test
   executors crash outright (e.g. exit 10) even though the same tests pass on x86_64.

At the same time, the repo already has a GitHub Actions workflow (`.github/workflows/
android-ci.yml`) that runs the **full, real** unit-test + APK build on `ubuntu-latest` —
x86_64, no QEMU, real pixels. But it only triggers on `push`/`pull_request` and only runs a
**fixed** pair of tasks, so the agent can't say "run *this* gradle task on GitHub and give me
the output".

## Goal

Make it a first-class, scriptable workflow to **delegate an arbitrary `gradlew` task to a
GitHub runner and pull the artifacts back** into the workspace — replacing the slow/broken
local path for build, test, and screenshot work.

## Design

Two pieces: an on-demand dispatch workflow (the remote side) and a one-command local driver
(the caller side).

### A. `.github/workflows/remote-build.yml` — generic on-demand runner

- `on: workflow_dispatch` with two optional inputs:
  - `task` — the exact gradle command-line suffix (e.g. `:app:assembleDebug`, or
    `:app:testDebugUnitTest --tests com.example.ui.WorkspaceScreenTest`). Default
    `:app:testDebugUnitTest`. Expanded verbatim into `./gradlew ${{ inputs.task }}`.
  - `ref` — optional branch/tag/SHA to check out (default: the ref the dispatch ran against).
- One `ubuntu-latest` job: checkout → JDK 17 → `gradle/actions/setup-gradle@v3`
  (`cache-read-only`) → run the task → upload `app/build/outputs/**` + `app/build/reports/**`
  as a `remote-build-outputs` artifact (`if: always()` so even a failed run returns its
  reports).
- Coverage: debug APK, JUnit HTML/XML reports, and **real** Roborazzi screenshots under
  `app/build/outputs/roborazzi-screens/`.

### B. `scripts/delegate-build.sh` — local driver

```
scripts/delegate-build.sh [options] [gradle-task...]
  -b, --branch <name>   Branch/ref to build (default: current branch)
  -c, --commit <msg>    Commit working-tree changes to <branch> and push first
      --repo <owner/repo>  Repo (default asshat1981ar/OllamaDev)
  -o, --outdir <dir>    Artifact download dir (default ./ci-artifacts)
```

Flow: (optional commit+push) → `gh auth setup-git` (PAT-over-HTTPS per `git/pat-https-push`)
→ `gh workflow run "Remote Build Delegation" --ref <branch> -f task="<task>"` →
poll for the new run id → `gh run watch --exit-status` → `gh run download` into
`ci-artifacts/` → exit non-zero if the run didn't conclude `success`.

### C. `.github/workflows/android-ci.yml` — on-demand merge gate

A `workflow_dispatch:` trigger is added so the **standard** gate (`test` + `build`) can also
be re-run on demand on any branch (not just via PR), e.g. before opening a PR.

## Constraints & tradeoffs

- **Runs against a pushed ref only.** `workflow_dispatch` cannot see uncommitted local
  changes; the driver's `--commit` handles committing + pushing first.
- **Workflows are only dispatchable once they exist on the default branch.** The actions
  API resolves workflow names/files against `main`, so a brand-new `remote-build.yml` is
  invisible to `gh workflow run` until it lands on `main`. `delegate-build.sh` handles this:
  for the default task (`:app:testDebugUnitTest`) it falls back to dispatching the standard
  `Android CI` gate with a warning; for custom tasks it fails with guidance to merge the
  workflow first or use `gh workflow run "Android CI" --ref <branch>`.
- **`${{ inputs.task }}` is an injection surface.** Acceptable: dispatch requires write
  access and this is a single-owner repo. Do not expose `remote-build.yml` to unauthenticated
  or public input.
- **Actions minutes.** Each delegated build ≈ 6–10 min `ubuntu-latest`; private-repo free
  tier is 2000 min/mo. Prefer targeted `--tests` (via the `task` input) over full runs when
  iterating.
- **No secrets needed** for the current suite (build tolerates a missing
  `OLLAMA_API_KEY` / `google-services.json` via existing passthrough). If a delegated test
  later needs the live key, add `OLLAMA_API_KEY` as a repo/action secret.

## Verification

- `./scripts/delegate-build.sh ':app:testDebugUnitTest --tests com.example.ui.WorkspaceScreenTest'`
  → watch to completion, artifacts land under `ci-artifacts/`.
- `./scripts/delegate-build.sh ':app:assembleDebug'` → download `app-debug.apk`.
- `./scripts/delegate-build.sh ':app:recordRoborazziDebug --tests com.example.ui.ScreenshotDriverTest'`
  → real `roborazzi-screens/*.png` (unproducible locally).
