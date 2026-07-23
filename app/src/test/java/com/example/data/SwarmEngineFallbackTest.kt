package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.ui.FakeAppDatabase
import com.example.ui.FakeMcpClient
import com.example.ui.FakeOllamaService
import com.example.ui.FakeSecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/**
 * Covers the Gemini-removal replacement policy: SwarmEngine must resolve every generation call
 * against the Ollama node pool (falling back to any online node when an agent's exact model
 * doesn't match one), and must never surface a Gemini-shaped response now that GeminiService is
 * gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SwarmEngineFallbackTest {

    private fun buildEngine(db: FakeAppDatabase): SwarmEngine {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val gitService = GitService(File(context.cacheDir, "git-test-${System.nanoTime()}"))
        return SwarmEngine(
            db = db,
            gitService = gitService,
            mcpClient = FakeMcpClient(),
            appContext = context,
            securePrefs = FakeSecurePrefs(),
            ollamaService = FakeOllamaService(),
            dispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun generateFreeform_noOnlineNodes_returnsClearErrorInsteadOfGemini() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase() // all seeded nodes default to Offline
        val engine = buildEngine(db)

        val result = engine.generateFreeform("hello", "system prompt")

        assertTrue(result.contains("no online Ollama node available"))
    }

    @Test
    fun generateFreeform_onlineNodeAvailable_usesFallbackPool() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val node = db.ollamaNodeDao().getAllNodesSync().first()
        db.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))
        val engine = buildEngine(db)

        val result = engine.generateFreeform("hello", "system prompt")

        assertTrue(result.contains("[FakeOllama]"))
    }

    @Test
    fun executeTask_agentModelMismatch_fallsBackToOnlineNodesOwnModel() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        // Node advertises llama3/mistral/phi3 but is Online; the agent's configured model
        // matches none of them, so the engine should fall back to the node's own first model
        // (llama3) instead of returning the old Gemini fallback or an error.
        val node = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        db.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))

        val agent = Agent(
            id = 101, name = "Mismatched Agent", role = "Programmer",
            modelName = "some-unrelated-model", systemPrompt = "Be helpful.", colorHex = "#000000"
        )
        db.agentDao().insertAgent(agent)

        val config = SwarmConfig(
            id = 201, name = "Solo Test Swarm", description = "", coordinationMode = "SEQUENTIAL", agentIds = "101"
        )

        val engine = buildEngine(db)
        var createdTaskId: Int? = null
        val taskId = engine.executeTask(config, "do the thing", onTaskCreated = { createdTaskId = it })

        assertNotNull(createdTaskId)
        assertEquals(createdTaskId, taskId)

        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        val finalStep = steps.lastOrNull { it.actionType == "FINAL_RESPONSE" }
        assertNotNull("Expected a FINAL_RESPONSE step", finalStep)
        assertTrue(
            "Expected the fallback node's own model (llama3) in the response, got: ${finalStep!!.content}",
            finalStep.content.contains("[FakeOllama] llama3 says:")
        )
    }
}
