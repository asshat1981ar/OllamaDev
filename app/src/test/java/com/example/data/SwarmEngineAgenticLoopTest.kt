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

/** Ollama fake whose plan response never gets marked done by [neverDoneMarker], so the agentic
 *  loop's iteration guardrail is what has to stop the loop rather than the checklist emptying. */
private class ScriptedOllamaService(private val planResponse: String, private val stepResponse: String) : OllamaService {
    var stepCallCount = 0

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

    private fun respond(prompt: String): String {
        return if (prompt.startsWith("Break the following request")) {
            planResponse
        } else {
            stepCallCount++
            stepResponse
        }
    }

    override suspend fun pingAndFetchModels(nodeUrl: String, apiKey: String?): Pair<Boolean, List<String>> =
        true to listOf("llama3")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SwarmEngineAgenticLoopTest {

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
    fun agenticLoop_plansThenCompletesChecklist_writesFinalResponse() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        setUpSoloAgent(db)
        val config = SwarmConfig(id = 401, name = "Loop Swarm", description = "", coordinationMode = "AGENTIC_LOOP", agentIds = "301")
        val ollama = ScriptedOllamaService(
            planResponse = "- write the function\n- add a test",
            stepResponse = "Reasoned through the step and completed it."
        )
        val engine = buildEngine(db, ollama)

        val taskId = engine.executeTask(config, "build a fibonacci function")

        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        val planStep = steps.lastOrNull { it.actionType == "PLAN" }
        assertTrue("Expected a PLAN step", planStep != null)
        assertTrue("Expected both checklist items marked done", planStep!!.content.contains("[x] write the function") && planStep.content.contains("[x] add a test"))

        val finalStep = steps.lastOrNull { it.actionType == "FINAL_RESPONSE" }
        assertTrue("Expected a FINAL_RESPONSE step", finalStep != null)
        // 2 act-step calls + 2 verify-step calls (one of each per checklist item, since a solo
        // agent stands in for both actor and QA-fallback) + 1 final synthesis call, all routed
        // through the "else" branch of ScriptedOllamaService.respond since none of them start
        // with the plan-prompt's fixed prefix.
        assertEquals(5, ollama.stepCallCount)
    }

    @Test
    fun agenticLoop_neverCompletingChecklist_stopsAtMaxIterationsGuardrail() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        setUpSoloAgent(db)
        val config = SwarmConfig(id = 402, name = "Loop Swarm Stuck", description = "", coordinationMode = "AGENTIC_LOOP", agentIds = "301")
        // Only one checklist item, and it's never actually reasoned about as "done" by the
        // engine's own logic -- but since the engine always marks the current item done after
        // processing it (when verification doesn't look like a failure), a single-item checklist
        // would finish in one iteration regardless. To exercise the guardrail we instead give a
        // checklist the parser can't split into more than the max, forcing many iterations: use a
        // huge checklist so the 16-iteration cap (raised from 8 now that each iteration includes
        // both an act step and a verify step) triggers before all items are done.
        val hugeChecklist = (1..30).joinToString("\n") { "- step $it" }
        val ollama = ScriptedOllamaService(planResponse = hugeChecklist, stepResponse = "ok")
        val engine = buildEngine(db, ollama)

        val taskId = engine.executeTask(config, "a task with way more steps than the guardrail allows")

        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        val planStep = steps.lastOrNull { it.actionType == "PLAN" }
        assertTrue(planStep != null)
        val doneCount = Regex("""\[x\]""").findAll(planStep!!.content).count()
        assertEquals("Loop must stop at the 16-iteration guardrail, not process all 30 items", 16, doneCount)

        // Loop still reaches a final synthesis step even when the checklist wasn't fully cleared.
        val finalStep = steps.lastOrNull { it.actionType == "FINAL_RESPONSE" }
        assertTrue(finalStep != null)
    }
}
