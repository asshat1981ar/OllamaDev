package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.ui.FakeAppDatabase
import com.example.ui.FakeMcpClient
import com.example.ui.FakeOllamaService
import com.example.ui.FakeSecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class AgenticActionExecutorHeadlessApprovalTest {
    @Before fun reset() { PendingApprovalStore.reset() }
    @After fun clear() { PendingApprovalStore.reset() }

    private fun headlessExecutor(db: AppDatabaseInterface): AgenticActionExecutor {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val git = GitService(File(context.cacheDir, "git-headless-${System.nanoTime()}"))
        val llm = LlmRouter(FakeOllamaService(), db.ollamaNodeDao(), db.claudeSkillDao(), FakeSecurePrefs(), Dispatchers.Unconfined)
        return AgenticActionExecutor(db, git, FakeMcpClient(), context, FakeSecurePrefs(), llm, Dispatchers.Unconfined, isHeadless = true)
    }

    @Test
    fun headless_gitPush_autoDeclinedAndSkipsApproval() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val taskId = db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = context.getSharedPreferences("ollama_swarm_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("git_remote_url", "https://github.com/example/repo.git").apply()
        val securePrefs = FakeSecurePrefs().apply { setGitToken("fake-token") }
        val git = GitService(File(context.cacheDir, "git-headless-push-${System.nanoTime()}"))
        val llm = LlmRouter(FakeOllamaService(), db.ollamaNodeDao(), db.claudeSkillDao(), FakeSecurePrefs(), Dispatchers.Unconfined)
        val executor = AgenticActionExecutor(db, git, FakeMcpClient(), context, securePrefs, llm, Dispatchers.Unconfined, isHeadless = true)

        val job = launch { executor.parseAndExecute(taskId, agentId = 1, "Pipeline Deployer", "git push") }
        advanceUntilIdle()
        job.join()

        assertNull(
            "Headless executor must not publish a pending UI approval request",
            PendingApprovalStore.pendingApproval.value
        )
        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        assertTrue(
            "Expected APPROVAL_SKIPPED_HEADLESS step; got: ${steps.map { it.actionType }}",
            steps.any { it.actionType == "APPROVAL_SKIPPED_HEADLESS" }
        )
        assertTrue(
            "Expected skip step to mention git push",
            steps.any { it.actionType == "APPROVAL_SKIPPED_HEADLESS" && it.content.contains("git push", ignoreCase = true) }
        )
        assertTrue(
            "Push should not actually run in headless mode",
            steps.none { it.actionType == "GIT_PUSH" }
        )
    }

    @Test
    fun headless_destructiveMcpCall_autoDeclinedAndSkipsApproval() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val taskId = db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()
        db.mcpServerDao().insertServer(
            McpServer(id = 200, name = "Risky MCP", type = "Deploy", sourceUrl = "http://localhost:9999/mcp", status = "Connected", toolsCount = 1, configuredParams = "{}")
        )
        db.mcpToolDao().insertTool(
            McpToolEntity(serverId = 200, name = "destroy_artifact", description = "Destroy an artifact", inputSchemaJson = "{}", outputSchemaJson = null, annotationsJson = "{\"destructiveHint\":true}")
        )
        db.claudeSkillDao().insertSkill(
            ClaudeSkill(id = 200, name = "Destroy Artifact", description = "Destroy an artifact", category = "Automation", isRecommended = true, isEnabled = true, usageExample = "", requiredMcpServerType = "Deploy", sourceToolName = "destroy_artifact")
        )

        val job = launch { headlessExecutor(db).parseAndExecute(taskId, agentId = 2, "AgentX", "MCP_CALL: Destroy Artifact | {}") }
        advanceUntilIdle()
        job.join()

        assertNull(
            "Headless executor must not publish a pending UI approval request",
            PendingApprovalStore.pendingApproval.value
        )
        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        assertTrue(
            "Expected MCP_CALL_GATED step before skip; got: ${steps.map { it.actionType }}",
            steps.any { it.actionType == "MCP_CALL_GATED" }
        )
        assertTrue(
            "Expected APPROVAL_SKIPPED_HEADLESS step; got: ${steps.map { it.actionType }}",
            steps.any { it.actionType == "APPROVAL_SKIPPED_HEADLESS" }
        )
        assertTrue(
            "Headless skip should be recorded instead of ACTION_DECLINED; got: ${steps.map { it.actionType }}",
            steps.none { it.actionType == "ACTION_DECLINED" }
        )
    }

    @Test
    fun headless_writeFileBatch_autoDeclinedAndSkipsApproval() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        db.ollamaNodeDao().getAllNodesSync().first().let {
            db.ollamaNodeDao().updateNode(it.copy(status = "Online", latencyMs = 10))
        }
        val taskId = db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()

        val output = """
            WRITE_FILE: app/src/main/java/com/example/HeadlessA.kt
            Some reasoning here.
        """.trimIndent()

        val job = launch { headlessExecutor(db).parseAndExecute(taskId, agentId = 3, "AgentZ", output) }
        advanceUntilIdle()
        job.join()

        assertNull(
            "Headless executor must not publish a pending UI file-change batch",
            PendingApprovalStore.pendingFileChangeBatch.value
        )
        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        assertTrue(
            "Expected APPROVAL_SKIPPED_HEADLESS step; got: ${steps.map { it.actionType }}",
            steps.any { it.actionType == "APPROVAL_SKIPPED_HEADLESS" }
        )
        assertTrue(
            "Expected FILE_CHANGE_REJECTED step; got: ${steps.map { it.actionType }}",
            steps.any { it.actionType == "FILE_CHANGE_REJECTED" }
        )
        assertNull(
            "File should not be written to workspace in headless mode",
            db.workspaceFileDao().getFileByPath("app/src/main/java/com/example/HeadlessA.kt")
        )
    }

    @Test
    fun interactive_gitPush_stillRequestsApproval() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val taskId = db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = context.getSharedPreferences("ollama_swarm_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("git_remote_url", "https://github.com/example/repo.git").apply()
        val securePrefs = FakeSecurePrefs().apply { setGitToken("fake-token") }
        val git = GitService(File(context.cacheDir, "git-interactive-push-${System.nanoTime()}"))
        val llm = LlmRouter(FakeOllamaService(), db.ollamaNodeDao(), db.claudeSkillDao(), FakeSecurePrefs(), Dispatchers.Unconfined)
        val executor = AgenticActionExecutor(db, git, FakeMcpClient(), context, securePrefs, llm, Dispatchers.Unconfined, isHeadless = false)

        val job = launch { executor.parseAndExecute(taskId, agentId = 4, "Pipeline Deployer", "git push") }
        val pending = PendingApprovalStore.pendingApproval.value
        assertNotNull("Interactive executor should still request UI approval", pending)
        assertTrue(pending?.riskCategory == ApprovalRiskCategory.GIT_PUSH)

        PendingApprovalStore.reject()
        job.join()

        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        assertTrue(
            "Interactive decline should still produce ACTION_DECLINED",
            steps.any { it.actionType == "ACTION_DECLINED" }
        )
        assertTrue(
            "Interactive path should not produce headless skip steps",
            steps.none { it.actionType == "APPROVAL_SKIPPED_HEADLESS" }
        )
    }
}
