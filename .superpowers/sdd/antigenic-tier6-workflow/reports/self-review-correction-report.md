# Antigenic Tier-6 Workflow — Self-Review Correction Report

**Date:** 2026-07-30

## Issues found and corrected

### 1. Redundant `APPROVAL_DECLINED` signal in headless mode
**Problem:** `AgenticActionExecutor` recorded both an `APPROVAL_SKIPPED_HEADLESS` signal and an `APPROVAL_DECLINED` signal when running headless, making it appear as though the user declined an action they never saw.

**Fix:** In both the git-push and risky-MCP paths, when `isHeadless` is true the code now records only the `APPROVAL_SKIPPED_HEADLESS` signal and returns early. The `APPROVAL_DECLINED` signal is emitted only for interactive declines.

**Files:** `app/src/main/java/com/example/data/AgenticActionExecutor.kt`

### 2. Stale test expectation for headless MCP skip
**Problem:** `AgenticActionExecutorHeadlessApprovalTest.headless_destructiveMcpCall_autoDeclinedAndSkipsApproval` still asserted an `ACTION_DECLINED` step containing "headless", which no longer exists.

**Fix:** Updated the test to assert that `ACTION_DECLINED` is *not* present in headless mode, while `MCP_CALL_GATED` and `APPROVAL_SKIPPED_HEADLESS` are present.

**Files:** `app/src/test/java/com/example/data/AgenticActionExecutorHeadlessApprovalTest.kt`

### 3. `AntigenicOrchestrator` could dispatch the same signal repeatedly
**Problem:** Observing `AntigenicSignalStore.unresolvedSignals` would re-emit every still-unresolved signal on each state update, potentially spawning duplicate subagents.

**Fix:** Added an in-flight `MutableSet<Long>` so each signal is dispatched at most once until explicitly cleared via `clearInFlight(signalId)`.

**Files:** `app/src/main/java/com/example/data/AntigenicOrchestrator.kt`

### 4. No regression test proving `BUDGET_OVERRUN` signal emission
**Problem:** The instrumentation in `SwarmEngine.kt` was untested; a future refactor could remove the `AntigenicSignalStore.recordSignal` call silently.

**Fix:** Extended `SwarmEngineBudgetGuardrailTest.lowCap_haltsAgenticLoop_andRecordsBudgetHaltStep` to assert that a `BUDGET_OVERRUN` antigenic signal is recorded when the cap is exceeded.

**Files:** `app/src/test/java/com/example/data/SwarmEngineBudgetGuardrailTest.kt`

### 5. Environment workaround needed for Robolectric SDK 13
**Problem:** `SwarmEngineBudgetGuardrailTest` uses `@Config(sdk = [33])`, which requires Robolectric's `android-all-instrumented:13-robolectric-9030017-i7` jar. Maven download fails in this WSL environment.

**Fix:** Downloaded the jar/pom via `curl -4` and placed them in `~/.m2/repository/org/robolectric/android-all-instrumented/13-robolectric-9030017-i7/`. Tests now pass with `--offline`.

## Verification

```bash
JAVA_TOOL_OPTIONS='-Djava.net.preferIPv6Addresses=true' ./gradlew :app:testDebugUnitTest \
  --tests 'com.example.data.SwarmEngineBudgetGuardrailTest' \
  --tests 'com.example.data.AntigenicSignalTest' \
  --tests 'com.example.data.AntigenicSignalStoreTest' \
  --tests 'com.example.data.AntigenicDispatcherTest' \
  --tests 'com.example.data.AgenticActionExecutorHeadlessApprovalTest' \
  --console=plain --offline
```

Result: **BUILD SUCCESSFUL** (50s, 32 tasks).
