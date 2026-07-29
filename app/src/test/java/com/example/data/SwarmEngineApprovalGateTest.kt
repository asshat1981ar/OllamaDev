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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/** Distinguishes the plan prompt, the verify prompt, and every other (act/synthesis) prompt, so
 *  a single scripted directive can be placed in the act step without also firing during verify. */
private class ScriptedDirectiveOllamaService(
    private val planResponse: String,
    private val actResponse: String,
    private val verifyResponse: String
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
        prompt.contains("As QA, verify this was actually done correctly") -> verifyResponse
        else -> actResponse
    }

    override suspend fun pingAndFetchModels(nodeUrl: String, apiKey: String?): Pair<Boolean, List<String>> =
        true to listOf("llama3")
}

/**
 * Covers the agentic-loop human-approval gate: risky actions (git push, destructive-flagged MCP
 * calls) must suspend on [PendingApprovalStore] and wait for an explicit approve/reject before
 * proceeding, using the AGENTIC_LOOP coordination mode specifically because it has no delay()
 * calls in its workflow (unlike SEQUENTIAL/PEER_TO_PEER), so the launched task coroutine reaches
 * the approval gate immediately under an unconfined dispatcher instead of stalling on a real delay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SwarmEngineApprovalGateTest {

    @Before
    fun resetApprovalStore() {
        PendingApprovalStore.reset()
    }

    @After
    fun clearApprovalStore() {
        PendingApprovalStore.reset()
    }

    private fun buildEngine(
        db: AppDatabaseInterface,
        ollamaService: OllamaService,
        mcpClient: McpClientInterface,
        securePrefs: SecurePrefsInterface,
        context: android.app.Application
    ): SwarmEngine {
        val gitService = GitService(File(context.cacheDir, "git-test-${System.nanoTime()}"))
        return SwarmEngine(
            db = db, gitService = gitService, mcpClient = mcpClient, appContext = context,
            securePrefs = securePrefs, ollamaService = ollamaService, dispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun approvalGate_gitPush_approved_proceedsPastGate() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val node = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        db.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))
        db.agentDao().insertAgent(Agent(id = 901, name = "Pusher", role = "Programmer", modelName = "llama3", systemPrompt = "", colorHex = "#000000"))
        val config = SwarmConfig(id = 901, name = "Push Swarm", description = "", coordinationMode = "AGENTIC_LOOP", agentIds = "901")

        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        // Port 1 on loopback refuses connections immediately -- fast, deterministic push failure
        // without any real network hop, so the test doesn't depend on external connectivity.
        context.getSharedPreferences("ollama_swarm_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("git_remote_url", "http://127.0.0.1:1/repo.git").apply()
        val securePrefs = FakeSecurePrefs().apply { setGitToken("fake-token") }

        val ollama = ScriptedDirectiveOllamaService(
            planResponse = "- push my changes",
            actResponse = "git push",
            verifyResponse = "No further tooling available to verify."
        )
        val engine = buildEngine(db, ollama, FakeMcpClient(), securePrefs, context)

        var taskId: Int? = null
        val job = launch { taskId = engine.executeTask(config, "please push my commits") }

        val pending = PendingApprovalStore.pendingApproval.value
        assertTrue("Expected a pending GIT_PUSH approval request", pending != null && pending.riskCategory == ApprovalRiskCategory.GIT_PUSH)

        PendingApprovalStore.approve()
        job.join()

        assertTrue(PendingApprovalStore.pendingApproval.value == null)
        val steps = db.taskStepDao().getStepsForTaskSync(taskId!!)
        assertTrue(
            "Expected the push to actually be attempted after approval (fails locally since there's no real remote, but must not be blocked)",
            steps.any { it.actionType == "GIT_PUSH" || it.actionType == "GIT_PUSH_FAILED" }
        )
        assertTrue("Approval should never produce a decline step", steps.none { it.actionType == "ACTION_DECLINED" })
    }

    @Test
    fun approvalGate_gitPush_rejected_skipsPushAndRecordsDecline() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val node = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        db.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))
        db.agentDao().insertAgent(Agent(id = 902, name = "Pusher", role = "Programmer", modelName = "llama3", systemPrompt = "", colorHex = "#000000"))
        val config = SwarmConfig(id = 902, name = "Push Swarm Reject", description = "", coordinationMode = "AGENTIC_LOOP", agentIds = "902")

        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        context.getSharedPreferences("ollama_swarm_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString("git_remote_url", "http://127.0.0.1:1/repo.git").apply()
        val securePrefs = FakeSecurePrefs().apply { setGitToken("fake-token") }

        val ollama = ScriptedDirectiveOllamaService(
            planResponse = "- push my changes",
            actResponse = "git push",
            verifyResponse = "No further tooling available to verify."
        )
        val engine = buildEngine(db, ollama, FakeMcpClient(), securePrefs, context)

        var taskId: Int? = null
        val job = launch { taskId = engine.executeTask(config, "please push my commits") }

        assertTrue(PendingApprovalStore.pendingApproval.value != null)
        PendingApprovalStore.reject()
        job.join()

        val steps = db.taskStepDao().getStepsForTaskSync(taskId!!)
        assertTrue("Expected a decline step", steps.any { it.actionType == "ACTION_DECLINED" })
        assertTrue(
            "A rejected push must never reach the real push attempt",
            steps.none { it.actionType == "GIT_PUSH" || it.actionType == "GIT_PUSH_FAILED" }
        )
    }

    @Test
    fun approvalGate_riskyMcpCall_pausesThenProceedsOnApprove() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val node = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        db.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))
        db.agentDao().insertAgent(Agent(id = 903, name = "Deployer", role = "Programmer", modelName = "llama3", systemPrompt = "", colorHex = "#000000"))
        db.mcpServerDao().insertServer(
            McpServer(id = 903, name = "Deploy MCP", type = "Deploy", sourceUrl = "http://localhost:9999/mcp", status = "Connected", toolsCount = 1, configuredParams = "{}")
        )
        db.claudeSkillDao().insertSkill(
            ClaudeSkill(id = 903, name = "Force Deploy", description = "Force-push a deployment, destroying the previous release", category = "Automation", isRecommended = true, isEnabled = true, usageExample = "", requiredMcpServerType = "Deploy", sourceToolName = null)
        )
        val config = SwarmConfig(id = 903, name = "Deploy Swarm", description = "", coordinationMode = "AGENTIC_LOOP", agentIds = "903")

        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val ollama = ScriptedDirectiveOllamaService(
            planResponse = "- deploy the release",
            actResponse = "MCP_CALL: Force Deploy | {}",
            verifyResponse = "No further tooling available to verify."
        )
        val engine = buildEngine(db, ollama, FakeMcpClient(), FakeSecurePrefs(), context)

        var taskId: Int? = null
        val job = launch { taskId = engine.executeTask(config, "deploy it") }

        val pending = PendingApprovalStore.pendingApproval.value
        assertTrue(
            "Expected a pending MCP destructive-call approval request, got: $pending",
            pending != null && pending.riskCategory == ApprovalRiskCategory.MCP_DESTRUCTIVE_CALL
        )

        PendingApprovalStore.approve()
        job.join()

        val steps = db.taskStepDao().getStepsForTaskSync(taskId!!)
        assertTrue("Expected the tool call to actually proceed after approval", steps.any { it.actionType == "MCP_TOOL_CALL" })
        assertTrue(steps.none { it.actionType == "ACTION_DECLINED" })
    }

    @Test
    fun approvalGate_nonRiskyMcpCall_neverPauses() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val node = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        db.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))
        db.agentDao().insertAgent(Agent(id = 904, name = "Reader", role = "Programmer", modelName = "llama3", systemPrompt = "", colorHex = "#000000"))
        db.mcpServerDao().insertServer(
            McpServer(id = 904, name = "Read MCP", type = "Reader", sourceUrl = "http://localhost:9998/mcp", status = "Connected", toolsCount = 1, configuredParams = "{}")
        )
        db.claudeSkillDao().insertSkill(
            ClaudeSkill(id = 904, name = "Read Status", description = "Read-only status check, no side effects", category = "Analysis", isRecommended = true, isEnabled = true, usageExample = "", requiredMcpServerType = "Reader", sourceToolName = null)
        )
        val config = SwarmConfig(id = 904, name = "Read Swarm", description = "", coordinationMode = "AGENTIC_LOOP", agentIds = "904")

        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val ollama = ScriptedDirectiveOllamaService(
            planResponse = "- check the status",
            actResponse = "MCP_CALL: Read Status | {}",
            verifyResponse = "No further tooling available to verify."
        )
        val engine = buildEngine(db, ollama, FakeMcpClient(), FakeSecurePrefs(), context)

        val taskId = engine.executeTask(config, "check status") // non-risky path completes without ever suspending on approval

        assertTrue(PendingApprovalStore.pendingApproval.value == null)
        val steps = db.taskStepDao().getStepsForTaskSync(taskId)
        assertTrue(steps.any { it.actionType == "MCP_TOOL_CALL" })
    }
}
