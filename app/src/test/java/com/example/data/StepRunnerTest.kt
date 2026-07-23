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
    override suspend fun parseAndExecute(
        taskId: Int,
        agentName: String,
        output: String,
        mcpSuccessActionType: String,
        mcpFailureActionType: String
    ): ActionOutcome {
        parsed += output
        return ActionOutcome(mcpCallAttempted = false, mcpCallSucceeded = false, mcpResultText = null)
    }
    override suspend fun autoCheckpoint(taskId: Int, agentName: String, todoText: String) {}
}

class StepRunnerTest {
    @Test
    fun run_replacesPlaceholderWithFinalOutput_andParsesActions() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        db.ollamaNodeDao().getAllNodesSync().first().let {
            db.ollamaNodeDao().updateNode(it.copy(status = "Online", latencyMs = 10))
        }
        val agent = Agent(id = 7, name = "Solo", role = "Programmer", modelName = "llama3", systemPrompt = "s", colorHex = "#000")
        val taskId = db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()
        val executor = FakeActionExecutor()
        val runner = StepRunner(
            taskStepDao = db.taskStepDao(),
            llmRouter = LlmRouter(FakeOllamaService(), db.ollamaNodeDao(), db.claudeSkillDao(), FakeSecurePrefs(), Dispatchers.Unconfined),
            actionExecutor = executor
        )
        val statuses = mutableListOf<String>()
        val result = runner.run(
            taskId = taskId,
            updateStatus = { statuses += it },
            req = StepRequest(agent = agent, prompt = "do the thing", interStepDelayMs = 0, thinkingDelayMs = 0)
        )
        // one logical step row, final content is the model output
        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        assertTrue("Expected at least one step row written for the task", steps.isNotEmpty())
        assertEquals("Final step row content must match the LLM output", result.output, steps.last().content)
        assertEquals("Final step row actionType is OUTPUT by default", "OUTPUT", steps.last().actionType)
        assertTrue("Action executor must have been called once with the model output", executor.parsed.single() == result.output)
        assertTrue("updateStatus lambda must have been invoked at least once", statuses.isNotEmpty())
    }
}
