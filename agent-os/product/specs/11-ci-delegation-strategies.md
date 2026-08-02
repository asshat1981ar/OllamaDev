# Spec 11 — CI Delegation Strategies (parallel gate, reusable runner, more targets)

Status: implemented · feeds `agent-os/backlog.md` Tier 7

## Problem

Spec 10 gave the repo an on-demand gradle-delegation workflow (`remote-build.yml`) and the
standard `Android CI` gate, but the gate ran `test` → `build` **sequentially** (~13 min
wall-clock), there was no way to record real screenshots on demand, no nightly maintenance,
and every new delegation target duplicated the checkout/JDK/gradle-setup steps. The local
aarch64 container also can't reuse CI's compiled cache entries (x86_64-only).

## Design

### A. Reusable gradle runner — `.github/workflows/gradle-task.yml` (`workflow_call`)

Single source of truth for "run a gradle task on a fresh x86_64 runner and upload outputs":
checkout → JDK 17 → `gradle/actions/setup-gradle@v3` (cache-read-only knob) → run the task →
upload artifacts (`if: always()`). Inputs: `task`, `ref`, `artifact_name`, `upload_patterns`,
`cache_read_only`, `timeout_minutes`. Everything else in this spec calls it.

### B. Parallel gate — `.github/workflows/android-ci.yml`

- Unit suite split into **three matrix shards** running concurrently: `ui`
  (`--tests 'com.example.ui.*'`), `data` (`--tests 'com.example.data.*'`), and `rest`
  (top-level + `viewmodel`). `fail-fast: false` so one red shard doesn't cancel the others.
- `build` (`:app:assembleDebug`) runs **concurrently** with the test shards (no `needs: test`;
  assembleDebug doesn't depend on unit tests).
- Net effect: gate wall-clock ≈ `max(test, build)` instead of `test + build`.
- `cache_read_only` stays `true` on feature branches so only `main` refreshes the shared cache.

### C. New delegation targets

| Workflow | Trigger | Task | Artifacts |
|---|---|---|---|
| `Roborazzi Screenshots` | dispatch | `:app:recordRoborazziDebug --tests com.example.ui.ScreenshotDriverTest` | real `roborazzi-screens/**/*.png` (impossible locally) |
| `Nightly Maintenance` | `schedule: cron '0 3 * * *'` + dispatch | `testDebugUnitTest assembleDebug lintDebug` | outputs + reports |
| `Android Lint` | dispatch | `:app:lintDebug` | lint HTML/TXT |
| `Remote Build Delegation` | dispatch (unchanged interface for `delegate-build.sh`) | arbitrary task input | outputs + reports |

All four are thin `uses: ./.github/workflows/gradle-task.yml` wrappers.

### D. Local warm-up — `scripts/pull-gradle-cache.sh`

Downloads the newest `main`-branch `gradle-home` cache entry via the `gh-actions-cache`
extension and copies only the **arch-independent** parts into `~/.gradle`:
`caches/modules-2`, `caches/modules-2-files`, `wrapper/dists`. Compiled/transform caches are
intentionally skipped (x86_64-specific; unusable and harmful on aarch64).

## Cache discovery: gradle/actions/setup-gradle@v3 restore 400

While validating, the repo had **zero** Actions caches even though every run configured
`gradle/actions/setup-gradle@v3` — the setup step logged `Failed to restore
gradle-home-v1|Linux|test[<hash>]-<sha>: Error: Cache service responded with 400`, so
every job cold-started (re-downloaded the Gradle distribution + dependencies). The reusable
runner now pins `gradle/actions/setup-gradle@v4` (uses actions/cache v4), which fixes the
cache-service interaction. `gh api repos/{owner}/{repo}/actions/caches` should show entries
after a v4 run, and `scripts/pull-gradle-cache.sh --list` can then verify/download them.

## Constraints & tradeoffs

- **`task` / `shell` inputs are verbatim-expanded.** Acceptable: reusable workflows are only
  callable by this repo's workflows, and dispatch requires write access. Do not add free-form
  user input to public/third-party callers.
- **Matrix shards duplicate compilation** (each shard compiles the full test sources). On
  cold runs this is roughly neutral; on warm-cache runs (main refreshes the cache nightly) the
  execution phase dominates, so sharding pays off. The parallel `build` job is the guaranteed
  win.
- **Screenshots/nightly/lint add runner minutes.** Targeted `--tests` filters keep iteration
  cheap; nightly is a single job/day.
- **Larger runners / self-hosted runners** are the escalation path if the free-hosted pool
  becomes a bottleneck: larger runners are org/enterprise-billed (not available on this
  personal repo today); a self-hosted x86_64 machine would need the runner agent installed.

## Deferred (documented, not implemented)

- **Issue-driven task queue** (`on: issues` + `run:<label>` → mapped to a fixed task, comment
  result back). Safe variant: label → `case`-mapped task string (no free-form body parsing).
- **`.devcontainer`** for Codespaces so the managed prebuilds build a usable JDK17+Android-SDK
  dev box (billable per-core-hour; optional).
- **Other CI providers** (CircleCI/Buildkite) — only if hosted Actions minutes/queue become a
  constraint.

## Verification

- `gh workflow run "Android CI" --ref <branch>` → 3 test shards + build all green, and the
  gate completes in ~max(shard, build) time.
- `gh workflow run "Roborazzi Screenshots" --ref <branch>` → `roborazzi-screens` artifact with
  real PNGs.
- `gh workflow run "Android Lint" --ref <branch>` → lint report artifact (informational).
- `scripts/pull-gradle-cache.sh --list` lists main-branch gradle caches; run without `--list`
  restores `modules-2` + wrapper dists locally.
