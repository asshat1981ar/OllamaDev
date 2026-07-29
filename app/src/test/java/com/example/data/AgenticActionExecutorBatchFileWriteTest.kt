package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.ui.FakeAppDatabase
import com.example.ui.FakeMcpClient
import com.example.ui.FakeOllamaService
import com.example.ui.FakeSecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
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
class AgenticActionExecutorBatchFileWriteTest {
    @Before fun reset() { PendingApprovalStore.reset() }
    @After fun clear() { PendingApprovalStore.reset() }

    private fun executor(db: AppDatabaseInterface): AgenticActionExecutor {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val git = GitService(File(context.cacheDir, "git-batch-${System.nanoTime()}"))
        val llm = LlmRouter(FakeOllamaService(), db.ollamaNodeDao(), db.claudeSkillDao(), FakeSecurePrefs(), Dispatchers.Unconfined)
        return AgenticActionExecutor(db, git, FakeMcpClient(), context, FakeSecurePrefs(), llm, Dispatchers.Unconfined)
    }

    @Test
    fun multipleWriteFileDirectives_collectIntoSingleBatchDialog() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        db.ollamaNodeDao().getAllNodesSync().first().let {
            db.ollamaNodeDao().updateNode(it.copy(status = "Online", latencyMs = 10))
        }
        val taskId = db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()

        val output = """
            WRITE_FILE: app/src/main/java/com/example/A.kt
            Some reasoning here.
            WRITE_FILE: app/src/main/java/com/example/B.kt
            More reasoning.
        """.trimIndent()

        val job = launch { executor(db).parseAndExecute(taskId, agentId = 1, "AgentZ", output) }

        val batch = PendingApprovalStore.pendingFileChangeBatch.value
        assertNotNull("Expected a batched file-change review dialog", batch)
        assertEquals("Expected 2 changes in the batch", 2, batch?.changes?.size)
        assertTrue(
            "Expected both file paths in the batch",
            batch?.changes?.map { it.filePath }?.containsAll(listOf("app/src/main/java/com/example/A.kt", "app/src/main/java/com/example/B.kt")) == true
        )

        // Accept both and confirm the batch.
        batch?.changes?.forEach { PendingApprovalStore.setBatchFileDecision(it.filePath, true) }
        PendingApprovalStore.confirmFileChangeBatch()
        job.join()

        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        assertTrue(
            "Expected two FILE_CHANGE_APPLIED steps; got: ${steps.map { it.actionType }}",
            steps.count { it.actionType == "FILE_CHANGE_APPLIED" } == 2
        )
        assertTrue(
            "Expected both files to be written to workspace",
            listOf("app/src/main/java/com/example/A.kt", "app/src/main/java/com/example/B.kt").all { path ->
                db.workspaceFileDao().getFileByPath(path) != null
            }
        )
    }

    @Test
    fun batchReview_rejectOneApplyOne() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        db.ollamaNodeDao().getAllNodesSync().first().let {
            db.ollamaNodeDao().updateNode(it.copy(status = "Online", latencyMs = 10))
        }
        val taskId = db.swarmTaskDao().insertTask(SwarmTask(prompt = "p", status = "Thinking", swarmName = "s")).toInt()

        val output = """
            WRITE_FILE: app/src/main/java/com/example/A.kt
            WRITE_FILE: app/src/main/java/com/example/B.kt
        """.trimIndent()

        val job = launch { executor(db).parseAndExecute(taskId, agentId = 2, "AgentZ", output) }
        val batch = PendingApprovalStore.pendingFileChangeBatch.value
        assertNotNull(batch)

        // Reject A, accept B.
        batch?.changes?.first { it.filePath == "app/src/main/java/com/example/A.kt" }?.let {
            PendingApprovalStore.setBatchFileDecision(it.filePath, false)
        }
        batch?.changes?.first { it.filePath == "app/src/main/java/com/example/B.kt" }?.let {
            PendingApprovalStore.setBatchFileDecision(it.filePath, true)
        }
        PendingApprovalStore.confirmFileChangeBatch()
        job.join()

        assertTrue(
            "A should be rejected",
            db.taskStepDao().getStepsForTaskSync(taskId).any { it.actionType == "FILE_CHANGE_REJECTED" && it.content.contains("A.kt") }
        )
        assertTrue(
            "B should be applied",
            db.taskStepDao().getStepsForTaskSync(taskId).any { it.actionType == "FILE_CHANGE_APPLIED" && it.content.contains("B.kt") }
        )
        assertTrue(
            "Only B should exist in workspace",
            db.workspaceFileDao().getFileByPath("app/src/main/java/com/example/B.kt") != null &&
                db.workspaceFileDao().getFileByPath("app/src/main/java/com/example/A.kt") == null
        )
    }
}
