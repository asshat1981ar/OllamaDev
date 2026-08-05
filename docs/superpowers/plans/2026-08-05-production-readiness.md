# OllamaDev Production Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring OllamaDev (Android) + ollamadev-mcp-server to a production-ready polished state: green build/tests, fixed known defects, versioned release path, hardened MCP server ops, docs in sync.

**Architecture:** Five workstreams (WS1 baseline, WS2 defects, WS3 release, WS4 MCP ops, WS5 docs). Each task is one independently verifiable unit on `main`. Android tasks follow the `run-ollamadev` skill for build/test commands; mcp-server tasks run under `/home/dev/ollamadev-mcp-server/.venv`.

**Tech Stack:** Kotlin/Jetpack Compose (Material 3), Gradle 9.3.1 + AGP (compileSdk 34, minSdk 24, targetSdk 34), Room, JGit; Python 3.11 + MCP Python SDK v2 (`mcp[cli]==2.0.0rc1`), pytest, requests.

## Global Constraints

- Repos: `/home/dev/OllamaDev` (app), `/home/dev/ollamadev-mcp-server` (server). Both on `main`, in sync with origin.
- Container: ~5.3 GB RAM, 2 workers. `gradle.properties` heap/workers flags are deliberate — do NOT raise them.
- The `run-ollamadev` skill (`.claude/skills/run-ollamadev/SKILL.md`) is the authority for Android build/test/screenshot and known traps (WSL IPv4 loopback broken → use `127.0.0.1` for HTTP where possible; no emulator; Roborazzi needs `recordRoborazziDebug`; QEMU aapt2 via `scripts/setup_aapt2_qemu.sh` if aapt2 fails).
- Server must run with `WORKSPACE_ROOT=/home/dev/OllamaDev` (default points at missing `/home/userland/OllamaDev`).
- Android unit tests: `./gradlew :app:testDebugUnitTest --console=plain`; cold Robolectric first run 2–4 min/class — expect slow.
- No secrets committed: `.env` contents never tracked; signing keys env-only placeholders.
- Each task ends committed with a focused message; no force pushes.

---

### Task 1: WIP reconciliation — commit valuable work, delete the orphan

**Files:**
- Modify (uncommitted, review each): `app/src/main/java/com/example/data/PendingApprovalStore.kt`, `SwarmEngine.kt`, `AgenticActionExecutor.kt`, `SprintOrchestrator.kt`, `TaskBudgetTracker.kt`, `AgenticLoopService.kt`, `TaskStepComponents.kt`, `app/src/test/java/com/example/data/SwarmEngineBudgetGuardrailTest.kt`, `agent-os/backlog.md`, `.claude/skills/run-ollamadev/SKILL.md`
- Create (untracked; keep+commit if they compile): `app/src/main/java/com/example/data/Antigenic*.kt` (6 files), `app/src/main/java/com/example/data/SubagentLauncher.kt`, `app/src/test/java/com/example/data/Antigenic*Test.kt` (4), `AgenticActionExecutorHeadlessApprovalTest.kt`, `SwarmEngineVerifyPromptTest.kt`
- Delete: `app/src/main/java/com/example/viewmodel/WorkspaceViewModel.kt` (verdict: `agent-os/plans/workspace-viewmodel-verdict.md`)
- Note: `settings.gradle.kts` / `gradle.properties` modified locally = infra tuning; review and commit separately if intentional.

**Interfaces:** Produces: clean committed baseline; deleted orphan; documented leftovers (no phantom commits).

- [ ] **Step 1: Inventory.** `git -C /home/dev/OllamaDev status --short`; for each `M` file run `git diff <file>` and record intended vs incidental.
- [ ] **Step 2: Safety compile.** `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --console=plain --offline` (fall back online if missing deps). Record failures — they gate which untracked files are commit-eligible.
- [ ] **Step 3: Delete orphan.** `git rm app/src/main/java/com/example/viewmodel/WorkspaceViewModel.kt`; confirm `grep -rn WorkspaceViewModel app/src` has no main/test references.
- [ ] **Step 4: Commit kept work.** `git add <kept files>` + `git commit -m "chore: reconcile WIP (antigenic subagent system, headless approval tests, infra tuning)"`.
- [ ] **Step 5: Document leftovers.** Non-compiling/uncertain files stay untracked; list them in the task summary comment.

### Task 2: Green baseline — RELEASE build type, signing placeholder

**Files:**
- Modify: `/home/dev/OllamaDev/app/build.gradle.kts` (`android { signingConfigs { ... } }` + `buildTypes { release { ... } }`)
- Optionally: `/home/dev/OllamaDev/gradle/libs.versions.toml`

**Interfaces:** Produces: `signingConfigs.release` (env-gated), `buildTypes.release` (unsigned when env absent); consumed by Task 4 version wiring.

- [ ] **Step 1: Add env-gated signing config:**

```kotlin
android {
  signingConfigs {
    if (System.getenv("OLLAMADEV_KEYSTORE_PATH") != null) {
      create("release") {
        storeFile = file(System.getenv("OLLAMADEV_KEYSTORE_PATH"))
        storePassword = System.getenv("OLLAMADEV_KEYSTORE_PASS")
        keyAlias = System.getenv("OLLAMADEV_KEYSTORE_ALIAS")
        keyPassword = System.getenv("OLLAMADEV_KEYSTORE_PASS")
      }
    }
  }
  buildTypes {
    getByName("release") {
      isMinifyEnabled = false
      signingConfig = signingConfigs.findByName("release") // null -> unsigned
    }
  }
}
```

- [ ] **Step 2: Verify** `./gradlew :app:assembleRelease --console=plain --offline` produces unsigned APK when env unset.
- [ ] **Step 3: Verify** `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain` green.
- [ ] **Step 4: Commit** `build: add gated release signing placeholder + release build type`.

### Task 3: Fix `suggest_next_action` NameError (mcp-server)

**Files:**
- Modify: `/home/dev/ollamadev-mcp-server/ollamadev_mcp_server/tools/meta.py` (the `suggest_next_action` handler where `model` is referenced before assignment)
- Test: extend `/home/dev/ollamadev-mcp-server/tests/test_meta.py`

**Interfaces:** Produces: `suggest_next_action(...)` returns a parsed recommendation dict or a clean error string.

- [ ] **Step 1: Reproduce.** With server running (`WORKSPACE_ROOT=/home/dev/OllamaDev .venv/bin/python server.py`), call `tools/call suggest_next_action`; confirm the NameError JSON.
- [ ] **Step 2: Write failing test.** In `tests/test_meta.py` (match existing fixtures), add a test that drives the cloud/ollama branch where `model` is shadowed/unbound and asserts no `NameError` and a tool-shaped dict. Run `uv run pytest tests/test_meta.py -q` — expect FAIL.
- [ ] **Step 3: Fix.** Resolve variable shadowing so `model` is always bound in both `provider=="cloud"` and `provider=="ollama"` paths.
- [ ] **Step 4: Verify live.** Restart server; re-call the tool; assert valid JSON (`tool_name`, `arguments`, `reasoning`, `confidence`) or a clean documented error.
- [ ] **Step 5: Full suite** `uv run pytest tests/ -q` all green; commit `fix(meta): resolve suggest_next_action NameError`.

### Task 4: VersionScheme — derive versionCode/versionName from git

**Files:**
- Create: `/home/dev/OllamaDev/scripts/version.sh`
- Modify: `/home/dev/OllamaDev/app/build.gradle.kts` (`defaultConfig` versionCode/versionName)

**Interfaces:** Produces: `scripts/version.sh` with `code` and `name` subcommands; Gradle `versionCodeValue`/`versionNameValue` used in `defaultConfig`.

- [ ] **Step 1: Write scripts/version.sh** — `code` = `git rev-list --count HEAD`; `name` = latest semver tag (or `1.0.0`) + `-<short-sha>` when dirty. Print one value per invocation.
- [ ] **Step 2: Wire in app/build.gradle.kts:**

```kotlin
val versionCodeValue = providers.exec { commandLine("bash", "scripts/version.sh", "code") }
  .standardOutput.asText.orNull?.trim()?.toIntOrNull() ?: 1
val versionNameValue = providers.exec { commandLine("bash", "scripts/version.sh", "name") }
  .standardOutput.asText.orNull?.trim() ?: "1.0.0"
// defaultConfig { versionCode = versionCodeValue; versionName = versionNameValue }
```

- [ ] **Step 3: Verify** `bash scripts/version.sh code` and `name` return sane values; `./gradlew :app:processReleaseManifest --offline` shows them in the merged manifest.
- [ ] **Step 4: Commit** `build: derive versionCode/versionName from git`.

### Task 5: Fix AGENTIC_LOOP verify-prompt `decision` bug (Tier 6.1) + regression test

**Files:**
- Modify: `/home/dev/OllamaDev/app/src/main/java/com/example/data/SwarmEngine.kt` (verifyPrompt build ~397–416)
- Test: `/home/dev/OllamaDev/app/src/test/java/com/example/data/SwarmEngineVerifyPromptTest.kt` (extend existing untracked test)

**Interfaces:** Produces: verify prompt containing the act-step output text.

- [ ] **Step 1: Inspect.** Read `SwarmEngine.kt` around `verifyPrompt`; confirm whether `decision` is unbound/wrongly interpolated.
- [ ] **Step 2: Write failing test.** Assert produced verify prompt contains the act-step output snippet; run `./gradlew :app:testDebugUnitTest --tests '*SwarmEngineVerifyPromptTest' --console=plain` — expect FAIL.
- [ ] **Step 3: Fix** the prompt construction; keep prompt shape; no behavior change elsewhere.
- [ ] **Step 4: Verify** targeted test green + `./gradlew :app:compileDebugKotlin`.
- [ ] **Step 5: Commit** `fix(swarm): AGENTIC_LOOP verify prompt uses act output (tier 6.1)`.

### Task 6: Room migration safeguard (Tier 6.4)

**Files:**
- Create: `/home/dev/OllamaDev/docs/adr/ADR-0002-room-migration-policy.md`
- Modify: `/home/dev/OllamaDev/app/src/main/java/com/example/data/AppDatabase.kt` (or `Daos.kt`), `/home/dev/OllamaDev/app/src/main/java/com/example/MainActivity.kt` (startup note if policy chosen)

**Interfaces:** Produces: documented migration policy + any needed `MIGRATION_*` constant.

- [ ] **Step 1: Read** `AppDatabase.kt` (Room version, `fallbackToDestructiveMigration(true)`, existing `MIGRATION_13_14`).
- [ ] **Step 2: Write ADR-0002** — policy: destructive fallback is qualified; every version bump requires an explicit `MIGRATION_*`; startup data-export note if destructive retained.
- [ ] **Step 3: Implement** the chosen policy (code change, keep it minimal; bump Room version only if a schema change is intentionally included).
- [ ] **Step 4: Verify** `./gradlew :app:compileDebugKotlin`.
- [ ] **Step 5: Commit** `chore(db): document migration policy + safeguard (tier 6.4)`.

### Task 7: Budget heuristic documentation (Tier 6.5)

**Files:**
- Modify: `/home/dev/OllamaDev/app/src/main/java/com/example/ui/BudgetScreen.kt`

**Interfaces:** Consumes: existing `cloud_token_cap` and `(len(prompt)+len(output))/2 + 100` estimate.

- [ ] **Step 1: Add copy** explaining the estimate is character-length based, approximate vs real tokenizers, cap enforced between iterations.
- [ ] **Step 2: Verify** `./gradlew :app:compileDebugKotlin` (plus BudgetScreen flow test if present).
- [ ] **Step 3: Commit** `docs: document budget token heuristic (tier 6.5)`.

### Task 8: LlmRouter agent-prompt directive docs (Tier 6.6)

**Files:**
- Modify: `/home/dev/OllamaDev/app/src/main/java/com/example/data/LlmRouter.kt` (`buildSkillsContext`, `generateForAgent` scope only)
- Test: `/home/dev/OllamaDev/app/src/test/java/com/example/data/LlmRouterTest.kt`

**Interfaces:** Produces: `generateForAgent` prompt contains `WRITE_FILE:`/`MCP_CALL:`/git directive reference; `generateFreeform`/`routePrompt` unchanged.

- [ ] **Step 1: Read** `LlmRouter.kt`; locate `buildSkillsContext()` and `generateForAgent`.
- [ ] **Step 2: TDD** — extend `LlmRouterTest`: assert `generateForAgent` prompt contains the directive reference and `generateFreeform` does not; run that test — expect FAIL.
- [ ] **Step 3: Implement** scoped context augmentation, char-budget conscious.
- [ ] **Step 4: Verify** targeted + `./gradlew :app:testDebugUnitTest --console=plain`.
- [ ] **Step 5: Commit** `feat(swarm): document agent directives in LLM context (tier 6.6)`.

### Task 9: MCP server ops — launcher + CI + README

**Files:**
- Create: `/home/dev/ollamadev-mcp-server/scripts/launch_server.sh`
- Create: `/home/dev/ollamadev-mcp-server/.github/workflows/test.yml`
- Modify: `/home/dev/ollamadev-mcp-server/README.md` (Ops section)

**Interfaces:** Produces: `launch_server.sh` starts server (venv + WORKSPACE_ROOT-aware); CI gate runs the suite.

- [ ] **Step 1: Write launch_server.sh** — repo-root exec, reuse `.venv/bin/python`, export `WORKSPACE_ROOT` when unset, `exec server.py`, print the MCP URL.
- [ ] **Step 2: Write .github/workflows/test.yml** — ubuntu-latest, python 3.11 + uv, `uv sync --extra dev --prerelease=allow`, `uv run pytest tests/ -q`.
- [ ] **Step 3: README ops section** — quick start, env vars, health-check curl snippet.
- [ ] **Step 4: Verify** `bash scripts/launch_server.sh` (background) + `curl`/client ping; `uv run pytest tests/ -q` all green.
- [ ] **Step 5: Commit** `ops: launch script + CI + README for mcp-server`.

### Task 10: Final docs sync + full verification (WS5)

**Files:**
- Create: `/home/dev/OllamaDev/CHANGELOG.md`
- Modify: `/home/dev/OllamaDev/README.md`, `RELEASE_NOTES.md`, `agent-os/backlog.md`, `agent-os/product/PRD.md`, `HLD.md`, `LLD.md` (status alignment), `.github/workflows/android-ci.yml` (add release workflow wiring item if time-boxed feasible)

**Interfaces:** Consumes: results of Tasks 1–9.

- [ ] **Step 1: Create CHANGELOG.md** reflecting Tasks 1–9 + prior notes.
- [ ] **Step 2: Sync statuses** — backlog checks for 6.1/6.4/6.5/6.6 done; PRD/HLD/LLD status fields; README correction (stale signing step).
- [ ] **Step 3: Full verify** — app: `./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain` green; server: `uv run pytest tests/ -q` green + live `suggest_next_action` clean.
- [ ] **Step 4: Commit** `docs: changelog + status sync (production readiness)`.

---

## Self-Review Notes

- **Spec coverage:** WS1→Tasks 1–2; WS2→Tasks 3–8; WS3→Task 4 (+ CI release-wiring folded into Task 10 step 2 as time-boxed); WS4→Tasks 3+9; WS5→Task 10.
- **Placeholder scan:** no TBDs; every step has a command or code block.
- **Type consistency:** `scripts/version.sh` (`code`/`name`), Gradle props (`versionCodeValue`/`versionNameValue`), and the `suggest_next_action` recommendation dict are each defined once.
- **Known deferral:** app signing with a real keystore + upload-to-store automation is explicitly OUT of scope (env-gated placeholder only).
