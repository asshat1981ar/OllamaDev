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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/**
 * Same setup shape as [SwarmEngineApprovalGateTest.approvalGate_riskyMcpCall_pausesThenProceedsOnApprove]
 * but asserts the matched reason is rendered into [PendingApproval.detail] for both the
 * annotation-based path (destructiveHint=true) and the keyword-match fallback path.
 *
 * The detail field is captured by reading [PendingApprovalStore.pendingApproval] BEFORE the gate
 * is resolved (the StateFlow is cleared on approve/reject). The test then approves so the engine
 * proceeds; the post-approval steps are not asserted here.
 */
private class McpRiskReasoningScriptedOllamaService(
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SwarmEngineMcpRiskReasoningTest {

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
    fun approvalGate_destructiveHintAnnotation_detailMentionsAnnotationReason() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val node = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        db.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))
        db.agentDao().insertAgent(Agent(id = 911, name = "Deployer", role = "Programmer", modelName = "llama3", systemPrompt = "", colorHex = "#000000"))
        db.mcpServerDao().insertServer(
            McpServer(id = 911, name = "Deploy MCP", type = "Deploy", sourceUrl = "http://localhost:9999/mcp", status = "Connected", toolsCount = 1, configuredParams = "{}")
        )
        // Tool with an explicit destructiveHint annotation -- this is the path the engine should
        // take priority on, ahead of any keyword matching.
        db.mcpToolDao().insertTool(
            McpToolEntity(
                serverId = 911, name = "deploy_model",
                description = "Deploy a model artifact",
                inputSchemaJson = "{}",
                outputSchemaJson = null,
                annotationsJson = "{\"destructiveHint\":true}"
            )
        )
        db.claudeSkillDao().insertSkill(
            ClaudeSkill(
                id = 911, name = "Deploy Model", description = "Deploy a model", category = "Automation",
                isRecommended = true, isEnabled = true, usageExample = "", requiredMcpServerType = "Deploy",
                sourceToolName = "deploy_model"
            )
        )
        val config = SwarmConfig(id = 911, name = "Deploy Swarm", description = "", coordinationMode = "AGENTIC_LOOP", agentIds = "911")

        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val ollama = McpRiskReasoningScriptedOllamaService(
            planResponse = "- deploy the release",
            actResponse = "MCP_CALL: Deploy Model | {}",
            verifyResponse = "No further tooling available to verify."
        )
        val engine = buildEngine(db, ollama, FakeMcpClient(), FakeSecurePrefs(), context)

        val job = launch { engine.executeTask(config, "deploy it") }

        val pending = PendingApprovalStore.pendingApproval.value
        assertNotNull("Expected a pending MCP destructive-call approval request", pending)
        assertTrue(
            "Expected riskCategory MCP_DESTRUCTIVE_CALL, got: ${pending?.riskCategory}",
            pending?.riskCategory == ApprovalRiskCategory.MCP_DESTRUCTIVE_CALL
        )
        assertTrue(
            "Expected detail to mention the destructiveHint annotation reason; got: '${pending?.detail}'",
            pending?.detail?.contains("destructiveHint", ignoreCase = true) == true
        )
        // Approve so the engine proceeds and the coroutine completes.
        PendingApprovalStore.approve()
        job.join()
    }

    @Test
    fun approvalGate_keywordMatch_detailMentionsMatchedKeyword() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val node = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        db.ollamaNodeDao().updateNode(node.copy(status = "Online", latencyMs = 10))
        db.agentDao().insertAgent(Agent(id = 912, name = "Deployer", role = "Programmer", modelName = "llama3", systemPrompt = "", colorHex = "#000000"))
        db.mcpServerDao().insertServer(
            McpServer(id = 912, name = "Deploy MCP", type = "Deploy", sourceUrl = "http://localhost:9999/mcp", status = "Connected", toolsCount = 1, configuredParams = "{}")
        )
        // Tool WITHOUT a destructiveHint annotation -- forces the keyword-match fallback path.
        // The skill name "Force Deploy" contains both "force" and "deploy" so the keyword
        // matcher should hit on the first one it scans.
        db.mcpToolDao().insertTool(
            McpToolEntity(
                serverId = 912, name = "force_push",
                description = "Push the current release",
                inputSchemaJson = "{}",
                outputSchemaJson = null,
                annotationsJson = null
            )
        )
        db.claudeSkillDao().insertSkill(
            ClaudeSkill(
                id = 912, name = "Force Deploy", description = "Force-push a deployment", category = "Automation",
                isRecommended = true, isEnabled = true, usageExample = "", requiredMcpServerType = "Deploy",
                sourceToolName = "force_push"
            )
        )
        val config = SwarmConfig(id = 912, name = "Deploy Swarm 2", description = "", coordinationMode = "AGENTIC_LOOP", agentIds = "912")

        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val ollama = McpRiskReasoningScriptedOllamaService(
            planResponse = "- push the release",
            actResponse = "MCP_CALL: Force Deploy | {}",
            verifyResponse = "No further tooling available to verify."
        )
        val engine = buildEngine(db, ollama, FakeMcpClient(), FakeSecurePrefs(), context)

        val job = launch { engine.executeTask(config, "deploy it") }

        val pending = PendingApprovalStore.pendingApproval.value
        assertNotNull("Expected a pending MCP destructive-call approval request", pending)
        assertTrue(
            "Expected riskCategory MCP_DESTRUCTIVE_CALL, got: ${pending?.riskCategory}",
            pending?.riskCategory == ApprovalRiskCategory.MCP_DESTRUCTIVE_CALL
        )
        assertTrue(
            "Expected detail to mention a matched keyword (one of: delete/remove/drop/push/deploy/destroy/force/publish/merge); got: '${pending?.detail}'",
            listOf("delete", "remove", "drop", "push", "deploy", "destroy", "force", "publish", "merge")
                .any { pending?.detail?.contains(it, ignoreCase = true) == true }
        )
        // Approve so the engine proceeds and the coroutine completes.
        PendingApprovalStore.approve()
        job.join()
    }
}
