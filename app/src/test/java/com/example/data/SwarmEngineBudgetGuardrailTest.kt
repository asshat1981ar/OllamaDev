package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.ui.FakeAppDatabase
import com.example.ui.FakeMcpClient
import com.example.ui.FakeSecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/**
 * Scripted Ollama service that emits a two-todo plan and deterministic act/verify responses.
 * The act response is long enough that two act + two verify calls will exceed a small token cap,
 * forcing the budget guardrail to halt the agentic loop.
 */
private class BudgetGuardrailScriptedOllamaService(
    private val planResponse: String,
    private val actResponse: String,
    private val verifyResponse: String
) : OllamaService {
    override suspend fun generate(
        nodeUrl: String,
        modelName: String,
        prompt: String,
        systemPrompt: String?,
        apiKey: String?
    ): String? = respond(prompt)

    override suspend fun generateStreaming(
        nodeUrl: String,
        modelName: String,
        prompt: String,
        systemPrompt: String?,
        apiKey: String?,
        onToken: suspend (String) -> Unit
    ): String? {
        val full = respond(prompt)
        onToken(full)
        return full
    }

    private fun respond(prompt: String): String = when {
        prompt.startsWith("Break the following request") -> planResponse
        prompt.contains("As QA, verify this was actually done correctly") -> verifyResponse
        else -> actResponse
    }

    override suspend fun pingAndFetchModels(nodeUrl: String, apiKey: String?): Pair<Boolean, List<String>> =
        true to listOf("llama3")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SwarmEngineBudgetGuardrailTest {

    @Before
    fun resetApprovalStore() {
        PendingApprovalStore.reset()
    }

    private fun buildEngine(
        db: AppDatabaseInterface,
        ollamaService: OllamaService,
        mcpClient: McpClientInterface,
        securePrefs: SecurePrefsInterface,
        context: android.app.Application,
        cap: Int
    ): SwarmEngine {
        val gitService = GitService(File(context.cacheDir, "git-budget-${System.nanoTime()}"))
        // Use a custom budget tracker factory so the test can set a low cap without relying on
        // SharedPreferences parsing.
        return SwarmEngine(
            db = db,
            gitService = gitService,
            mcpClient = mcpClient,
            appContext = context,
            securePrefs = securePrefs,
            ollamaService = ollamaService,
            dispatcher = Dispatchers.Unconfined,
            budgetTrackerFactory = { TaskBudgetTracker.Ledger(cap) }
        )
    }

    @Test
    fun lowCap_haltsAgenticLoop_andRecordsBudgetHaltStep() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val node = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        db.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))
        db.agentDao().insertAgent(Agent(id = 800, name = "Budget Architect", role = "Architect", modelName = "llama3", systemPrompt = "", colorHex = "#000000"))
        db.agentDao().insertAgent(Agent(id = 801, name = "Budget Coder", role = "Programmer", modelName = "llama3", systemPrompt = "", colorHex = "#000000"))
        db.agentDao().insertAgent(Agent(id = 802, name = "Budget QA", role = "QA Engineer", modelName = "llama3", systemPrompt = "", colorHex = "#000000"))
        val config = SwarmConfig(
            id = 800,
            name = "Budget Guardrail Swarm",
            description = "",
            coordinationMode = "AGENTIC_LOOP",
            agentIds = "800,801,802"
        )

        // A cap of 50 tokens is far below the cost of two act+verify iterations, since the
        // approximate token heuristic is (prompt.length + output.length) / 2 + 100 per call.
        val cap = 50

        // Long deterministic act output so each call blows through the cap immediately.
        val actResponse = "Implemented the requested change. " + "x ".repeat(200)
        val verifyResponse = "Verified successfully."
        val ollama = BudgetGuardrailScriptedOllamaService(
            planResponse = "- [Programmer] implement step one\n- [Programmer] implement step two",
            actResponse = actResponse,
            verifyResponse = verifyResponse
        )

        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val engine = buildEngine(db, ollama, FakeMcpClient(), FakeSecurePrefs(), context, cap)

        var taskId = -1
        val job = launch { engine.executeTask(config, "budget guardrail test", onTaskCreated = { taskId = it }) }
        job.join()

        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        assertTrue(
            "Expected a BUDGET_HALT step when the cap is exceeded; steps: ${steps.map { it.actionType }}",
            steps.any { it.actionType == "BUDGET_HALT" }
        )
        assertTrue(
            "Expected a BUDGET_HALT step to mention the cap; got: '${steps.firstOrNull { it.actionType == "BUDGET_HALT" }?.content}'",
            steps.firstOrNull { it.actionType == "BUDGET_HALT" }?.content?.contains("cap: $cap") == true
        )
        assertTrue(
            "Expected remaining todos to be marked [BUDGET HALT] in the plan content; last plan: ${steps.lastOrNull { it.actionType == "PLAN" }?.content}",
            steps.any { it.actionType == "PLAN" && it.content.contains("[BUDGET HALT]") }
        )
    }

    @Test
    fun zeroCap_doesNotHaltLoop() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val node = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        db.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))
        db.agentDao().insertAgent(Agent(id = 803, name = "Budget Architect", role = "Architect", modelName = "llama3", systemPrompt = "", colorHex = "#000000"))
        db.agentDao().insertAgent(Agent(id = 804, name = "Budget Coder", role = "Programmer", modelName = "llama3", systemPrompt = "", colorHex = "#000000"))
        db.agentDao().insertAgent(Agent(id = 805, name = "Budget QA", role = "QA Engineer", modelName = "llama3", systemPrompt = "", colorHex = "#000000"))
        val config = SwarmConfig(
            id = 803,
            name = "Budget Disabled Swarm",
            description = "",
            coordinationMode = "AGENTIC_LOOP",
            agentIds = "803,804,805"
        )

        // cap = 0 disables the guardrail entirely.
        val cap = 0
        val actResponse = "Done."
        val verifyResponse = "Verified."
        val ollama = BudgetGuardrailScriptedOllamaService(
            planResponse = "- [Programmer] implement step one",
            actResponse = actResponse,
            verifyResponse = verifyResponse
        )

        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val engine = buildEngine(db, ollama, FakeMcpClient(), FakeSecurePrefs(), context, cap)

        var taskId = -1
        val job = launch { engine.executeTask(config, "budget disabled test", onTaskCreated = { taskId = it }) }
        job.join()

        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        assertTrue(
            "Did not expect a BUDGET_HALT step when cap is disabled; steps: ${steps.map { it.actionType }}",
            steps.none { it.actionType == "BUDGET_HALT" }
        )
        assertTrue(
            "Expected the single todo to be marked done; plan steps: ${steps.filter { it.actionType == "PLAN" }.map { it.content }}",
            steps.any { it.actionType == "PLAN" && it.content.contains("[x]") }
        )
    }
}
