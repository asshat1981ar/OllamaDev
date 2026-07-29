# Extract Agent-Step Deep Modules (LlmRouter / AgenticActionExecutor / StepRunner) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** De-duplicate the five coordination-mode workflows in `SwarmEngine.kt` by extracting three deep modules — `LlmRouter` (single Ollama-pool entry point), `AgenticActionExecutor` (git/MCP/WRITE_FILE directive side effects), and `StepRunner` (the shared 8-beat agent-step loop) — so each workflow shrinks to coordination policy (pick agents + build prompt + call `StepRunner`).

**Architecture:** Today every workflow inlines the same 8 beats (set-active -> status -> THINKING placeholder -> delay -> prompt -> streamIntoStep -> parseAndExecuteAgenticActions -> replace-step + metrics + idle), ~200 lines x 5. We move LLM node-selection into `LlmRouter`, directive side effects into `AgenticActionExecutor`, and the step loop into `StepRunner`. `SwarmEngine` keeps its exact 7-arg public constructor and public `generateFreeform()` (delegating to `LlmRouter`) so the 8 existing tests stay green with **zero edits** — the engine builds the three modules internally via a private secondary constructor. This preserves the `agent-os/standards` `swarm-engine-llm-pool` rule (one Ollama entry point, no cloud fallback) and the `interface-seam` rule (seams only over real-I/O classes).

**Tech Stack:** Kotlin, Jetpack Compose app (unit logic only here), Room (DAOs), JGit (`GitService`), OkHttp/Moshi (`OllamaService`, `McpClient`), kotlinx-coroutines, Robolectric + JUnit4 (`testDebugUnitTest`).

## Global Constraints

- Keep `SwarmEngine` public constructor EXACTLY: `(db: AppDatabaseInterface, gitService: GitService, mcpClient: McpClientInterface, appContext: android.content.Context, securePrefs: SecurePrefsInterface, ollamaService: OllamaService = OllamaServiceDefault, dispatcher: CoroutineDispatcher = Dispatchers.IO)`.
- Keep `suspend fun SwarmEngine.generateFreeform(prompt: String, systemPrompt: String, preferCloud: Boolean = false): String` — public, unchanged signature.
- `AgentStateStore`, `PendingApprovalStore` stay sanctioned singletons (standards/state). Do NOT inject them.
- New seams ONLY for I/O classes: `LlmRouterInterface`, `AgenticActionExecutorInterface`. `StepRunner` is pure orchestration -> concrete class, NO interface (standards/data/interface-seam).
- Package stays `com.example.data`. No new Gradle deps. No DI framework.
- Run tests with `./gradlew :app:testDebugUnitTest`. `forkEvery=1`, heap bounded (do not change `app/build.gradle.kts`).
- The 8 existing `SwarmEngine*Test.kt` files must PASS UNTOUCHED after every task. Never edit them to force green.
- Behavior must be byte-for-byte identical: same step actionTypes, same step content strings, same status strings, same delays, same metrics math. This is a pure refactor (move code), not a behavior change.

## Source-of-truth line map (current SwarmEngine.kt, 1264 lines)

- `executeTask` 18-102 (lifecycle; NOT moved).
- 5 workflows: `runSequentialWorkflow` 103-163, `runPeerToPeerWorkflow` 165-420ish, `runConsensusVoteWorkflow` ~340-420, `runAgenticLoopWorkflow` 464-623, `runDynamicRoutingWorkflow` 1123-1256.
- Helpers to MOVE to `LlmRouter`: `generateOutputForAgentStreaming` 625-633, `generateFreeform` 640-641, `generateFreeformStreaming` 643-647, `buildSkillsContext` 673-687, `generateFromFallbackPool` 698-756, `OllamaNode.isCloudGatewayNode` 760-761, `resolveApiKeyForNode` (inside 698-756 region), `StreamThrottle` 765-778 (shared -> move to own file).
- Helpers to MOVE to `AgenticActionExecutor`: `parseAndExecuteAgenticActions` 784-810, `executeAgenticGitCommand` 812-925, `executeAgenticMcpCall` 927-1021, `isRiskyMcpCall` 1027-1033, `executeAgenticFileWrite` 1042-1077, `autoCheckpoint` 1085-1099, `validateRequiredArgs` 1101-1109, `parseJsonArguments` 1111-1121, `ActionOutcome` data class 782.
- Helpers that STAY in engine: `updateTaskStatus` 1258-1263, `Todo`/`roleTagRegex`/`inferRoleFromText`/`pickAgentForRole`/`pickQaAgent` 422-448.

---

### Task 1: Extract `StreamThrottle` + `LlmRouter` (Ollama-pool entry point)

**Files:**
- Create: `app/src/main/java/com/example/data/StreamThrottle.kt`
- Create: `app/src/main/java/com/example/data/LlmRouterInterface.kt`
- Create: `app/src/main/java/com/example/data/LlmRouter.kt`
- Modify: `app/src/main/java/com/example/data/SwarmEngine.kt` (move helpers out; keep thin delegates)
- Test: `app/src/test/java/com/example/data/LlmRouterTest.kt`

**Interfaces:**
- Consumes: existing `OllamaService` (`generate`, `generateStreaming`), `OllamaNodeDao.getAllNodesSync()`, `ClaudeSkillDao.getAllSkillsSync()`, `SecurePrefsInterface`.
- Produces:
  - `class StreamThrottle(minCharDelta: Int = 20, minMillis: Long = 150) { fun shouldEmit(current: String): Boolean }`
  - `interface LlmRouterInterface { suspend fun generateForAgent(agent: Agent, prompt: String, preferCloud: Boolean = false, onToken: (suspend (String) -> Unit)? = null): String; suspend fun generateFreeform(prompt: String, systemPrompt: String, preferCloud: Boolean = false): String }`
  - `class LlmRouter(ollamaService: OllamaService, nodeDao: OllamaNodeDao, skillDao: ClaudeSkillDao, securePrefs: SecurePrefsInterface, dispatcher: CoroutineDispatcher = Dispatchers.IO) : LlmRouterInterface`

- [ ] **Step 1: Write the failing test** `app/src/test/java/com/example/data/LlmRouterTest.kt`

```kotlin
package com.example.data

import com.example.ui.FakeOllamaService
import com.example.ui.FakeSecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeNodeDao(private val nodes: List<OllamaNode>) {
    private val inner = com.example.ui.FakeAppDatabase()
    suspend fun seed() { nodes.forEach { inner.ollamaNodeDao().insertNode(it) } }
    fun dao(): OllamaNodeDao = inner.ollamaNodeDao()
}

class LlmRouterTest {
    private fun router(db: com.example.ui.FakeAppDatabase, ollama: OllamaService = FakeOllamaService()) =
        LlmRouter(
            ollamaService = ollama,
            nodeDao = db.ollamaNodeDao(),
            skillDao = db.claudeSkillDao(),
            securePrefs = FakeSecurePrefs(),
            dispatcher = Dispatchers.Unconfined
        )

    @Test
    fun generateFreeform_picksLowestLatencyOnlineNode() = runTest(UnconfinedTestDispatcher()) {
        val db = com.example.ui.FakeAppDatabase()
        db.ollamaNodeDao().insertNode(OllamaNode(id = 1, name = "slow", url = "http://slow", status = "Online", latencyMs = 900, availableModels = "llama3"))
        db.ollamaNodeDao().insertNode(OllamaNode(id = 2, name = "fast", url = "http://fast", status = "Online", latencyMs = 5, availableModels = "llama3"))
        db.ollamaNodeDao().insertNode(OllamaNode(id = 3, name = "off", url = "http://off", status = "Offline", latencyMs = 1, availableModels = "llama3"))
        val out = router(db).generateFreeform("hi", "sys")
        assertTrue(out.contains("[FakeOllama]"))
    }

    @Test
    fun generateFreeform_noOnlineNode_returnsConfigureMessage() = runTest(UnconfinedTestDispatcher()) {
        val db = com.example.ui.FakeAppDatabase()
        db.ollamaNodeDao().getAllNodesSync().forEach { db.ollamaNodeDao().updateNode(it.copy(status = "Offline")) }
        val out = router(db).generateFreeform("hi", "sys")
        assertEquals("Error: no online Ollama node available to service this request. Configure at least one node in Manage > Nodes.", out)
    }

    @Test
    fun generateForAgent_appendsSkillsContextToSystemPrompt() = runTest(UnconfinedTestDispatcher()) {
        val db = com.example.ui.FakeAppDatabase()
        db.ollamaNodeDao().getAllNodesSync().first().let { db.ollamaNodeDao().updateNode(it.copy(status = "Online", latencyMs = 10)) }
        db.claudeSkillDao().insertSkill(ClaudeSkill(id = 99, name = "Git Branch Creator", description = "d", category = "Development", isEnabled = true, usageExample = "git branch x", requiredMcpServerType = "None"))
        val captured = mutableListOf<String?>()
        val capturing = object : OllamaService by FakeOllamaService() {
            override suspend fun generate(nodeUrl: String, modelName: String, prompt: String, systemPrompt: String?, apiKey: String?): String? {
                captured += systemPrompt
                return "ok"
            }
        }
        val agent = Agent(id = 1, name = "A", role = "Programmer", modelName = "llama3", systemPrompt = "BASE", colorHex = "#000")
        router(db, capturing).generateForAgent(agent, "do it")
        assertTrue(captured.single()!!.startsWith("BASE"))
        assertTrue(captured.single()!!.contains("AVAILABLE SYSTEM TOOLS & MCP SKILLS"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.data.LlmRouterTest"`
Expected: FAIL — `unresolved reference: LlmRouter` / `StreamThrottle`.

- [ ] **Step 3: Move `StreamThrottle` to its own file** `app/src/main/java/com/example/data/StreamThrottle.kt`

Move the class VERBATIM from `SwarmEngine.kt` lines 763-778 (keep the KDoc). Package `com.example.data`, no `private` so it is shared:

```kotlin
package com.example.data

/** Gates DB writes for an in-progress streamed step so a fast token stream doesn't turn into a
 *  write per NDJSON line; only emits once the text has grown enough or enough time passed. */
class StreamThrottle(private val minCharDelta: Int = 20, private val minMillis: Long = 150) {
    private var lastLength = 0
    private var lastEmitTime = 0L

    fun shouldEmit(current: String): Boolean {
        val now = System.currentTimeMillis()
        if (current.length - lastLength >= minCharDelta || now - lastEmitTime >= minMillis) {
            lastLength = current.length
            lastEmitTime = now
            return true
        }
        return false
    }
}
```

- [ ] **Step 4: Create the seam** `app/src/main/java/com/example/data/LlmRouterInterface.kt`

```kotlin
package com.example.data

/** Seam over the Ollama node-pool router so tests can drive deterministic LLM routing without
 *  network I/O. Single entry point for every LLM call (standards/backend/swarm-engine-llm-pool). */
interface LlmRouterInterface {
    /** Generate using a specific agent's persona (system prompt + skills context + preferred model). */
    suspend fun generateForAgent(
        agent: Agent,
        prompt: String,
        preferCloud: Boolean = false,
        onToken: (suspend (String) -> Unit)? = null
    ): String

    /** Generate with a freeform system prompt (consensus moderator, voice routing, chat, sandbox, self-heal). */
    suspend fun generateFreeform(prompt: String, systemPrompt: String, preferCloud: Boolean = false): String

    /** Streaming freeform variant (consensus-vote scoring). */
    suspend fun generateFreeformStreaming(prompt: String, systemPrompt: String, onToken: suspend (String) -> Unit): String
}
```

- [ ] **Step 5: Create `LlmRouter`** `app/src/main/java/com/example/data/LlmRouter.kt`

Move VERBATIM (only changing `private`->`override`/internal as needed and routing `db.xxxDao()` to the injected DAOs) these from `SwarmEngine.kt`: `buildSkillsContext` (673-687), `generateFromFallbackPool` (698-756, incl. `resolveApiKeyForNode` helper it uses), `OllamaNode.isCloudGatewayNode` (760-761). `generateOutputForAgentStreaming`/`generateFreeformStreaming` collapse into the two interface methods (the streaming variant just forwards `onToken`). Public shape:

```kotlin
package com.example.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Single entry point for reaching an LLM. Always resolves against the Ollama node pool;
 *  there is no cloud/Gemini fallback (standards/backend/swarm-engine-llm-pool). */
class LlmRouter(
    private val ollamaService: OllamaService,
    private val nodeDao: OllamaNodeDao,
    private val skillDao: ClaudeSkillDao,
    private val securePrefs: SecurePrefsInterface,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : LlmRouterInterface {

    override suspend fun generateForAgent(
        agent: Agent,
        prompt: String,
        preferCloud: Boolean,
        onToken: (suspend (String) -> Unit)?
    ): String {
        val systemPromptWithSkills = agent.systemPrompt + buildSkillsContext()
        return generateFromFallbackPool(prompt, systemPromptWithSkills, preferredModelName = agent.modelName, preferCloud = preferCloud, onToken = onToken)
    }

    override suspend fun generateFreeform(prompt: String, systemPrompt: String, preferCloud: Boolean): String =
        generateFromFallbackPool(prompt, systemPrompt, preferredModelName = null, preferCloud = preferCloud)

    // --- moved verbatim from SwarmEngine.kt: buildSkillsContext(), generateFromFallbackPool(),
    //     resolveApiKeyForNode(), private fun OllamaNode.isCloudGatewayNode(). ---
    // buildSkillsContext() reads skillDao.getAllSkillsSync() instead of db.claudeSkillDao().
    // generateFromFallbackPool() reads nodeDao.getAllNodesSync() instead of db.ollamaNodeDao().
}
```

- [ ] **Step 6: Slim `SwarmEngine` to delegate, keeping public API**

In `SwarmEngine.kt`: delete the moved bodies; add a `private val llmRouter: LlmRouterInterface` (wired in Task 4's secondary constructor; for Task 6 wire it inline from constructor deps). Replace old call sites with delegates so existing engine code + the public API keep compiling/behaving:

```kotlin
// Keep the public API the ViewModel uses — now a one-line delegate.
suspend fun generateFreeform(prompt: String, systemPrompt: String, preferCloud: Boolean = false): String =
    llmRouter.generateFreeform(prompt, systemPrompt, preferCloud)

// Private shims used by the workflows until Task 3 rewrites them:
private suspend fun generateOutputForAgentStreaming(agent: Agent, prompt: String, onToken: suspend (String) -> Unit, preferCloud: Boolean = false): String =
    llmRouter.generateForAgent(agent, prompt, preferCloud, onToken)
private suspend fun generateFreeformStreaming(prompt: String, systemPrompt: String, onToken: suspend (String) -> Unit): String =
    llmRouter.generateFreeformStreaming(prompt, systemPrompt, onToken)
```

`LlmRouter.generateFreeformStreaming` (added to the interface in Step 4) is implemented as `generateFromFallbackPool(prompt, systemPrompt, preferredModelName = null, onToken = onToken)`. `buildSkillsContext()` is DELETED from the engine (its only caller was `generateOutputForAgentStreaming`, now inside `LlmRouter`). Also delete the engine's private `generateFromFallbackPool`, `isCloudGatewayNode`, `resolveApiKeyForNode`, and `StreamThrottle` (now in its own file). The engine's `llmRouter` field is wired in Task 4's secondary constructor; for this task, construct it inline from the existing constructor deps (`LlmRouter(ollamaService, db.ollamaNodeDao(), db.claudeSkillDao(), securePrefs, dispatcher)`).

- [ ] **Step 7: Run new test + full suite to verify green**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.data.LlmRouterTest"`
Expected: PASS
Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — all 8 `SwarmEngine*Test` still green (constructor + behavior unchanged).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/example/data/StreamThrottle.kt app/src/main/java/com/example/data/LlmRouterInterface.kt app/src/main/java/com/example/data/LlmRouter.kt app/src/main/java/com/example/data/SwarmEngine.kt app/src/test/java/com/example/data/LlmRouterTest.kt
git commit -m "refactor: extract LlmRouter (single Ollama-pool entry point) from SwarmEngine"
```

---

### Task 2: Extract `AgenticActionExecutor` (git / MCP_CALL / WRITE_FILE side effects)

**Files:**
- Create: `app/src/main/java/com/example/data/AgenticActionExecutorInterface.kt`
- Create: `app/src/main/java/com/example/data/AgenticActionExecutor.kt`
- Modify: `app/src/main/java/com/example/data/SwarmEngine.kt` (move parser + executors out; keep thin delegate)
- Test: `app/src/test/java/com/example/data/AgenticActionExecutorTest.kt`

**Interfaces:**
- Consumes: `LlmRouterInterface` (Task 1) for the WRITE_FILE content round-trip; existing `GitService` (`mirrorFiles`, `commitAll`, `push`, `localHeadHash`), `McpClientInterface` (`initialize`, `callTool`), `SecurePrefsInterface` (`getGitToken`, `getMcpToken`), DAOs, singletons `PendingApprovalStore` / `AgentStateStore`.
- Produces:
  - `data class ActionOutcome(val mcpCallAttempted: Boolean, val mcpCallSucceeded: Boolean, val mcpResultText: String?)` (moved out of engine; same fields, now top-level in its own file or in the interface file).
  - `interface AgenticActionExecutorInterface { suspend fun parseAndExecute(taskId: Int, agentName: String, output: String, mcpSuccessActionType: String = "MCP_TOOL_CALL", mcpFailureActionType: String = "MCP_CALL_FAILED"): ActionOutcome; suspend fun autoCheckpoint(taskId: Int, agentName: String, todoText: String) }`
  - `class AgenticActionExecutor(db: AppDatabaseInterface, gitService: GitService, mcpClient: McpClientInterface, appContext: android.content.Context, securePrefs: SecurePrefsInterface, llmRouter: LlmRouterInterface, dispatcher: CoroutineDispatcher = Dispatchers.IO) : AgenticActionExecutorInterface`

- [ ] **Step 1: Write the failing test** `app/src/test/java/com/example/data/AgenticActionExecutorTest.kt`

```kotlin
package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.ui.FakeAppDatabase
import com.example.ui.FakeMcpClient
import com.example.ui.FakeSecurePrefs
import com.example.ui.FakeOllamaService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AgenticActionExecutorTest {
    @Before fun reset() { PendingApprovalStore.reset() }
    @After fun clear() { PendingApprovalStore.reset() }

    private fun executor(db: AppDatabaseInterface): AgenticActionExecutor {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val git = GitService(File(context.cacheDir, "git-aae-${System.nanoTime()}"))
        val llm = LlmRouter(FakeOllamaService(), db.ollamaNodeDao(), db.claudeSkillDao(), FakeSecurePrefs(), Dispatchers.Unconfined)
        return AgenticActionExecutor(db, git, FakeMcpClient(), context, FakeSecurePrefs(), llm, Dispatchers.Unconfined)
    }

    @Test
    fun unknownSkill_emitsFailureStep_andReportsAttempted() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val taskId = db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()
        val outcome = executor(db).parseAndExecute(taskId, "AgentX", "MCP_CALL: No Such Skill | {}")
        assertTrue(outcome.mcpCallAttempted)
        assertTrue(!outcome.mcpCallSucceeded)
        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        assertTrue(steps.any { it.actionType == "MCP_CALL_FAILED" && it.content.contains("No Such Skill") })
    }

    @Test
    fun gitBranch_directive_emitsNotImplementedStep() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val taskId = db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()
        executor(db).parseAndExecute(taskId, "AgentY", "git branch feature/x")
        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        assertTrue(steps.any { it.actionType == "GIT_BRANCH" })
    }

    @Test
    fun noDirectives_reportsNoAttempt() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val taskId = db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()
        val outcome = executor(db).parseAndExecute(taskId, "AgentZ", "just prose, no directives")
        assertTrue(!outcome.mcpCallAttempted)
        assertEquals(0, db.taskStepDao().getStepsForTaskSync(taskId).size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.data.AgenticActionExecutorTest"`
Expected: FAIL — `unresolved reference: AgenticActionExecutor`.

- [ ] **Step 3: Create the seam** `app/src/main/java/com/example/data/AgenticActionExecutorInterface.kt`

Move `ActionOutcome` here (top-level, same 3 fields). Interface:

```kotlin
package com.example.data

/** Result of scanning one agent output for directives -- used by the verify phase to decide
 *  whether a real tool call was attempted and whether it looked like it failed. */
data class ActionOutcome(val mcpCallAttempted: Boolean, val mcpCallSucceeded: Boolean, val mcpResultText: String?)

/** Seam over the agentic-directive executor (git commands, MCP_CALL, WRITE_FILE side effects) so
 *  tests can exercise directive handling without real JGit/network/Keystore I/O. */
interface AgenticActionExecutorInterface {
    suspend fun parseAndExecute(
        taskId: Int,
        agentName: String,
        output: String,
        mcpSuccessActionType: String = "MCP_TOOL_CALL",
        mcpFailureActionType: String = "MCP_CALL_FAILED"
    ): ActionOutcome

    suspend fun autoCheckpoint(taskId: Int, agentName: String, todoText: String)
}
```

- [ ] **Step 4: Create `AgenticActionExecutor`** `app/src/main/java/com/example/data/AgenticActionExecutor.kt`

Move VERBATIM from `SwarmEngine.kt`, renaming only the entry point and routing the WRITE_FILE content round-trip through the injected `llmRouter` instead of `generateFreeform`:
- `parseAndExecuteAgenticActions` (784-810) -> `override suspend fun parseAndExecute(...)` (same params/defaults, same `ActionOutcome`).
- `executeAgenticGitCommand` (812-925) -> private, unchanged (uses `appContext.getSharedPreferences("ollama_swarm_prefs", ...)`, `securePrefs.getGitToken()`, `PendingApprovalStore`, `gitService`, `db.gitCommitDao()`, `db.taskStepDao()`).
- `executeAgenticMcpCall` (927-1021) -> private, unchanged (`db.claudeSkillDao()`, `db.mcpServerDao()`, `db.mcpToolDao()`, `securePrefs.getMcpToken`, `mcpClient`).
- `isRiskyMcpCall` (1027-1033), `validateRequiredArgs` (1101-1109), `parseJsonArguments` (1111-1121) -> private, unchanged.
- `executeAgenticFileWrite` (1042-1077) -> private; the one edit is `val proposedContent = llmRouter.generateFreeform(contentPrompt, "You are an expert software engineer producing exact file contents for direct use, not a chat response.", preferCloud = true)`.
- `autoCheckpoint` (1085-1099) -> `override suspend fun autoCheckpoint(...)`, unchanged body (`gitService.mirrorFiles` + `commitAll`, `db.gitCommitDao()`, `db.taskStepDao()`).

Constructor:

```kotlin
class AgenticActionExecutor(
    private val db: AppDatabaseInterface,
    private val gitService: GitService,
    private val mcpClient: McpClientInterface,
    private val appContext: android.content.Context,
    private val securePrefs: SecurePrefsInterface,
    private val llmRouter: LlmRouterInterface,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : AgenticActionExecutorInterface { /* moved bodies */ }
```

- [ ] **Step 5: Slim `SwarmEngine` to delegate**

Delete the moved bodies from `SwarmEngine.kt`. Keep a private shim so the workflows compile until Task 3:

```kotlin
private val actionExecutor: AgenticActionExecutorInterface =
    AgenticActionExecutor(db, gitService, mcpClient, getApplicationContext(), securePrefs, llmRouter, dispatcher)

private suspend fun parseAndExecuteAgenticActions(taskId: Int, agentName: String, output: String, mcpSuccessActionType: String = "MCP_TOOL_CALL", mcpFailureActionType: String = "MCP_CALL_FAILED"): ActionOutcome =
    actionExecutor.parseAndExecute(taskId, agentName, output, mcpSuccessActionType, mcpFailureActionType)

private suspend fun autoCheckpoint(taskId: Int, agentName: String, todoText: String) =
    actionExecutor.autoCheckpoint(taskId, agentName, todoText)
```

(`appContext` is the engine's existing constructor param — reuse that exact reference for the executor, not a new lookup.) Delete the engine's now-unused `ActionOutcome` data class.

- [ ] **Step 6: Run new test + full suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.data.AgenticActionExecutorTest"` -> PASS
Run: `./gradlew :app:testDebugUnitTest` -> all 8 `SwarmEngine*Test` still PASS untouched (ApprovalGate/VerifyLoop/FileWrite exercise this code through the engine).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/data/AgenticActionExecutorInterface.kt app/src/main/java/com/example/data/AgenticActionExecutor.kt app/src/main/java/com/example/data/SwarmEngine.kt app/src/test/java/com/example/data/AgenticActionExecutorTest.kt
git commit -m "refactor: extract AgenticActionExecutor (git/MCP/WRITE_FILE directives) from SwarmEngine"
```

---

### Task 3: Extract `StepRunner` (the shared 8-beat agent-step loop) and rewrite the 5 workflows

**Files:**
- Create: `app/src/main/java/com/example/data/StepRunner.kt`
- Modify: `app/src/main/java/com/example/data/SwarmEngine.kt` (rewrite the 5 workflows to policy; delete inline beats)
- Test: `app/src/test/java/com/example/data/StepRunnerTest.kt`

**Interfaces:**
- Consumes: `LlmRouterInterface.generateForAgent` (Task 1), `AgenticActionExecutorInterface.parseAndExecute` (Task 2), `TaskStepDao.insertStep`, singleton `AgentStateStore`.
- Produces:
  - `class StepRunner(taskStepDao: TaskStepDao, llmRouter: LlmRouterInterface, actionExecutor: AgenticActionExecutorInterface)` — concrete, NO interface (pure orchestration; standards/data/interface-seam).
  - `data class StepRequest(val agent: Agent, val prompt: String, val thinkingActionType: String = "THINKING", val finalActionType: String = "OUTPUT", val thinkingContent: String = "Analyzing prompt and building execution context...", val preferCloud: Boolean = false, val interStepDelayMs: Long = 1000, val thinkingDelayMs: Long = 1500, val mcpSuccessActionType: String = "MCP_TOOL_CALL", val mcpFailureActionType: String = "MCP_CALL_FAILED")`
  - `data class StepResult(val stepId: Int, val output: String, val durationMs: Long, val approxTokens: Int)`
  - `suspend fun StepRunner.run(taskId: Int, updateStatus: (String) -> Unit, req: StepRequest): StepResult`

This is the deep module. `run()` performs exactly the current 8 beats in order:
1. `val start = System.currentTimeMillis()`
2. `AgentStateStore.setAgentActive(req.agent.id, true, label)` (label derived from actionType as today: last->"Synthesizing", else per-mode strings — pass `activeLabel` in `StepRequest`, default `"Thinking"`).
3. `updateStatus(...)` via the injected lambda (engine passes its `updateTaskStatus`).
4. `insertStep(TaskStep(taskId, agent, role, req.thinkingActionType, req.thinkingContent))` -> `stepId`.
5. `delay(req.thinkingDelayMs)`.
6. `streamIntoStep`-equivalent: call `llmRouter.generateForAgent(req.agent, req.prompt, req.preferCloud) { token -> if (StreamThrottle().shouldEmit(acc)) insertStep(replace stepId with req.thinkingActionType, partial) }`.
7. `actionExecutor.parseAndExecute(taskId, req.agent.name, output, req.mcpSuccessActionType, req.mcpFailureActionType)`.
8. `insertStep(replace stepId, actionType = req.finalActionType, content = output)`; `AgentStateStore.recordExecutionMetrics(agent.id, dur, tokens)`; `AgentStateStore.setAgentActive(agent.id, false, "Idle")`; `delay(req.interStepDelayMs)`; return `StepResult`.

Keep `StreamThrottle` + the in-place REPLACE-on-id streaming EXACTLY as `streamIntoStep` does today (move `streamIntoStep`'s body into StepRunner as a private helper).

- [ ] **Step 1: Write the failing test** `app/src/test/java/com/example/data/StepRunnerTest.kt`

```kotlin
package com.example.data

import com.example.ui.FakeAppDatabase
import com.example.ui.FakeOllamaService
import com.example.ui.FakeSecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeActionExecutor : AgenticActionExecutorInterface {
    val parsed = mutableListOf<String>()
    override suspend fun parseAndExecute(taskId: Int, agentName: String, output: String, mcpSuccessActionType: String, mcpFailureActionType: String): ActionOutcome {
        parsed += output
        return ActionOutcome(mcpCallAttempted = false, mcpCallSucceeded = false, mcpResultText = null)
    }
    override suspend fun autoCheckpoint(taskId: Int, agentName: String, todoText: String) {}
}

class StepRunnerTest {
    @Test
    fun run_replacesPlaceholderWithFinalOutput_andParsesActions() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        db.ollamaNodeDao().getAllNodesSync().first().let { db.ollamaNodeDao().updateNode(it.copy(status = "Online", latencyMs = 10)) }
        val agent = Agent(id = 7, name = "Solo", role = "Programmer", modelName = "llama3", systemPrompt = "s", colorHex = "#000")
        val taskId = db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()
        val executor = FakeActionExecutor()
        val runner = StepRunner(
            taskStepDao = db.taskStepDao(),
            llmRouter = LlmRouter(FakeOllamaService(), db.ollamaNodeDao(), db.claudeSkillDao(), FakeSecurePrefs(), Dispatchers.Unconfined),
            actionExecutor = executor
        )
        val statuses = mutableListOf<String>()
        val result = runner.run(taskId, { statuses += it }, StepRequest(agent = agent, prompt = "do the thing", interStepDelayMs = 0, thinkingDelayMs = 0))
        // one logical step row, final content is the model output
        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        assertTrue(steps.isNotEmpty())
        assertEquals(result.output, steps.last().content)
        assertEquals("OUTPUT", steps.last().actionType)
        assertTrue(executor.parsed.single() == result.output)
        assertTrue(statuses.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.data.StepRunnerTest"`
Expected: FAIL — `unresolved reference: StepRunner`.

- [ ] **Step 3: Implement `StepRunner`** `app/src/main/java/com/example/data/StepRunner.kt`

Implement the 8 beats above. Move the body of `streamIntoStep` (SwarmEngine 655-671) in as a private helper so the throttle + REPLACE-on-id in-place streaming is preserved exactly. Use `StepRequest.activeLabel` (default `"Thinking"`) for the set-active label.

- [ ] **Step 4: Rewrite the 5 workflows to policy**

For each workflow, delete the inline 8 beats and call `stepRunner.run(...)`, keeping the SAME prompt strings, actionTypes, delays, and context-accumulation. Example for sequential (103-163):

```kotlin
private suspend fun runSequentialWorkflow(taskId: Int, agents: List<Agent>, userPrompt: String) {
    var context = "Initial Task Request:\n\"$userPrompt\"\n\n"
    for (index in agents.indices) {
        val agent = agents[index]
        val isLast = index == agents.size - 1
        val prompt = if (index == 0) "Complete the following task: $userPrompt"
            else "Previous swarm analysis:\n$context\n\nYour task is to review, add your specialty expertise (${agent.role}), and build upon this. Respond to: $userPrompt"
        val result = stepRunner.run(taskId, { s -> updateTaskStatus(taskId, s) }, StepRequest(
            agent = agent,
            prompt = prompt,
            finalActionType = if (isLast) "FINAL_RESPONSE" else "OUTPUT",
            activeLabel = if (isLast) "Synthesizing" else "Thinking"
        ))
        context += "--- ${agent.name} (${agent.role}) Output ---\n${result.output}\n\n"
    }
}
```

Apply the same mechanical conversion to:
- `runPeerToPeerWorkflow` (research/code/audit beats; keep CRITIQUING actionType + the exact prompt strings + `updateTaskStatus("Peer Task: ...")`).
- `runConsensusVoteWorkflow` (proposal beats -> StepRunner; keep the VOTING moderator step, which streams via `llmRouter.generateFreeformStreaming` and writes a single FINAL_RESPONSE row directly — NOT a StepRunner step since it has no placeholder/metrics pattern).
- `runAgenticLoopWorkflow` (plan/act/verify/synthesis steps; keep `preferCloud = true`, `EXEC_RESULT`/`EXEC_RESULT_FAILED` MCP action types via `mcpSuccessActionType`/`mcpFailureActionType`, the Todo/checklist logic, `pickAgentForRole`/`pickQaAgent`, and `actionExecutor.autoCheckpoint` calls — these stay in the engine).
- `runDynamicRoutingWorkflow` (routing-plan beat -> StepRunner with ROUTING final action; per-agent routed beats -> StepRunner; synthesis beat -> StepRunner with FINAL_RESPONSE).

Steps that do NOT fit the placeholder->stream->replace->metrics pattern (consensus moderator, the routing-plan JSON step) may keep a direct `llmRouter` + `db.taskStepDao()` call. Do not force them through StepRunner if it changes emitted rows.

Delete the engine's now-dead `streamIntoStep` and the per-beat `delay`/`recordExecutionMetrics` duplicates that StepRunner now owns. Keep `updateTaskStatus` (engine), used by workflows and passed into StepRunner.

- [ ] **Step 5: Run new test + full suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.data.StepRunnerTest"` -> PASS
Run: `./gradlew :app:testDebugUnitTest` -> all 8 `SwarmEngine*Test` PASS untouched (they assert step-row ids, actionTypes, metrics, approval gates, role routing through `executeTask`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/data/StepRunner.kt app/src/main/java/com/example/data/SwarmEngine.kt app/src/test/java/com/example/data/StepRunnerTest.kt
git commit -m "refactor: extract StepRunner; rewrite the five coordination workflows as policy"
```

---

### Task 4: Clean constructor wiring + update CONTEXT.md / ADR

**Files:**
- Modify: `app/src/main/java/com/example/data/SwarmEngine.kt` (single private secondary constructor that builds the 3 modules once)
- Modify: `CONTEXT.md` (create if absent)
- Create: `docs/adr/ADR-0001-agent-step-deep-modules.md`

**Interfaces:**
- Consumes: Tasks 1-3.
- Produces: unchanged public `SwarmEngine` 7-arg constructor (primary) + `generateFreeform`; internal wiring for `llmRouter`, `actionExecutor`, `stepRunner`.

- [ ] **Step 1: Centralize module construction**

Replace the per-field inline construction from Tasks 1-3 with one private initializer so the three modules are built once and shared (StepRunner reuses the same LlmRouter + AgenticActionExecutor instances; AgenticActionExecutor reuses the same LlmRouter):

```kotlin
// Primary public constructor UNCHANGED (used by SwarmViewModel + all 8 tests).
private val llmRouter: LlmRouterInterface = LlmRouter(ollamaService, db.ollamaNodeDao(), db.claudeSkillDao(), securePrefs, dispatcher)
private val actionExecutor: AgenticActionExecutorInterface = AgenticActionExecutor(db, gitService, mcpClient, appContext, securePrefs, llmRouter, dispatcher)
private val stepRunner: StepRunner = StepRunner(db.taskStepDao(), llmRouter, actionExecutor)
```

(These are property initializers in declaration order; `db`, `gitService`, etc. are constructor params already in scope. No secondary constructor is strictly needed if initializers are ordered top-to-bottom.)

- [ ] **Step 2: Run full suite**

Run: `./gradlew :app:testDebugUnitTest` -> PASS (all existing + 3 new test classes).

- [ ] **Step 3: Update CONTEXT.md** (create `/home/dev/OllamaDev/CONTEXT.md` if it does not exist)

Add domain terms for the new deep modules so future architecture reviews use the same names:

```markdown
# CONTEXT.md — Domain Glossary

## Deep modules (data/)
- **LlmRouter** — the single entry point for every LLM call; owns Ollama online-node selection,
  preferred-model matching, cloud-gateway routing, and the skills context appended to agent system
  prompts. No cloud/Gemini fallback. (agent-os/standards/backend/swarm-engine-llm-pool)
- **AgenticActionExecutor** — parses agent output for directives (git commands, `MCP_CALL:`,
  `WRITE_FILE:`) and performs the real side effects (JGit mirror/commit/push, MCP tool call, file
  write) behind the human-approval gate.
- **StepRunner** — the shared agent-step loop used by every coordination mode: set-active ->
  status -> THINKING placeholder -> delay -> stream into the step row in place -> parse directives
  -> replace with final output -> record metrics. Workflows supply only the agent, prompt, and
  action types.
- **Coordination mode** — the policy a `SwarmConfig` selects (SEQUENTIAL, PEER_TO_PEER,
  CONSENSUS_VOTE, AGENTIC_LOOP, DYNAMIC_ROUTING); a workflow in `SwarmEngine` that orders agents and
  builds prompts, delegating each step to `StepRunner`.
```

- [ ] **Step 4: Record the ADR** `docs/adr/ADR-0001-agent-step-deep-modules.md`

```markdown
# ADR-0001: Extract agent-step deep modules out of SwarmEngine

- Status: Accepted
- Date: 2026-07-23

## Context
The five coordination-mode workflows in `SwarmEngine.kt` each inlined the same 8-beat agent-step
loop (~200 lines x 5). Changing how a step emits progress required editing five places; each mode
was only testable end-to-end via `executeTask`.

## Decision
Extract three deep modules: `LlmRouter` (single Ollama-pool entry point; seam `LlmRouterInterface`),
`AgenticActionExecutor` (git/MCP/WRITE_FILE directive side effects; seam `AgenticActionExecutorInterface`),
and `StepRunner` (the shared step loop; concrete, no interface per the interface-seam standard since it
touches no I/O directly). `SwarmEngine` keeps its existing 7-arg public constructor and public
`generateFreeform()` so all pre-existing tests stay green without modification; the engine builds the
three modules internally.

## Consequences
+ Step-emission behavior lives in one place (locality + leverage across all modes).
+ Each module is unit-testable through its own interface.
+ The Ollama single-entry-point standard is preserved (now `LlmRouter`).
- `SwarmEngine` remains the composition root; construction is internal, so callers cannot yet inject
  a fake `StepRunner` — acceptable, since `StepRunner` is pure orchestration covered via `LlmRouter`
  / `AgenticActionExecutor` fakes.
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/data/SwarmEngine.kt CONTEXT.md docs/adr/ADR-0001-agent-step-deep-modules.md
git commit -m "docs: record agent-step deep-module extraction (ADR-0001) and CONTEXT glossary"
```

---

## Self-Review

- **Spec coverage:** Candidate 01 (extract one deep "agent step" module; dedupe 5 workflows) -> Task 3. Supporting single-ownership split chosen during grilling -> Tasks 1 (LlmRouter) & 2 (AgenticActionExecutor). Wire-up + domain vocab + ADR -> Task 4. Tests kept green by construction -> Global Constraints (constructor + `generateFreeform` unchanged; new tests added, none edited).
- **Placeholder scan:** All code blocks contain concrete code or exact move instructions with line ranges; no TBD/TODO. The only "moved verbatim" notes reference exact current line numbers verified in this session.
- **Type consistency:** `LlmRouterInterface` methods = `generateForAgent`, `generateFreeform`, `generateFreeformStreaming` (declared in Task 1 Step 4, used in Task 1 Step 6 & Task 3). `AgenticActionExecutorInterface` = `parseAndExecute`, `autoCheckpoint` (Task 2), consumed by StepRunner (Task 3). `ActionOutcome` moved to the interface file (Task 2 Step 3), referenced by StepRunner/engine. `StepRequest`/`StepResult` defined and used only in Task 3. Engine field order in Task 4 matches constructor params. Consistent.
