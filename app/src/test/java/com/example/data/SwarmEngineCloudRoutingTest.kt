package com.example.data

import androidx.test.core.app.ApplicationProvider
import com.example.ui.FakeAppDatabase
import com.example.ui.FakeMcpClient
import com.example.ui.FakeSecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/** Records which node URL every call was routed to, so tests can assert cloud-preference
 *  routing without depending on response content (unlike [com.example.ui.FakeOllamaService],
 *  which only echoes the model name, not the node). */
private class RecordingOllamaService : OllamaService {
    val calledNodeUrls = mutableListOf<String>()

    override suspend fun generate(nodeUrl: String, modelName: String, prompt: String, systemPrompt: String?, apiKey: String?): String? {
        calledNodeUrls += nodeUrl
        return "response from $nodeUrl"
    }

    override suspend fun generateStreaming(
        nodeUrl: String, modelName: String, prompt: String, systemPrompt: String?, apiKey: String?,
        onToken: suspend (String) -> Unit
    ): String? {
        calledNodeUrls += nodeUrl
        val full = "response from $nodeUrl"
        onToken(full)
        return full
    }

    override suspend fun pingAndFetchModels(nodeUrl: String, apiKey: String?): Pair<Boolean, List<String>> =
        true to listOf("some-model")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SwarmEngineCloudRoutingTest {

    private fun buildEngine(db: AppDatabaseInterface, ollamaService: OllamaService): SwarmEngine {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val gitService = GitService(File(context.cacheDir, "git-test-${System.nanoTime()}"))
        return SwarmEngine(
            db = db, gitService = gitService, mcpClient = FakeMcpClient(), appContext = context,
            securePrefs = FakeSecurePrefs(), ollamaService = ollamaService, dispatcher = Dispatchers.Unconfined
        )
    }

    @Test
    fun preferCloud_true_routesToCloudGatewayEvenWhenSlower() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val localNode = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        val cloudNode = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Ollama Cloud Gateway" }
        db.ollamaNodeDao().updateNode(localNode.copy(status = "Online", latencyMs = 5))
        db.ollamaNodeDao().updateNode(cloudNode.copy(status = "Online", latencyMs = 500))

        val ollama = RecordingOllamaService()
        val engine = buildEngine(db, ollama)

        engine.generateFreeform("hello", "system prompt", preferCloud = true)

        assertTrue(
            "Expected the cloud gateway node to be used despite higher latency, calls were: ${ollama.calledNodeUrls}",
            ollama.calledNodeUrls.last().contains("ollama.com")
        )
    }

    @Test
    fun preferCloud_false_stillPrefersLowestLatencyRegardlessOfNode() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val localNode = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        val cloudNode = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Ollama Cloud Gateway" }
        db.ollamaNodeDao().updateNode(localNode.copy(status = "Online", latencyMs = 5))
        db.ollamaNodeDao().updateNode(cloudNode.copy(status = "Online", latencyMs = 500))

        val ollama = RecordingOllamaService()
        val engine = buildEngine(db, ollama)

        engine.generateFreeform("hello", "system prompt", preferCloud = false)

        assertTrue(
            "Expected the lowest-latency (local) node without cloud preference, calls were: ${ollama.calledNodeUrls}",
            ollama.calledNodeUrls.last().contains("127.0.0.1")
        )
    }

    @Test
    fun preferCloud_true_cloudGatewayOffline_fallsBackToOnlinePool() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        val localNode = db.ollamaNodeDao().getAllNodesSync().first { it.name == "Local Node (Loopback)" }
        // Cloud gateway stays Offline (its seeded default) -- only the local node is online.
        db.ollamaNodeDao().updateNode(localNode.copy(status = "Online", latencyMs = 5))

        val ollama = RecordingOllamaService()
        val engine = buildEngine(db, ollama)

        val result = engine.generateFreeform("hello", "system prompt", preferCloud = true)

        assertTrue(
            "Expected fallback to the online local node instead of a hard failure, calls were: ${ollama.calledNodeUrls}",
            ollama.calledNodeUrls.last().contains("127.0.0.1")
        )
        assertTrue(!result.startsWith("Error:"))
    }
}
