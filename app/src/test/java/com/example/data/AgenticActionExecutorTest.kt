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
import org.junit.Assert.assertEquals
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
        val outcome = executor(db).parseAndExecute(taskId, "AgentX", "MCP_CALL: No Such Skill | {}")
        assertTrue(outcome.mcpCallAttempted)
        assertTrue(!outcome.mcpCallSucceeded)
        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        assertTrue(steps.any { it.actionType == "MCP_CALL_FAILED" && it.content.contains("No Such Skill") })
    }

    @Test
    fun gitBranch_directive_emitsNotImplementedStep() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val taskId = db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()
        executor(db).parseAndExecute(taskId, "AgentY", "git branch feature/x")
        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        assertTrue(steps.any { it.actionType == "GIT_BRANCH" })
    }

    @Test
    fun noDirectives_reportsNoAttempt() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val taskId = db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()
        val outcome = executor(db).parseAndExecute(taskId, "AgentZ", "just prose, no directives")
        assertTrue(!outcome.mcpCallAttempted)
        assertEquals(0, db.taskStepDao().getStepsForTaskSync(taskId).size)
    }
}
