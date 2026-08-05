# Headless Approval Policy — Verification & Next-Feature Plan

> **For Hermes:** Use subagent-driven-development or direct execution to implement this plan task-by-task.

**Goal:** Resolve the Gradle daemon connection failure so the headless-approval test suite can actually run, then use the result to decide and plan the next OllamaDev feature.

**Architecture:** The implementation is already in place across `AgenticActionExecutor`, `SwarmEngine`, `AgenticLoopService`, `PendingApprovalStore`, `TaskStepComponents`, and a new Robolectric test. The remaining work is environmental verification and a follow-on product decision.

**Tech Stack:** Android/Jetpack Compose/Kotlin, Gradle 9.3.1, Robolectric unit tests, WSL2 Ubuntu.

---

## Current context / assumptions

- Project root: `/home/dev/OllamaDev` (WSL2 Ubuntu).
- Code changes have been applied but **not validated by the Gradle test runner**.
- `./gradlew` fails with `Could not connect to the Gradle daemon` despite the daemon process starting and listening on `127.0.0.1`.
- `java.net.ConnectException: Connection refused` when the Gradle client tries to connect to its own daemon.
- Gradle 9.3.1, Java 17 OpenJDK, WSL2 kernel `6.18.35.2-microsoft-standard-WSL2`.
- Ad-hoc source verification passed: all intended headless-approval branches are structurally present.

---

## Task 1: Diagnose the WSL2 Gradle daemon connection failure

**Objective:** Make `./gradlew` usable again so tests can compile and run.

**Files:** none (environmental).

**Step 1: Confirm daemon listens on a connectable interface**

Run:
```bash
wsl bash -c 'cd /home/dev/OllamaDev && pkill -9 -f GradleDaemon && sleep 2 && ./gradlew --version --info'
```
Expected: either success, or the same `Connection refused` with a specific daemon port.

**Step 2: Try forcing IPv4 loopback and disabling the daemon registry**

Run:
```bash
wsl bash -c 'cd /home/dev/OllamaDev && pkill -9 -f GradleDaemon && rm -rf ~/.gradle/daemon && ./gradlew --no-daemon -Dorg.gradle.daemon=false -Djava.net.preferIPv4Stack=true --version'
```
Expected: Gradle reports version without daemon connection errors.

**Step 3: If still failing, switch to `localhost` explicit bind**

Create or edit `/home/dev/OllamaDev/gradle.properties` temporarily:
```properties
org.gradle.daemon=false
systemProp.java.net.preferIPv4Stack=true
```
Run:
```bash
wsl bash -c 'cd /home/dev/OllamaDev && ./gradlew --version'
```
Expected: version output.

**Step 4: As a last resort, use the Gradle wrapper JAR directly without the launcher script**

Run:
```bash
wsl bash -c 'cd /home/dev/OllamaDev && java -Dorg.gradle.daemon=false -Djava.net.preferIPv4Stack=true -jar gradle/wrapper/gradle-wrapper.jar :app:testDebugUnitTest --tests "com.example.data.AgenticActionExecutorHeadlessApprovalTest"'
```
Expected: tests compile and execute.

**Step 5: Document the working invocation**

Add a one-line note to `agent-os/plans/headless-approval-verification-plan.md` (this file) under "Working test command".

---

## Task 2: Run the focused headless-approval test suite

**Objective:** Confirm the new tests pass and existing related tests still pass.

**Files:**
- Test: `app/src/test/java/com/example/data/AgenticActionExecutorHeadlessApprovalTest.kt`
- Test: `app/src/test/java/com/example/data/AgenticActionExecutorTest.kt`
- Test: `app/src/test/java/com/example/data/AgenticActionExecutorBatchFileWriteTest.kt`
- Service test: `app/src/test/java/com/example/viewmodel/SwarmViewModelBackgroundRunTest.kt`

**Step 1: Run the three executor test classes**

Use the working invocation from Task 1, e.g.:
```bash
wsl bash -c 'cd /home/dev/OllamaDev && [WORKING_GRADLE_INVOCATION] :app:testDebugUnitTest --tests "com.example.data.AgenticActionExecutorHeadlessApprovalTest" --tests "com.example.data.AgenticActionExecutorTest" --tests "com.example.data.AgenticActionExecutorBatchFileWriteTest"'
```
Expected: all tests pass; no new failures.

**Step 2: Run the background-service VM test**

```bash
wsl bash -c 'cd /home/dev/OllamaDev && [WORKING_GRADLE_INVOCATION] :app:testDebugUnitTest --tests "com.example.viewmodel.SwarmViewModelBackgroundRunTest"'
```
Expected: pass.

**Step 3: If any test fails, fix the implementation**

Likely failure modes:
- `PendingApprovalStore.pendingApproval.value` is non-null in headless mode → means the executor did not skip approval.
- Missing `APPROVAL_SKIPPED_HEADLESS` step → approval branch not taken.
- `buildTaskSummary` suspend-function mismatch in `AgenticLoopService` → already patched, but confirm compile.

Fix, re-run the focused tests, repeat.

---

## Task 3: Run the full unit-test suite

**Objective:** Ensure no regressions across the app.

**Step 1: Run all unit tests**

```bash
wsl bash -c 'cd /home/dev/OllamaDev && [WORKING_GRADLE_INVOCATION] :app:testDebugUnitTest'
```
Expected: suite completes; any failures are investigated.

**Step 2: Address regressions**

If failures relate to the new `isHeadless` constructor parameter changing call sites, add default values or update call sites. The changes already use default values, so this should not break callers.

---

## Task 4: Update product docs for the shipped feature

**Objective:** Keep `RELEASE_NOTES.md` and `agent-os/backlog.md` in sync.

**Files:**
- Modify: `agent-os/backlog.md` (mark Tier 6.3 as done or in-review)
- Modify: `RELEASE_NOTES.md` (add note under latest release)

**Step 1: Add release note**

In `RELEASE_NOTES.md`, append:
```markdown
- Background service now uses a headless approval policy: git push, destructive MCP calls, and batched file writes that require UI approval are auto-declined instead of deadlocking. Skipped approvals are recorded as `APPROVAL_SKIPPED_HEADLESS` task steps and surfaced in the completion notification.
```

**Step 2: Update backlog**

In `agent-os/backlog.md`, mark Tier 6.3 as completed and add a reference to this plan file.

---

## Task 5: Decide and plan the next feature

**Objective:** Use the weighted scoring system from `agent-os/plans/next-feature-scoring.md` to pick the next build target.

**Context:** The top 5 scored features are:
1. Headless approval policy for background service — **done/verifying**.
2. GitHub PR export from sprint/workspace artifacts.
3. Voice-driven follow-up and session memory.
4. Persistent notification quick actions.
5. MCP skill marketplace / discovery UI.

**Step 1: Re-score with implementation learnings**

After Task 1-3, update `agent-os/plans/next-feature-scoring.md` if the headless-approval work revealed new constraints (e.g. notification API limits, DB step types, fake DB capabilities).

**Step 2: Pick the next feature**

Recommended default: **GitHub PR export from sprint/workspace artifacts**.
Rationale:
- High strategic differentiation: OllamaDev produces code but has no path to share it as a real PR.
- Builds on existing git push, workspace files, and sprint artifacts.
- Market gap: mobile/local-first coding agents lack GitHub integration.

**Step 3: Write the next implementation plan**

Create `agent-os/plans/YYYY-MM-DD_HHMMSS-github-pr-export-plan.md` covering:
- Add GitHub REST API client or reuse `mcpClient` against a Private GitHub MCP server.
- Add `CreatePullRequest` directive parser in `AgenticActionExecutor`.
- Add UI fields for base branch, title, body in `SystemConfigScreen` Git tab or `WorkspacePanel`.
- Persist GitHub repo/owner in `SecurePrefs` and SharedPreferences.
- Add `GITHUB_PR_CREATED` / `GITHUB_PR_FAILED` step types and icon mapping.
- Tests with `FakeMcpClient` or a new `FakeGitHubApi`.

---

## Risks, tradeoffs, and open questions

1. **Gradle daemon blocker:** If WSL2 networking prevents any daemon connection, long builds will be slower with `--no-daemon`. Consider setting `org.gradle.daemon=false` in `gradle.properties` only on this dev machine if performance is acceptable.
2. **Headless policy is conservative:** It auto-declines all approval-gated actions. Future work could add a per-action headless policy (e.g. allow read-only MCP, allow file writes to existing paths). Document this limitation in `RELEASE_NOTES.md`.
3. **Notification UX:** The final notification appends a skip count. If the user has many skips, the text may truncate. Consider a richer notification with an expandable inbox-style summary later.
4. **Test coverage:** The new test covers executor branches but does not exercise `AgenticLoopService` end-to-end with a real background run. `SwarmViewModelBackgroundRunTest` is the closest proxy.
5. **Open question:** Should headless mode also skip the verify/retry loop's use of MCP tools, or is that covered by the destructive-call gate only?

---

## Working test command (fill in after Task 1)

```bash
# Replace [WORKING_GRADLE_INVOCATION] with the actual command that succeeds.
```

---

## Verification checklist

- [ ] Gradle daemon connection issue resolved.
- [ ] `AgenticActionExecutorHeadlessApprovalTest` passes.
- [ ] `AgenticActionExecutorTest` still passes.
- [ ] `AgenticActionExecutorBatchFileWriteTest` still passes.
- [ ] `SwarmViewModelBackgroundRunTest` passes.
- [ ] Full `:app:testDebugUnitTest` passes.
- [ ] `RELEASE_NOTES.md` updated.
- [ ] `agent-os/backlog.md` Tier 6.3 marked done.
- [ ] Next feature plan written.
