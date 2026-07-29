package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.ui.FakeAppDatabase
import com.example.ui.FakeMcpClient
import com.example.ui.FakeSecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

/** Distinguishes the plan prompt, the verify prompt (identified by its fixed "As QA, verify..."
 *  phrase), and every other (act/synthesis) prompt, so tests can script each phase separately. */
private class ScriptedVerifyOllamaService(
    private val planResponse: String,
    private val actResponse: String,
    private val verifyResponse: String
) : OllamaService {
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

    private fun respond(prompt: String): String = when {
        prompt.startsWith("Break the following request") -> planResponse
        prompt.contains("As QA, verify this was actually done correctly") -> verifyResponse
        else -> actResponse
    }

    override suspend fun pingAndFetchModels(nodeUrl: String, apiKey: String?): Pair<Boolean, List<String>> =
        true to listOf("llama3")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SwarmEngineVerifyLoopTest {

    private fun buildEngine(db: AppDatabaseInterface, ollamaService: OllamaService, mcpClient: McpClientInterface): SwarmEngine {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val gitService = GitService(File(context.cacheDir, "git-test-${System.nanoTime()}"))
        return SwarmEngine(
            db = db, gitService = gitService, mcpClient = mcpClient, appContext = context,
            securePrefs = FakeSecurePrefs(), ollamaService = ollamaService, dispatcher = Dispatchers.Unconfined
        )
    }

    private suspend fun setUpSoloAgentAndTestRunner(db: FakeAppDatabase) {
        val node = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        db.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))
        db.agentDao().insertAgent(
            Agent(id = 701, name = "Solo Agent", role = "Programmer", modelName = "llama3", systemPrompt = "Be helpful.", colorHex = "#000000")
        )
        db.mcpServerDao().insertServer(
            McpServer(id = 701, name = "Test Runner MCP", type = "TestRunner", sourceUrl = "http://localhost:9999/mcp", status = "Connected", toolsCount = 1, configuredParams = "{}")
        )
        db.claudeSkillDao().insertSkill(
            ClaudeSkill(id = 701, name = "Run Tests", description = "Runs the project test suite", category = "Automation", isRecommended = true, isEnabled = true, usageExample = "", requiredMcpServerType = "TestRunner", sourceToolName = null)
        )
    }

    @Test
    fun verifyPhase_repeatedFailure_retriesThenMarksUnresolvedAndContinues() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        setUpSoloAgentAndTestRunner(db)
        val config = SwarmConfig(id = 801, name = "Verify Fail Swarm", description = "", coordinationMode = "AGENTIC_LOOP", agentIds = "701")
        val fakeMcp = FakeMcpClient().apply { scriptedToolResult = "FAILED: 2 tests failed" }
        val ollama = ScriptedVerifyOllamaService(
            planResponse = "- implement the feature",
            actResponse = "Implemented the step.",
            verifyResponse = "MCP_CALL: Run Tests | {}"
        )
        val engine = buildEngine(db, ollama, fakeMcp)

        val taskId = engine.executeTask(config, "implement a feature")

        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        val planStep = steps.lastOrNull { it.actionType == "PLAN" }
        assertTrue("Expected the todo to be marked done with an UNRESOLVED marker after retries", planStep!!.content.contains("[x] implement the feature [UNRESOLVED after 2 attempts]"))

        // 1 initial attempt + 2 retries = 3 verify passes, each invoking the scripted-failing tool.
        val verifyingSteps = steps.filter { it.actionType == "VERIFYING" }
        assertEquals(3, verifyingSteps.size)

        // A todo that never verified cleanly should never be auto-checkpointed.
        assertTrue("Expected no checkpoint commit for an unresolved todo", steps.none { it.actionType == "CHECKPOINT_COMMIT" })
        assertTrue(db.gitCommitDao().getAllCommits().first().isEmpty())
    }

    @Test
    fun verifyPhase_passingResult_marksDoneAndAutoCheckpoints() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        setUpSoloAgentAndTestRunner(db)
        val config = SwarmConfig(id = 802, name = "Verify Pass Swarm", description = "", coordinationMode = "AGENTIC_LOOP", agentIds = "701")
        val fakeMcp = FakeMcpClient().apply { scriptedToolResult = "All tests passed" }
        val ollama = ScriptedVerifyOllamaService(
            planResponse = "- implement the feature",
            actResponse = "Implemented the step.",
            verifyResponse = "MCP_CALL: Run Tests | {}"
        )
        val engine = buildEngine(db, ollama, fakeMcp)

        val taskId = engine.executeTask(config, "implement a feature")

        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        val planStep = steps.lastOrNull { it.actionType == "PLAN" }
        assertTrue(planStep!!.content.contains("[x] implement the feature") && !planStep.content.contains("UNRESOLVED"))

        val verifyingSteps = steps.filter { it.actionType == "VERIFYING" }
        assertEquals("Expected exactly one verify pass for a step that verifies cleanly", 1, verifyingSteps.size)

        val checkpointStep = steps.lastOrNull { it.actionType == "CHECKPOINT_COMMIT" }
        assertTrue("Expected an auto-checkpoint commit step", checkpointStep != null)

        val commits = db.gitCommitDao().getAllCommits().first()
        assertTrue("Expected a recorded GitCommit for the checkpoint", commits.isNotEmpty())
        assertEquals(taskId, commits.first().taskId)
    }
}
