package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.ui.FakeAppDatabase
import com.example.ui.FakeMcpClient
import com.example.ui.FakeSecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/** Captures every verify-phase prompt (identified by the fixed "As QA, verify..." phrase the
 *  AGENTIC_LOOP verify step ends with) and scripts a distinct act response per act call, so the
 *  test can prove each verify prompt carries THAT iteration's act output rather than an
 *  unresolved/stale variable (the RELEASE_NOTES PRs #7-10 "decision" flag). */
private class VerifyPromptCapturingOllamaService(
    private val planResponse: String,
    private val actResponses: List<String>,
    private val verifyResponse: String
) : OllamaService {
    val verifyPrompts = mutableListOf<String>()
    private var actCallCount = 0

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
        prompt.contains("As QA, verify this was actually done correctly") -> {
            verifyPrompts += prompt
            verifyResponse
        }
        prompt.startsWith("Summarize the outcome") -> "Synthesis complete."
        else -> actResponses[(actCallCount++).coerceAtMost(actResponses.lastIndex)]
    }

    override suspend fun pingAndFetchModels(nodeUrl: String, apiKey: String?): Pair<Boolean, List<String>> =
        true to listOf("llama3")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SwarmEngineVerifyPromptTest {

    private fun buildEngine(db: AppDatabaseInterface, ollamaService: OllamaService): SwarmEngine {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val gitService = GitService(File(context.cacheDir, "git-test-${System.nanoTime()}"))
        return SwarmEngine(
            db = db, gitService = gitService, mcpClient = FakeMcpClient(), appContext = context,
            securePrefs = FakeSecurePrefs(), ollamaService = ollamaService, dispatcher = Dispatchers.Unconfined
        )
    }

    private suspend fun setUpSoloAgent(db: FakeAppDatabase) {
        val node = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        db.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))
        db.agentDao().insertAgent(
            Agent(id = 301, name = "Loop Agent", role = "Programmer", modelName = "llama3", systemPrompt = "Be helpful.", colorHex = "#000000")
        )
    }

    @Test
    fun agenticLoop_verifyPromptContainsThatIterationsActOutput() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        setUpSoloAgent(db)
        val config = SwarmConfig(id = 901, name = "Verify Prompt Swarm", description = "", coordinationMode = "AGENTIC_LOOP", agentIds = "301")
        val actOne = "ACT_ONE_SENTINEL: frobnicate module implemented with passing unit tests."
        val actTwo = "ACT_TWO_SENTINEL: frobnicate CLI wired up to the module."
        val ollama = VerifyPromptCapturingOllamaService(
            planResponse = "- [Programmer] implement step one\n- [Programmer] implement step two",
            actResponses = listOf(actOne, actTwo),
            verifyResponse = "Verified by inspection: the step was completed correctly."
        )
        val engine = buildEngine(db, ollama)

        engine.executeTask(config, "build a frobnicate tool")

        // One verify pass per checklist item (verification reads as passing, so no retries).
        assertEquals("Expected one verify prompt per checklist item", 2, ollama.verifyPrompts.size)

        // THE regression assertion for the RELEASE_NOTES flag ("verify prompt interpolates an
        // unresolved `decision` variable before it is declared"): each verify prompt must carry
        // the act step's output -- i.e. actResult.output, interpolated after it exists.
        assertTrue("Verify prompt for step one must contain that act step's output", ollama.verifyPrompts[0].contains(actOne))
        assertTrue("Verify prompt for step two must contain that act step's output", ollama.verifyPrompts[1].contains(actTwo))

        // The flagged failure mode was a wrong/stale variable: item two's verify prompt must not
        // still be carrying item one's act output (e.g. a previous iteration's leftover value).
        assertFalse("Verify prompt for step two must not carry a stale earlier act output", ollama.verifyPrompts[1].contains(actOne))

        // Structural adjacency per SwarmEngine's verifyPrompt template: the output lands in the
        // "Agent output:" slot, right after the attempted-step line.
        assertTrue(ollama.verifyPrompts[0].contains("implement step one\n\nAgent output:\n$actOne"))
        assertTrue(ollama.verifyPrompts[1].contains("implement step two\n\nAgent output:\n$actTwo"))
    }
}
