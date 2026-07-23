package com.example.data

import com.example.ui.FakeAppDatabase
import com.example.ui.FakeOllamaService
import com.example.ui.FakeSecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test double for the directive side-effect executor that records the outputs it was asked to
 * parse, and lets a test script a specific [ActionOutcome] for the parse step so callers can
 * verify the contract that [StepResult.mcpOutcome] propagates the executor's return value. */
private class FakeActionExecutor(private val scriptedOutcome: ActionOutcome? = null) : AgenticActionExecutorInterface {
    val parsed = mutableListOf<String>()
    val checkpointCalls = mutableListOf<Pair<Int, String>>()
    override suspend fun parseAndExecute(
        taskId: Int,
        agentName: String,
        output: String,
        mcpSuccessActionType: String,
        mcpFailureActionType: String
    ): ActionOutcome {
        parsed += output
        return scriptedOutcome ?: ActionOutcome(mcpCallAttempted = false, mcpCallSucceeded = false, mcpResultText = null)
    }
    override suspend fun autoCheckpoint(taskId: Int, agentName: String, todoText: String) {
        checkpointCalls += taskId to todoText
    }
}

/** Wraps [TaskStepDao.insertStep] to record every call, so tests can assert the in-place
 *  reinsert contract that [StepRunner.run] relies on (same row id throughout the streamed step,
 *  growing content, final replace with the configured [StepRequest.finalActionType]).
 *  File-private to StepRunnerTest -- the same class shape exists in SwarmEngineStreamingTest
 *  under the [SwarmEngineStreamingTest] test's own scope. */
private class StepRunnerRecordingTaskStepDao(private val delegate: TaskStepDao) : TaskStepDao by delegate {
    val inserts = mutableListOf<TaskStep>()
    override suspend fun insertStep(step: TaskStep): Long {
        val assignedId = delegate.insertStep(step)
        inserts += step.copy(id = assignedId.toInt())
        return assignedId
    }
}

private class StepRunnerRecordingAppDatabase(private val delegate: AppDatabaseInterface) : AppDatabaseInterface by delegate {
    val recordingTaskStepDao = StepRunnerRecordingTaskStepDao(delegate.taskStepDao())
    override fun taskStepDao(): TaskStepDao = recordingTaskStepDao
}

class StepRunnerTest {

    private fun runner(
        db: AppDatabaseInterface,
        executor: AgenticActionExecutorInterface = FakeActionExecutor()
    ): StepRunner = StepRunner(
        taskStepDao = db.taskStepDao(),
        llmRouter = LlmRouter(FakeOllamaService(), db.ollamaNodeDao(), db.claudeSkillDao(), FakeSecurePrefs(), Dispatchers.Unconfined),
        actionExecutor = executor
    )

    private suspend fun setUpOnlineSoloAgent(db: FakeAppDatabase) {
        db.ollamaNodeDao().getAllNodesSync().first().let {
            db.ollamaNodeDao().updateNode(it.copy(status = "Online", latencyMs = 10))
        }
    }

    private fun soloAgent(id: Int = 7) = Agent(
        id = id, name = "Solo", role = "Programmer", modelName = "llama3", systemPrompt = "s", colorHex = "#000"
    )

    private suspend fun newTaskId(db: FakeAppDatabase) =
        db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()

    // ---------------------------------------------------------------------
    // Happy path -- the single test the original Task 3 commit landed.
    // ---------------------------------------------------------------------

    @Test
    fun run_replacesPlaceholderWithFinalOutput_andParsesActions() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        setUpOnlineSoloAgent(db)
        val taskId = newTaskId(db)
        val executor = FakeActionExecutor()
        val runner = runner(db, executor)
        val statuses = mutableListOf<String>()
        val result = runner.run(
            taskId = taskId,
            updateStatus = { statuses += it },
            req = StepRequest(agent = soloAgent(), prompt = "do the thing", interStepDelayMs = 0, thinkingDelayMs = 0)
        )
        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        assertTrue("Expected at least one step row written for the task", steps.isNotEmpty())
        assertEquals("Final step row content must match the LLM output", result.output, steps.last().content)
        assertEquals("Final step row actionType is OUTPUT by default", "OUTPUT", steps.last().actionType)
        assertTrue("Action executor must have been called once with the model output", executor.parsed.single() == result.output)
        assertTrue("updateStatus lambda must have been invoked at least once", statuses.isNotEmpty())
    }

    // ---------------------------------------------------------------------
    // Throttled mid-stream reinsert contract -- the #1 thing the refactor
    // has to preserve for SwarmEngineStreamingTest to stay green.
    // ---------------------------------------------------------------------

    @Test
    fun run_writesThrottledMidStreamReinsertsToSameRowId() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        setUpOnlineSoloAgent(db)
        val taskId = newTaskId(db)
        val recordingDb = StepRunnerRecordingAppDatabase(db)
        val runner = runner(recordingDb)

        runner.run(
            taskId = taskId,
            updateStatus = {},
            req = StepRequest(agent = soloAgent(), prompt = "stream this please", interStepDelayMs = 0, thinkingDelayMs = 0)
        )

        val insertsForTask = recordingDb.recordingTaskStepDao.inserts.filter { it.taskId == taskId }
        val distinctIds = insertsForTask.map { it.id }.distinct()
        assertEquals(
            "Every write for this step's single row must reuse the same id (REPLACE-on-id in-place streaming)",
            1, distinctIds.size
        )
        assertTrue(
            "Expected more than one write (interim streamed growth + final replace); got ${insertsForTask.size}",
            insertsForTask.size > 1
        )
        // THINKING placeholder (~50 chars) is legitimately LONGER than the first streamed chunk
        // (FakeOllamaService streams in 12-char chunks), so monotonic growth only holds for the
        // streamed content that replaces it -- same shape as SwarmEngineStreamingTest's assertion.
        val streamedLengths = insertsForTask.drop(1).map { it.content.length }
        assertEquals(
            "Streamed content length must grow monotonically as tokens arrive",
            streamedLengths, streamedLengths.sorted()
        )
        val finalWrite = insertsForTask.last()
        assertEquals("Final reinsert uses the configured finalActionType", "OUTPUT", finalWrite.actionType)
        assertTrue(
            "Final reinsert content must contain the FakeOllama echo",
            finalWrite.content.contains("[FakeOllama]")
        )
    }

    // ---------------------------------------------------------------------
    // Per-step overrides -- verifies every field on StepRequest is honored.
    // ---------------------------------------------------------------------

    @Test
    fun run_honorsFinalActionTypeAndThinkingContentOverrides() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        setUpOnlineSoloAgent(db)
        val taskId = newTaskId(db)
        // Use the recording wrapper so we can observe the placeholder write BEFORE the
        // mid-stream reinserts and final-replace overwrite it (the same row id is reused for
        // every write in a step, so getStepsForTaskSync only shows the final content).
        val recordingDb = StepRunnerRecordingAppDatabase(db)
        val runner = runner(recordingDb)

        runner.run(
            taskId = taskId,
            updateStatus = {},
            req = StepRequest(
                agent = soloAgent(),
                prompt = "do the thing",
                thinkingActionType = "CRITIQUING",
                finalActionType = "FINAL_RESPONSE",
                thinkingContent = "Custom thinking placeholder text",
                interStepDelayMs = 0,
                thinkingDelayMs = 0
            )
        )

        val insertsForTask = recordingDb.recordingTaskStepDao.inserts.filter { it.taskId == taskId }
        val placeholder = insertsForTask.first()
        val finalInsert = insertsForTask.last()
        assertEquals(
            "Placeholder write must use the configured thinkingContent",
            "Custom thinking placeholder text", placeholder.content
        )
        assertEquals(
            "Placeholder write must use the configured thinkingActionType",
            "CRITIQUING", placeholder.actionType
        )
        assertEquals(
            "Final reinsert must use the configured finalActionType",
            "FINAL_RESPONSE", finalInsert.actionType
        )
        assertTrue(
            "Final reinsert content must contain the FakeOllama echo",
            finalInsert.content.contains("[FakeOllama]")
        )
        // Same row id throughout (the in-place reinsert contract).
        val distinctIds = insertsForTask.map { it.id }.distinct()
        assertEquals("Every write for this step must reuse the same row id", 1, distinctIds.size)
    }

    // ---------------------------------------------------------------------
    // recordMetrics=false -- the QA-verify step in the agentic loop uses this
    // so the QA agent's tasksExecuted only counts act steps.
    // ---------------------------------------------------------------------

    @Test
    fun run_recordMetricsFalse_doesNotBumpAgentState() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        setUpOnlineSoloAgent(db)
        val taskId = newTaskId(db)
        // AgentStateStore.recordExecutionMetrics is a no-op for unregistered agents (it updates
        // an existing entry, it doesn't create one). Pre-register so the test observes a real
        // delta. This mirrors what SwarmEngine.executeTask does via AgentStateStore.initializeAgents.
        val agent = soloAgent(id = 42)
        AgentStateStore.initializeAgents(listOf(agent))
        val before = AgentStateStore.agentStates.value[agent.id]!!
        val beforeCount = before.tasksExecuted
        val beforeTokens = before.totalTokensUsed

        val runner = runner(db)
        runner.run(
            taskId = taskId,
            updateStatus = {},
            req = StepRequest(
                agent = agent, prompt = "verify me",
                interStepDelayMs = 0, thinkingDelayMs = 0,
                recordMetrics = false
            )
        )

        val after = AgentStateStore.agentStates.value[agent.id]!!
        assertEquals(
            "recordMetrics=false must NOT increment tasksExecuted",
            beforeCount, after.tasksExecuted
        )
        assertEquals(
            "recordMetrics=false must NOT change totalTokensUsed",
            beforeTokens, after.totalTokensUsed
        )
    }

    @Test
    fun run_recordMetricsTrue_bumpsAgentState() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        setUpOnlineSoloAgent(db)
        val taskId = newTaskId(db)
        val agent = soloAgent(id = 43)
        AgentStateStore.initializeAgents(listOf(agent))
        val beforeCount = AgentStateStore.agentStates.value[agent.id]!!.tasksExecuted

        val runner = runner(db)
        runner.run(
            taskId = taskId,
            updateStatus = {},
            req = StepRequest(
                agent = agent, prompt = "act me",
                interStepDelayMs = 0, thinkingDelayMs = 0
                // recordMetrics defaults to true
            )
        )

        val after = AgentStateStore.agentStates.value[agent.id]!!
        assertEquals("tasksExecuted should increment by 1", beforeCount + 1, after.tasksExecuted)
        assertTrue("Per-agent totalTokensUsed should be > 0", after.totalTokensUsed > 0)
        assertFalse("Agent should be marked idle after the run completes", after.isActive)
    }

    // ---------------------------------------------------------------------
    // mcpOutcome propagation -- the agentic loop's verify-failure heuristic
    // depends on this so it can inspect the MCP result without re-parsing.
    // ---------------------------------------------------------------------

    @Test
    fun run_propagatesScriptedMcpOutcomeFromExecutor() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        setUpOnlineSoloAgent(db)
        val taskId = newTaskId(db)
        val scripted = ActionOutcome(
            mcpCallAttempted = true,
            mcpCallSucceeded = false,
            mcpResultText = "FAILED: 2 tests failed"
        )
        val executor = FakeActionExecutor(scriptedOutcome = scripted)
        val runner = runner(db, executor)

        val result = runner.run(
            taskId = taskId,
            updateStatus = {},
            req = StepRequest(agent = soloAgent(), prompt = "run tests", interStepDelayMs = 0, thinkingDelayMs = 0)
        )

        assertEquals(
            "StepResult.mcpOutcome must be the exact ActionOutcome the executor returned",
            scripted, result.mcpOutcome
        )
        assertTrue(result.mcpOutcome.mcpCallAttempted)
        assertFalse(result.mcpOutcome.mcpCallSucceeded)
        assertEquals("FAILED: 2 tests failed", result.mcpOutcome.mcpResultText)
    }

    // ---------------------------------------------------------------------
    // Final state -- verifies the post-step setAgentActive(false, "Idle")
    // fires, so the approval gate's coroutine-suspend path isn't blocked
    // by a stale isActive=true on the agent state.
    // ---------------------------------------------------------------------

    @Test
    fun run_marksAgentIdleAfterCompletion() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        setUpOnlineSoloAgent(db)
        val taskId = newTaskId(db)
        val agent = soloAgent(id = 44)
        // Pre-register so setAgentActive's update branch fires (otherwise the no-op path would
        // leave the entry absent regardless of the test's input).
        AgentStateStore.initializeAgents(listOf(agent))

        val runner = runner(db)
        runner.run(
            taskId = taskId,
            updateStatus = {},
            req = StepRequest(agent = agent, prompt = "x", interStepDelayMs = 0, thinkingDelayMs = 0)
        )

        val after = AgentStateStore.agentStates.value[agent.id]!!
        assertFalse("Agent should be marked idle after the run completes", after.isActive)
        assertEquals("Idle status label is set after the step completes", "Idle", after.status)
    }
}
