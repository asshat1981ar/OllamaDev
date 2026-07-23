package com.example.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.data.OllamaService
import com.example.data.SwarmConfig
import com.example.viewmodel.SwarmViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Records every prompt string SwarmEngine sends through Ollama, so tests can assert what
 *  context a dispatch actually carried rather than just its final output. */
private class RecordingOllamaService : OllamaService {
    val receivedPrompts = mutableListOf<String>()

    override suspend fun generate(nodeUrl: String, modelName: String, prompt: String, systemPrompt: String?, apiKey: String?): String? {
        receivedPrompts += prompt
        return "[Recording] response to: ${prompt.take(60)}"
    }

    override suspend fun generateStreaming(
        nodeUrl: String, modelName: String, prompt: String, systemPrompt: String?, apiKey: String?,
        onToken: suspend (String) -> Unit
    ): String? {
        receivedPrompts += prompt
        val full = "[Recording] response to: ${prompt.take(60)}"
        onToken(full)
        return full
    }

    override suspend fun pingAndFetchModels(nodeUrl: String, apiKey: String?): Pair<Boolean, List<String>> =
        true to listOf("llama3")
}

/**
 * Covers Phase 3's session-memory requirement: consecutive Session prompts should thread prior
 * turns as context (via the persisted ChatMessage log), not start each dispatch from a blank
 * slate like an isolated one-shot task.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SwarmViewModelSessionMemoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun runSwarmFromSession_secondPrompt_includesFirstExchangeAsContext() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val ollama = RecordingOllamaService()
        val db = FakeAppDatabase()
        val node = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        db.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))

        val viewModel = SwarmViewModel(
            application = context,
            ollamaService = ollama,
            mcpClient = FakeMcpClient(),
            registryClient = FakeMcpRegistryClient(),
            dispatcher = testDispatcher,
            database = db,
            securePrefs = FakeSecurePrefs()
        )

        val config = SwarmConfig(
            id = 1, name = "SDLC Spec & Design Swarm", description = "",
            coordinationMode = "SEQUENTIAL", agentIds = "1,4,3"
        )
        viewModel.selectSessionSwarmConfig(config)

        viewModel.runSwarmFromSession("what's 2+2")
        advanceUntilIdle()
        viewModel.runSwarmFromSession("now multiply that by 10")
        advanceUntilIdle()

        val secondDispatchPrompts = ollama.receivedPrompts.filter { it.contains("now multiply that by 10") }
        assertTrue(
            "Expected the second dispatch's prompt to include prior context, got: $secondDispatchPrompts",
            secondDispatchPrompts.any { it.contains("what's 2+2") }
        )
    }
}
