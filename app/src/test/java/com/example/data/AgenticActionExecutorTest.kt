package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.ui.FakeAppDatabase
import com.example.ui.FakeMcpClient
import com.example.ui.FakeOllamaService
import com.example.ui.FakeSecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        val outcome = executor(db).parseAndExecute(taskId, agentId = 1, "AgentX", "MCP_CALL: No Such Skill | {}")
        assertTrue(outcome.mcpCallAttempted)
        assertTrue(!outcome.mcpCallSucceeded)
        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        assertTrue(steps.any { it.actionType == "MCP_CALL_FAILED" && it.content.contains("No Such Skill") })
    }

    @Test
    fun gitBranch_directive_emitsNotImplementedStep() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val taskId = db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()
        executor(db).parseAndExecute(taskId, agentId = 2, "AgentY", "git branch feature/x")
        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        assertTrue(steps.any { it.actionType == "GIT_BRANCH" })
    }

    @Test
    fun noDirectives_reportsNoAttempt() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val taskId = db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()
        val outcome = executor(db).parseAndExecute(taskId, agentId = 3, "AgentZ", "just prose, no directives")
        assertTrue(!outcome.mcpCallAttempted)
        assertEquals(0, db.taskStepDao().getStepsForTaskSync(taskId).size)
    }

    @Test
    fun riskyMcpCall_destructiveHintAnnotation_emitsGatedStep_andSurfacesReason() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val taskId = db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()
        db.mcpServerDao().insertServer(
            McpServer(id = 100, name = "Risky MCP", type = "Deploy", sourceUrl = "http://localhost:9999/mcp", status = "Connected", toolsCount = 1, configuredParams = "{}")
        )
        db.mcpToolDao().insertTool(
            McpToolEntity(serverId = 100, name = "destroy_artifact", description = "Destroy an artifact", inputSchemaJson = "{}", outputSchemaJson = null, annotationsJson = "{\"destructiveHint\":true}")
        )
        db.claudeSkillDao().insertSkill(
            ClaudeSkill(id = 100, name = "Destroy Artifact", description = "Destroy an artifact", category = "Automation", isRecommended = true, isEnabled = true, usageExample = "", requiredMcpServerType = "Deploy", sourceToolName = "destroy_artifact")
        )

        val agentId = 10
        AgentStateStore.initializeAgents(listOf(Agent(id = agentId, name = "AgentX", role = "Programmer", modelName = "llama3", systemPrompt = "", colorHex = "#000000")))
        val job = launch { executor(db).parseAndExecute(taskId, agentId = agentId, "AgentX", "MCP_CALL: Destroy Artifact | {}") }

        val pending = PendingApprovalStore.pendingApproval.value
        assertNotNull("Expected a pending MCP destructive-call approval request", pending)
        assertTrue(pending?.riskCategory == ApprovalRiskCategory.MCP_DESTRUCTIVE_CALL)
        assertTrue("Expected detail to mention destructiveHint; got: '${pending?.detail}'", pending?.detail?.contains("destructiveHint") == true)
        assertTrue(
            "Expected an MCP_CALL_GATED step before approval; steps: ${db.taskStepDao().getStepsForTaskSync(taskId).map { it.actionType }}",
            db.taskStepDao().getStepsForTaskSync(taskId).any { it.actionType == "MCP_CALL_GATED" && it.content.contains("AgentX") }
        )
        assertTrue(
            "Expected agent status to be Awaiting Approval while the dialog is pending; got: ${AgentStateStore.agentStates.value[agentId]?.status}",
            AgentStateStore.agentStates.value[agentId]?.status == "Awaiting Approval"
        )

        PendingApprovalStore.approve()
        job.join()
        assertTrue(
            "Expected agent to be idle after the approval decision",
            AgentStateStore.agentStates.value[agentId]?.isActive == false
        )
    }

    @Test
    fun riskyMcpCall_deleteKeyword_emitsGatedStep_andSurfacesKeywordReason() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val taskId = db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()
        db.mcpServerDao().insertServer(
            McpServer(id = 101, name = "Risky MCP", type = "Automation", sourceUrl = "http://localhost:9999/mcp", status = "Connected", toolsCount = 1, configuredParams = "{}")
        )
        db.mcpToolDao().insertTool(
            McpToolEntity(serverId = 101, name = "delete_file", description = "Delete a workspace file", inputSchemaJson = "{}", outputSchemaJson = null, annotationsJson = null)
        )
        db.claudeSkillDao().insertSkill(
            ClaudeSkill(id = 101, name = "Delete File", description = "Delete a workspace file", category = "Automation", isRecommended = true, isEnabled = true, usageExample = "", requiredMcpServerType = "Automation", sourceToolName = "delete_file")
        )

        val agentId = 11
        AgentStateStore.initializeAgents(listOf(Agent(id = agentId, name = "AgentY", role = "Programmer", modelName = "llama3", systemPrompt = "", colorHex = "#000000")))
        val job = launch { executor(db).parseAndExecute(taskId, agentId = agentId, "AgentY", "MCP_CALL: Delete File | {}") }

        val pending = PendingApprovalStore.pendingApproval.value
        assertNotNull("Expected a pending MCP destructive-call approval request", pending)
        assertTrue(pending?.riskCategory == ApprovalRiskCategory.MCP_DESTRUCTIVE_CALL)
        assertTrue("Expected detail to mention a keyword reason; got: '${pending?.detail}'", pending?.detail?.contains("keyword") == true)
        assertTrue(
            "Expected an MCP_CALL_GATED step before approval; steps: ${db.taskStepDao().getStepsForTaskSync(taskId).map { it.actionType }}",
            db.taskStepDao().getStepsForTaskSync(taskId).any { it.actionType == "MCP_CALL_GATED" && it.content.contains("delete") }
        )
        assertTrue(
            "Expected agent status to be Awaiting Approval while the dialog is pending; got: ${AgentStateStore.agentStates.value[agentId]?.status}",
            AgentStateStore.agentStates.value[agentId]?.status == "Awaiting Approval"
        )

        PendingApprovalStore.approve()
        job.join()
    }
}
