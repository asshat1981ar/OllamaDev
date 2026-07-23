package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.ui.FakeAppDatabase
import com.example.ui.FakeMcpClient
import com.example.ui.FakeSecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/** Ollama fake that returns a fixed plan response and a neutral, directive-free response for
 *  everything else, so tests can focus purely on which agent each step routed to. */
private class ScriptedPlanOllamaService(private val planResponse: String) : OllamaService {
    override suspend fun generate(nodeUrl: String, modelName: String, prompt: String, systemPrompt: String?, apiKey: String?): String? =
        respond(prompt)

    override suspend fun generateStreaming(
        nodeUrl: String, modelName: String, prompt: String, systemPrompt: String?, apiKey: String?,
        onToken: suspend (String) -> Unit
    ): String? {
        val full = respond(prompt)
        onToken(full)
        return full
    }

    private fun respond(prompt: String): String =
        if (prompt.startsWith("Break the following request")) planResponse else "Acknowledged, step complete."

    override suspend fun pingAndFetchModels(nodeUrl: String, apiKey: String?): Pair<Boolean, List<String>> =
        true to listOf("llama3")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SwarmEngineRoleAssignmentTest {

    private fun buildEngine(db: AppDatabaseInterface, ollamaService: OllamaService): SwarmEngine {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val gitService = GitService(File(context.cacheDir, "git-test-${System.nanoTime()}"))
        return SwarmEngine(
            db = db, gitService = gitService, mcpClient = FakeMcpClient(), appContext = context,
            securePrefs = FakeSecurePrefs(), ollamaService = ollamaService, dispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun agenticLoop_roleTaggedChecklist_routesActStepsToMatchingAgentAndVerifyToQa() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val node = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        db.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))

        val architect = Agent(id = 501, name = "Arch", role = "Architect", modelName = "llama3", systemPrompt = "", colorHex = "#000000")
        val programmer = Agent(id = 502, name = "Prog", role = "Programmer", modelName = "llama3", systemPrompt = "", colorHex = "#000000")
        val qa = Agent(id = 503, name = "QaBot", role = "QA Engineer", modelName = "llama3", systemPrompt = "", colorHex = "#000000")
        db.agentDao().insertAgent(architect)
        db.agentDao().insertAgent(programmer)
        db.agentDao().insertAgent(qa)

        val config = SwarmConfig(id = 601, name = "Role Swarm", description = "", coordinationMode = "AGENTIC_LOOP", agentIds = "501,502,503")
        val ollama = ScriptedPlanOllamaService(planResponse = "- [Architect] design the schema\n- [Programmer] implement the endpoint")
        val engine = buildEngine(db, ollama)

        val taskId = engine.executeTask(config, "build an endpoint")

        val steps = db.taskStepDao().getStepsForTaskSync(taskId)

        // Act steps end up persisted as OUTPUT (the THINKING row is replaced in place once the
        // decision is generated), one per checklist item, owned by the role-tagged agent.
        val outputSteps = steps.filter { it.actionType == "OUTPUT" }
        assertTrue("Expected an OUTPUT step owned by the Architect", outputSteps.any { it.agentName == architect.name })
        assertTrue("Expected an OUTPUT step owned by the Programmer", outputSteps.any { it.agentName == programmer.name })
        assertTrue("QA agent should not own an act step here -- no checklist item was tagged QA", outputSteps.none { it.agentName == qa.name })

        // Verify phase always routes to the QA-role agent regardless of the acting agent's role.
        val verifyingSteps = steps.filter { it.actionType == "VERIFYING" }
        assertEquals("Expected one verify step per checklist item", 2, verifyingSteps.size)
        assertTrue(
            "Expected every verify step to be owned by the QA agent, got: ${verifyingSteps.map { it.agentName }}",
            verifyingSteps.all { it.agentName == qa.name }
        )

        // Planning and final synthesis are owned by the Architect-role agent.
        val planStep = steps.lastOrNull { it.actionType == "PLAN" }
        assertEquals(architect.name, planStep?.agentName)
        val finalStep = steps.lastOrNull { it.actionType == "FINAL_RESPONSE" }
        assertEquals(architect.name, finalStep?.agentName)
    }

    @Test
    fun agenticLoop_untaggedChecklistLine_fallsBackToKeywordRoleInference() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val node = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        db.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))

        val programmer = Agent(id = 511, name = "Prog", role = "Programmer", modelName = "llama3", systemPrompt = "", colorHex = "#000000")
        val qa = Agent(id = 512, name = "QaBot", role = "QA Engineer", modelName = "llama3", systemPrompt = "", colorHex = "#000000")
        db.agentDao().insertAgent(programmer)
        db.agentDao().insertAgent(qa)

        val config = SwarmConfig(id = 602, name = "Inference Swarm", description = "", coordinationMode = "AGENTIC_LOOP", agentIds = "511,512")
        // No [Role] tag -- "write unit tests" should keyword-infer to QA via inferRoleFromText.
        val ollama = ScriptedPlanOllamaService(planResponse = "- write unit tests for the module")
        val engine = buildEngine(db, ollama)

        val taskId = engine.executeTask(config, "cover the module with tests")

        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        val outputSteps = steps.filter { it.actionType == "OUTPUT" }
        assertTrue(
            "Expected the untagged 'write unit tests' step to keyword-infer to the QA agent, got: ${outputSteps.map { it.agentName }}",
            outputSteps.any { it.agentName == qa.name }
        )
    }
}
