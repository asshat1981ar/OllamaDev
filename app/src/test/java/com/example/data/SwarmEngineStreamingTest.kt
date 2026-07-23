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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/** Wraps [TaskStepDao.insertStep] to record every call, so tests can assert an in-progress
 *  streamed step is updated in place (same row id) rather than one row per token chunk. */
private class RecordingTaskStepDao(private val delegate: TaskStepDao) : TaskStepDao by delegate {
    val insertedSteps = mutableListOf<TaskStep>()
    override suspend fun insertStep(step: TaskStep): Long {
        // Record the *assigned* id (delegate.insertStep auto-generates one when step.id == 0),
        // not the input step's id field, or the very first insert for a row would look like a
        // different id than every subsequent replace of that same row.
        val assignedId = delegate.insertStep(step)
        insertedSteps.add(step.copy(id = assignedId.toInt()))
        return assignedId
    }
}

private class RecordingAppDatabase(private val delegate: AppDatabaseInterface) : AppDatabaseInterface by delegate {
    val recordingTaskStepDao = RecordingTaskStepDao(delegate.taskStepDao())
    override fun taskStepDao(): TaskStepDao = recordingTaskStepDao
}

/**
 * Covers the streaming plumbing added in Phase 2: SwarmEngine must grow a TaskStep's content in
 * place as tokens arrive (via the same REPLACE-on-id insert), not create a new row per chunk, and
 * the final content must still match the fully concatenated streamed text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SwarmEngineStreamingTest {

    private fun buildEngine(db: AppDatabaseInterface): SwarmEngine {
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
    fun executeTask_streamsSameStepIdInPlace_untilFinalReplace() = runTest(UnconfinedTestDispatcher()) {
        val fakeDb = FakeAppDatabase()
        val node = fakeDb.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        fakeDb.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))

        val agent = Agent(
            id = 111, name = "Streamer", role = "Programmer",
            modelName = "llama3", systemPrompt = "Be helpful.", colorHex = "#000000"
        )
        fakeDb.agentDao().insertAgent(agent)
        val config = SwarmConfig(
            id = 211, name = "Solo Streaming Swarm", description = "", coordinationMode = "SEQUENTIAL", agentIds = "111"
        )

        val recordingDb = RecordingAppDatabase(fakeDb)
        val engine = buildEngine(recordingDb)

        val taskId = engine.executeTask(config, "stream this please, it should grow token by token")

        val insertsForTask = recordingDb.recordingTaskStepDao.insertedSteps.filter { it.taskId == taskId }
        val distinctIds = insertsForTask.map { it.id }.distinct()
        assertEquals("Expected every write for this task's single step to reuse the same row id", 1, distinctIds.size)

        assertTrue(
            "Expected more than one write (interim streamed growth + final replace), got ${insertsForTask.size}",
            insertsForTask.size > 1
        )

        val lengths = insertsForTask.map { it.content.length }
        assertEquals("Content length should grow monotonically as tokens stream in", lengths, lengths.sorted())

        val finalContent = insertsForTask.last().content
        assertTrue(finalContent.contains("[FakeOllama] llama3 says:"))
    }
}
