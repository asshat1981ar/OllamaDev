package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.ui.FakeAppDatabase
import com.example.ui.FakeMcpClient
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

/** Distinguishes the plan prompt, the dedicated file-content round-trip prompt, the verify
 *  prompt, and every other (act/synthesis) prompt. */
private class ScriptedFileWriteOllamaService(
    private val planResponse: String,
    private val actResponse: String,
    private val verifyResponse: String,
    private val fileContentResponse: String
) : OllamaService {
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
        prompt.startsWith("Write the complete contents of the file at") -> fileContentResponse
        prompt.contains("As QA, verify this was actually done correctly") -> verifyResponse
        else -> actResponse
    }

    override suspend fun pingAndFetchModels(nodeUrl: String, apiKey: String?): Pair<Boolean, List<String>> =
        true to listOf("llama3")
}

/**
 * End-to-end coverage of the WRITE_FILE directive inside an AGENTIC_LOOP swarm.
 * The act step emits `WRITE_FILE: <path>`, which triggers a focused LLM round-trip for content,
 * then routes the change through [PendingApprovalStore]'s batched file-change review gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SwarmEngineFileWriteTest {

    @Before
    fun resetApprovalStore() {
        PendingApprovalStore.reset()
    }

    @After
    fun clearApprovalStore() {
        PendingApprovalStore.reset()
    }

    private fun buildEngine(db: AppDatabaseInterface, ollamaService: OllamaService, context: android.app.Application): SwarmEngine {
        val gitService = GitService(File(context.cacheDir, "git-test-${System.nanoTime()}"))
        return SwarmEngine(
            db = db, gitService = gitService, mcpClient = FakeMcpClient(), appContext = context,
            securePrefs = FakeSecurePrefs(), ollamaService = ollamaService, dispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun writeFileDirective_approved_createsNewFileWithGeneratedContent() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val node = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        db.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))
        db.agentDao().insertAgent(Agent(id = 950, name = "Writer", role = "Programmer", modelName = "llama3", systemPrompt = "", colorHex = "#000000"))
        val config = SwarmConfig(id = 950, name = "Write Swarm", description = "", coordinationMode = "AGENTIC_LOOP", agentIds = "950")

        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val ollama = ScriptedFileWriteOllamaService(
            planResponse = "- write the new module",
            actResponse = "WRITE_FILE: new_module.py",
            verifyResponse = "No further tooling available to verify.",
            fileContentResponse = "print('hello from new module')"
        )
        val engine = buildEngine(db, ollama, context)

        var taskId: Int? = null
        val job = launch { taskId = engine.executeTask(config, "add a new module") }

        val batch = PendingApprovalStore.pendingFileChangeBatch.value
        assertNotNull("Expected a batched file-change review, got: $batch", batch)
        val change = batch!!.changes.first { it.filePath == "new_module.py" }
        assertTrue("A brand-new file should be flagged isNewFile", change.isNewFile)
        assertEquals("print('hello from new module')", change.proposedContent)
        assertEquals("", change.originalContent)

        PendingApprovalStore.setBatchFileDecision("new_module.py", true)
        PendingApprovalStore.confirmFileChangeBatch()
        job.join()

        assertTrue(PendingApprovalStore.pendingFileChangeBatch.value == null)
        val file = db.workspaceFileDao().getFileByPath("new_module.py")
        assertTrue("Expected the new file to be created", file != null)
        assertEquals("print('hello from new module')", file!!.content)

        val steps = db.taskStepDao().getStepsForTaskSync(taskId!!)
        assertTrue(steps.any { it.actionType == "FILE_CHANGE_APPLIED" })
        assertTrue(steps.none { it.actionType == "FILE_CHANGE_REJECTED" })
    }

    @Test
    fun writeFileDirective_rejected_leavesExistingFileUnchanged() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val node = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        db.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))
        db.agentDao().insertAgent(Agent(id = 951, name = "Writer", role = "Programmer", modelName = "llama3", systemPrompt = "", colorHex = "#000000"))
        db.workspaceFileDao().insertFile(WorkspaceFile(filePath = "existing_file.py", content = "original content"))
        val config = SwarmConfig(id = 951, name = "Write Swarm Reject", description = "", coordinationMode = "AGENTIC_LOOP", agentIds = "951")

        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val ollama = ScriptedFileWriteOllamaService(
            planResponse = "- update the existing module",
            actResponse = "WRITE_FILE: existing_file.py",
            verifyResponse = "No further tooling available to verify.",
            fileContentResponse = "print('modified content')"
        )
        val engine = buildEngine(db, ollama, context)

        var taskId: Int? = null
        val job = launch { taskId = engine.executeTask(config, "update the module") }

        val batch = PendingApprovalStore.pendingFileChangeBatch.value
        assertNotNull(batch)
        val change = batch!!.changes.first { it.filePath == "existing_file.py" }
        assertTrue("Modifying an existing file should not be flagged isNewFile", !change.isNewFile)
        assertEquals("original content", change.originalContent)

        PendingApprovalStore.setBatchFileDecision("existing_file.py", false)
        PendingApprovalStore.confirmFileChangeBatch()
        job.join()

        val file = db.workspaceFileDao().getFileByPath("existing_file.py")
        assertEquals("original content", file?.content)

        val steps = db.taskStepDao().getStepsForTaskSync(taskId!!)
        assertTrue(steps.any { it.actionType == "FILE_CHANGE_REJECTED" })
        assertTrue(steps.none { it.actionType == "FILE_CHANGE_APPLIED" })
    }
}
