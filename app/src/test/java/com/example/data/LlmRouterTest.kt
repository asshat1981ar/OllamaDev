package com.example.data

import com.example.ui.FakeAppDatabase
import com.example.ui.FakeOllamaService
import com.example.ui.FakeSecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun generateForAgent_documentsDirectiveReference() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        db.ollamaNodeDao().getAllNodesSync().first().let { db.ollamaNodeDao().updateNode(it.copy(status = "Online", latencyMs = 10)) }
        val captured = mutableListOf<String?>()
        val capturing = object : OllamaService by FakeOllamaService() {
            override suspend fun generate(nodeUrl: String, modelName: String, prompt: String, systemPrompt: String?, apiKey: String?): String? {
                captured += systemPrompt
                return "ok"
            }
        }
        val agent = Agent(id = 1, name = "A", role = "Programmer", modelName = "llama3", systemPrompt = "BASE", colorHex = "#000")
        router(db, capturing).generateForAgent(agent, "do it")
        val sys = captured.single()!!
        assertTrue("expected WRITE_FILE directive reference", sys.contains("WRITE_FILE: <path>"))
        assertTrue("expected MCP_CALL directive reference", sys.contains("MCP_CALL: <tool") || sys.contains("MCP_CALL: <skill"))
        assertTrue("expected git directive reference", sys.contains("git "))
        assertTrue("expected approval-gate awareness", sys.contains("approval", ignoreCase = true))
        assertTrue("expected standards reminder", sys.contains("standards", ignoreCase = true))
    }

    @Test
    fun generateFreeform_doesNotContainDirectiveReference() = runTest(UnconfinedTestDispatcher()) {
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
        router(db, capturing).generateFreeform("hi", "sys")
        val sys = captured.single()!!
        assertFalse("WRITE_FILE must NOT appear in freeform prompts", sys.contains("WRITE_FILE:"))
        assertFalse("directive reference marker must NOT appear in freeform prompts", sys.contains("AGENT DIRECTIVES"))
    }

    @Test
    fun routePrompt_doesNotContainDirectiveReference() = runTest(UnconfinedTestDispatcher()) {
        val db = FakeAppDatabase()
        db.ollamaNodeDao().getAllNodesSync().first().let { db.ollamaNodeDao().updateNode(it.copy(status = "Online", latencyMs = 10)) }
        val captured = mutableListOf<String?>()
        val capturing = object : OllamaService by FakeOllamaService() {
            override suspend fun generate(nodeUrl: String, modelName: String, prompt: String, systemPrompt: String?, apiKey: String?): String? {
                captured += systemPrompt
                return "ok"
            }
        }
        router(db, capturing).routePrompt("hi", "sys")
        val sys = captured.single()!!
        assertFalse("WRITE_FILE must NOT appear in routePrompts", sys.contains("WRITE_FILE:"))
        assertFalse("directive reference marker must NOT appear in routePrompts", sys.contains("AGENT DIRECTIVES"))
    }
}
