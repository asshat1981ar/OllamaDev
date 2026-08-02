package com.example.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: McpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = McpClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun initialize_parsesSessionIdFromJsonResponse() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Mcp-Session-Id", "session-123")
                .setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}""")
        )
        server.enqueue(MockResponse().setResponseCode(202)) // notifications/initialized

        val result = client.initialize(server.url("/mcp").toString(), authToken = null)

        assertTrue(result.isSuccess)
        assertEquals("session-123", result.getOrNull()?.sessionId)

        val initRequest = server.takeRequest()
        assertTrue(initRequest.body.readUtf8().contains("\"method\":\"initialize\""))
        val notifyRequest = server.takeRequest()
        assertTrue(notifyRequest.body.readUtf8().contains("notifications/initialized"))
    }

    @Test
    fun initialize_withAuthToken_sendsBearerHeader() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"jsonrpc":"2.0","id":1,"result":{}}""")
        )
        server.enqueue(MockResponse().setResponseCode(202))

        client.initialize(server.url("/mcp").toString(), authToken = "secret-token")

        val initRequest = server.takeRequest()
        assertEquals("Bearer secret-token", initRequest.getHeader("Authorization"))
    }

    @Test
    fun listTools_parsesToolsFromSseStream() = runBlocking {
        val sseBody = "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[" +
            "{\"name\":\"search\",\"description\":\"Search things\"}]}}\n\n"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val session = McpSession(sessionId = "s1", protocolVersion = "2025-06-18")
        val result = client.listTools(server.url("/mcp").toString(), session, authToken = null)

        assertTrue(result.isSuccess)
        val tools = result.getOrNull().orEmpty()
        assertEquals(1, tools.size)
        assertEquals("search", tools[0].name)
        assertEquals("Search things", tools[0].description)

        val request = server.takeRequest()
        assertEquals("s1", request.getHeader("Mcp-Session-Id"))
    }

    @Test
    fun callTool_surfacesRealJsonRpcError() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Tool not found"}}""")
        )

        val session = McpSession(sessionId = null, protocolVersion = "2025-06-18")
        val result = client.callTool(server.url("/mcp").toString(), session, null, "missing_tool", emptyMap())

        assertTrue(result.isFailure)
        assertEquals("Tool not found", result.exceptionOrNull()?.message)
    }

    @Test
    fun initialize_httpFailure_isReportedAsFailureNotSuccess() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = client.initialize(server.url("/mcp").toString(), authToken = null)

        assertTrue(result.isFailure)
        assertNull(result.getOrNull())
    }

    @Test
    fun initialize_usesModernTransportWhenServerRepliesToProbe() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Mcp-Session-Id", "modern-session-abc")
                .setBody(
                    """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"search_workspace","description":"Find files"}]}}"""
                )
        )

        val result = client.initialize(server.url("/mcp").toString(), authToken = null)

        assertTrue(result.isSuccess)
        val session = result.getOrNull()
        assertNotNull(session)
        assertEquals("modern-session-abc", session?.sessionId)
        assertEquals("2026-07-28", session?.protocolVersion)

        val probeRequest = server.takeRequest()
        val probeBody = probeRequest.body.readUtf8()
        assertEquals("tools/list", extractMethod(probeBody))
        assertEquals("2026-07-28", probeRequest.getHeader("MCP-Protocol-Version"))
        assertEquals("tools/list", probeRequest.getHeader("mcp-method"))
        assertTrue(probeBody.contains("\"_meta\""))
    }

    @Test
    fun initialize_fallsBackToLegacyWhenModernProbeFails() = runBlocking {
        // Modern probe fails with a JSON-RPC error (e.g., server doesn't understand _meta).
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setResponseCode(400)
                .setBody(
                    """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"params._meta must be an object"}}"""
                )
        )
        // Legacy initialize succeeds.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Mcp-Session-Id", "legacy-session-xyz")
                .setBody("""{"jsonrpc":"2.0","id":2,"result":{"protocolVersion":"2025-06-18"}}""")
        )
        // notifications/initialized ack.
        server.enqueue(MockResponse().setResponseCode(202))

        val result = client.initialize(server.url("/mcp").toString(), authToken = null)

        assertTrue(result.isSuccess)
        val session = result.getOrNull()
        assertEquals("legacy-session-xyz", session?.sessionId)
        assertEquals("2025-06-18", session?.protocolVersion)

        val probeRequest = server.takeRequest()
        val probeBody = probeRequest.body.readUtf8()
        assertEquals("tools/list", extractMethod(probeBody))
        assertEquals("2026-07-28", probeRequest.getHeader("MCP-Protocol-Version"))

        val initRequest = server.takeRequest()
        assertTrue(initRequest.body.readUtf8().contains("\"method\":\"initialize\""))
    }

    @Test
    fun listTools_modernRequestCarriesMetaAndMethodHeader() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setHeader("Mcp-Session-Id", "modern-session-abc")
                .setBody(
                    """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"ping","description":"Ping"}]}}"""
                )
        )

        val session = McpSession(sessionId = "modern-session-abc", protocolVersion = "2026-07-28")
        val result = client.listTools(server.url("/mcp").toString(), session, authToken = null)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)

        val request = server.takeRequest()
        assertEquals("2026-07-28", request.getHeader("MCP-Protocol-Version"))
        assertEquals("tools/list", request.getHeader("mcp-method"))
        assertEquals("modern-session-abc", request.getHeader("Mcp-Session-Id"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"_meta\""))
        assertTrue(body.contains("io.modelcontextprotocol/protocolVersion"))
    }

    @Test
    fun callTool_modernRequestCarriesMetaMethodAndNameHeaders() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"pong"}]}}"""
                )
        )

        val session = McpSession(sessionId = "modern-session-abc", protocolVersion = "2026-07-28")
        val result = client.callTool(
            serverUrl = server.url("/mcp").toString(),
            session = session,
            authToken = null,
            toolName = "ping",
            arguments = emptyMap()
        )

        assertTrue(result.isSuccess)
        assertEquals("pong", result.getOrNull())

        val request = server.takeRequest()
        assertEquals("2026-07-28", request.getHeader("MCP-Protocol-Version"))
        assertEquals("tools/call", request.getHeader("mcp-method"))
        assertEquals("ping", request.getHeader("mcp-name"))
        assertEquals("modern-session-abc", request.getHeader("Mcp-Session-Id"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"_meta\""))
        assertTrue(body.contains("\"method\":\"tools/call\""))
    }

    private fun extractMethod(jsonBody: String): String {
        val regex = """\"method\"\s*:\s*\"([^\"]+)\"""".toRegex()
        return regex.find(jsonBody)?.groupValues?.get(1) ?: ""
    }
}
