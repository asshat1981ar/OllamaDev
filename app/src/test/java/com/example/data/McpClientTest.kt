package com.example.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
}
