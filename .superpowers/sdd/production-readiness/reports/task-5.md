# Task 5 — AGENTIC_LOOP verify-prompt regression (Tier 6.1)

**Status: DONE**
**Branch:** main · **Commit:** `d1d6c18`

## 1. Fix confirmation (evidence from SwarmEngine.kt)

The Tier-6.1 bug ("verify prompt interpolates an unresolved `decision` variable
before it is declared") is **already fixed** in the working tree of
`app/src/main/java/com/example/data/SwarmEngine.kt`. The verify prompt is built
from `actResult.output`, and `actResult` is declared above it:

```kotlin
411:  val actResult = stepRunner.run(taskId, { updateTaskStatus(taskId, it) }, StepRequest(
412:      agent = actor,
413:      prompt = actPrompt,
...
420:      ))
421:  budgetLedger.addTokens(actResult.approxTokens)
422:  transcript.append("\n${next.text} -> ${actResult.output}")
423:
429:  val qaAgent = pickQaAgent(agents, planningAgent)
430:  val verifyPrompt = "The following step was just attempted:\n${next.text}\n\nAgent output:\n${actResult.output}\n\n" +
431:      "As QA, verify this was actually done correctly. If real test/execution tooling is " +
432:      "available, invoke it via 'MCP_CALL: <tool> | <json args>' and report the real " +
433:      "result -- do not fabricate output. If no tooling is available, say so plainly."
434:  val verifyResult = stepRunner.run(taskId, { updateTaskStatus(taskId, it) }, StepRequest(
435:      agent = qaAgent,
436:      prompt = verifyPrompt,
```

No unresolved variable: `actResult` (declared at line 411) is used directly at
line 430. No code change was required.

## 2. Regression test — run and result

**File:** `app/src/test/java/com/example/data/SwarmEngineVerifyPromptTest.kt`
(untracked WIP file, verified by this task; contains
`agenticLoop_verifyPromptContainsThatIterationsActOutput`).

**Command (backgrounded due to 30s tool timeout):**
```
cd /home/dev/OllamaDev && ( setsid env JAVA_TOOL_OPTIONS='-Djava.net.preferIPv6Addresses=true' \
  ./gradlew -I /tmp/r5-init.gradle :app:testDebugUnitTest \
  --tests 'com.example.data.SwarmEngineVerifyPromptTest' --console=plain --offline \
  >/tmp/t5.log 2>&1 </dev/null & )
```

**Result log tail (from /tmp/t5.log):**
```
> Task :app:kspDebugKotlin
> Task :app:compileDebugKotlin
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 4m 1s
```

**JUnit report:**
`app/build/test-results/testDebugUnitTest/TEST-com.example.data.SwarmEngineVerifyPromptTest.xml`:
```
<testsuite name="com.example.data.SwarmEngineVerifyPromptTest" tests="1" skipped="0" failures="0" errors="0" ...>
  <testcase name="agenticLoop_verifyPromptContainsThatIterationsActOutput" .../>
```
1 test, 0 failures, 0 errors — **PASSED** against the current engine, no code change needed.

### Environment note (why the non-trivial command line)
The run-ollamadev skill documents two conflicting WSL environment facts:
1. WSL IPv4 loopback is broken -> every Gradle JVM needs
   `JAVA_TOOL_OPTIONS='-Djava.net.preferIPv6Addresses=true'` or the client dies
   with "Could not connect to the Gradle daemon" (verified: bare `./gradlew`
   and even `--no-daemon` fail exactly that way; Gradle 9 always forks a
   single-use daemon).
2. That flag breaks Maven downloads -> resolution must come from the cache +
   file repos. The Gradle `modules-2` cache had been pruned since the last
   green offline run (2026-07-30): the `com.github.ben-manes.versions:0.48.0`
   plugin (declared in root `build.gradle.kts`), its exact transitive deps
   (kotlin-reflect 1.8.20, okhttp 4.11.0, okio 3.2.0 / okio-jvm 3.2.0,
   xstream 1.4.20 + xstream-parent, mxparser 1.2.2, xml-apis 1.3.04,
   xercesImpl 2.8.1, xmlpull 1.1.3.1, moshi 1.12.0/moshi-kotlin 1.12.0), and
   app deps `com.mixpanel.android:mixpanel-android:7.5.2` (aar) and
   `androidx.concurrent:concurrent-futures:1.1.0` (jar) were all missing.

Workaround (documented approach in the skill, applied without touching
`gradle.properties` or project files):
- Downloaded the missing artifacts over `curl -4` from Maven Central /
  plugins.gradle.org / dl.google.com into a local file repo `/tmp/m2repo`
  (Maven layout, exact versions above; ben-manes marker pom + impl jar).
- Bootstrapped every `./gradlew` with `-I /tmp/r5-init.gradle`, which inserts
  a `maven { url 'file:///tmp/m2repo' }` repo first into both
  `pluginManagement.repositories` and
  `dependencyResolutionManagement.repositories` via `settingsEvaluated`.
- Ran with `--offline`.

Result: `BUILD SUCCESSFUL in 4m 1s` (cold configuration cache + full KSP +
Robolectric cold start for SDK 34 `android-all-instrumented:14-robolectric-10818077-i7`,
which was already planted in `~/.m2` by a prior antigenic workflow task, and is
resolved by Robolectric's own resolver).

## 3. Files changed (this task)

- `app/src/test/java/com/example/data/SwarmEngineVerifyPromptTest.kt` — NEW,
  committed (114 lines, only file in the commit).

Commit:
```
d1d6c18 fix(swarm): AGENTIC_LOOP verify prompt uses act output (tier 6.1)
```
(message chosen per plan, since no code change was required; the test file is
the substantive deliverable).

**Ledger note:** this file is now tracked — the later WIP reconciliation task
must NOT commit it again (it should be excluded so history stays clean).

## 4. Self-review

- Fix evidence: quoted above; `actResult` declared (L411) before use (L430);
  compiles (KSP + `compileDebugKotlin` green) and the regression test passes.
- Regression test actually exercises the flag: each of the two verify prompts
  is asserted to contain THAT iteration's act output and NOT the previous
  iteration's output (stale-variable mode), which is exactly the RELEASE_NOTES
  "unresolved `decision`" failure mode.
- Test run narrowly scoped to the single test class (no flakiness risk from a
  partial suite); test XML confirms 1/1 passed.
- Only the intended file was committed (`git status --short` clean for that
  path afterwards; commit diff is exactly 1 file, 114 insertions).
- No `gradle.properties` or project build files were modified.

## 5. Concerns

- **Env fragility (pre-existing, not introduced):** the IPv6-loopback / pruned
  cache state means a fresh Robolectric test run needs the `/tmp/m2repo` file
  repo + `-I /tmp/r5-init.gradle` bootstrap. `/tmp/m2repo` is ephemeral; if the
  cache is pruned again the workaround must be re-applied. Did not commit any
  of that scaffolding (it's env, not project).
- The `google-services.json` warning during configuration is a pre-existing,
  non-fatal passthrough (skill-documented).
- `JAVA_TOOL_OPTIONS` prints to stderr on every JVM; harmless in logs.
