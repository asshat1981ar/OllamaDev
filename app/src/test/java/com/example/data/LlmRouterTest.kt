package com.example.data

import com.example.ui.FakeAppDatabase
import com.example.ui.FakeOllamaService
import com.example.ui.FakeSecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmRouterTest {
    private fun router(db: FakeAppDatabase, ollama: OllamaService = FakeOllamaService()) =
        LlmRouter(
            ollamaService = ollama,
            nodeDao = db.ollamaNodeDao(),
            skillDao = db.claudeSkillDao(),
            securePrefs = FakeSecurePrefs(),
            dispatcher = Dispatchers.Unconfined
        )

    @Test
    fun generateFreeform_picksOnlineNode() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        db.ollamaNodeDao().getAllNodesSync().first().let { db.ollamaNodeDao().updateNode(it.copy(status = "Online", latencyMs = 10)) }
        val out = router(db).generateFreeform("hi", "sys")
        assertTrue(out.contains("[FakeOllama]"))
    }

    @Test
    fun generateFreeform_noOnlineNode_returnsConfigureMessage() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        db.ollamaNodeDao().getAllNodesSync().forEach { db.ollamaNodeDao().updateNode(it.copy(status = "Offline")) }
        val out = router(db).generateFreeform("hi", "sys")
        assertEquals("Error: no online Ollama node available to service this request. Configure at least one node in Manage > Nodes.", out)
    }

    @Test
    fun generateForAgent_appendsSkillsContextToSystemPrompt() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        db.ollamaNodeDao().getAllNodesSync().first().let { db.ollamaNodeDao().updateNode(it.copy(status = "Online", latencyMs = 10)) }
        db.claudeSkillDao().insertSkill(ClaudeSkill(id = 99, name = "Git Branch Creator", description = "d", category = "Development", isEnabled = true, usageExample = "git branch x", requiredMcpServerType = "None"))
        val captured = mutableListOf<String?>()
        val capturing = object : OllamaService by FakeOllamaService() {
            override suspend fun generate(nodeUrl: String, modelName: String, prompt: String, systemPrompt: String?, apiKey: String?): String? {
                captured += systemPrompt
                return "ok"
            }
        }
        val agent = Agent(id = 1, name = "A", role = "Programmer", modelName = "llama3", systemPrompt = "BASE", colorHex = "#000")
        router(db, capturing).generateForAgent(agent, "do it")
        assertTrue(captured.single()!!.startsWith("BASE"))
        assertTrue(captured.single()!!.contains("AVAILABLE SYSTEM TOOLS & MCP SKILLS"))
    }
}
