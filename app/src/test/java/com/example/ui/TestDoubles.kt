package com.example.ui

import com.example.data.ClaudeSkill
import com.example.data.McpClientInterface
import com.example.data.McpRegistryClientInterface
import com.example.data.McpSession
import com.example.data.McpTool
import com.example.data.OllamaService
import com.example.data.RegistryRemote
import com.example.data.RegistryRemoteHeader
import com.example.data.RegistryServerDetail
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

class FakeOllamaService : OllamaService {
    override suspend fun generate(
        nodeUrl: String,
        modelName: String,
        prompt: String,
        systemPrompt: String?,
        apiKey: String?
    ): String? = "[FakeOllama] $modelName says: ${prompt.take(80)}"

    override suspend fun generateStreaming(
        nodeUrl: String,
        modelName: String,
        prompt: String,
        systemPrompt: String?,
        apiKey: String?,
        onToken: suspend (String) -> Unit
    ): String? {
        val full = "[FakeOllama] $modelName says: ${prompt.take(80)}"
        val chunkSize = 12
        val accumulator = StringBuilder()
        full.chunked(chunkSize).forEach { chunk ->
            accumulator.append(chunk)
            onToken(accumulator.toString())
        }
        return accumulator.toString()
    }

    override suspend fun pingAndFetchModels(
        nodeUrl: String,
        apiKey: String?
    ): Pair<Boolean, List<String>> = when {
        nodeUrl.contains("127.0.0.1") || nodeUrl.contains("localhost", ignoreCase = true) ->
            true to listOf("llama3", "mistral")
        else -> false to emptyList()
    }
}

class FakeMcpClient : McpClientInterface {
    var shouldFail = false
    val initializedUrls = mutableListOf<String>()

    override suspend fun initialize(serverUrl: String, authToken: String?): Result<McpSession> {
        initializedUrls += serverUrl
        return if (shouldFail) {
            Result.failure(IllegalStateException("Fake MCP initialize failure"))
        } else {
            Result.success(McpSession(sessionId = "fake-session-${serverUrl.hashCode()}", protocolVersion = "2025-06-18"))
        }
    }

    override suspend fun notifyInitialized(serverUrl: String, session: McpSession, authToken: String?) {
        // no-op
    }

    override suspend fun listTools(
        serverUrl: String,
        session: McpSession,
        authToken: String?
    ): Result<List<McpTool>> = if (shouldFail) {
        Result.failure(IllegalStateException("Fake MCP listTools failure"))
    } else {
        Result.success(
            listOf(
                McpTool(
                    name = "search",
                    description = "Fake web search",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf("query" to mapOf("type" to "string")),
                        "required" to listOf("query")
                    ),
                    outputSchema = null,
                    annotations = null
                ),
                McpTool(
                    name = "fetch",
                    description = "Fake URL fetch",
                    inputSchema = mapOf(
                        "type" to "object",
                        "properties" to mapOf("url" to mapOf("type" to "string")),
                        "required" to listOf("url")
                    ),
                    outputSchema = null,
                    annotations = null
                )
            )
        )
    }

    /** When set, callTool() returns this exact text instead of the default templated string --
     *  used to script a passing/failing verify-phase tool result in agentic-loop tests. */
    var scriptedToolResult: String? = null

    override suspend fun callTool(
        serverUrl: String,
        session: McpSession,
        authToken: String?,
        toolName: String,
        arguments: Map<String, Any?>
    ): Result<String> = if (shouldFail) {
        Result.failure(IllegalStateException("Fake MCP callTool failure"))
    } else {
        Result.success(scriptedToolResult ?: "Fake result for $toolName with $arguments")
    }

    override fun toJsonString(value: Map<String, Any?>?): String? {
        if (value == null) return null
        val moshi = Moshi.Builder().build()
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        return moshi.adapter<Map<String, Any?>>(type).toJson(value)
    }
}

class FakeMcpRegistryClient : McpRegistryClientInterface {
    override suspend fun searchServers(
        query: String,
        limit: Int,
        cursor: String?
    ): Result<Pair<List<RegistryServerDetail>, String?>> {
        val servers = listOf(
            RegistryServerDetail(
                name = "example/brave-search",
                title = "Brave Search",
                description = "Privacy-focused web search MCP server.",
                remotes = listOf(
                    RegistryRemote(
                        type = "streamable-http",
                        url = "https://example.com/brave/mcp",
                        headers = listOf(RegistryRemoteHeader(name = "Authorization", isSecret = true))
                    )
                )
            ),
            RegistryServerDetail(
                name = "example/postgres",
                title = "Postgres",
                description = "Query Postgres databases via MCP.",
                remotes = listOf(
                    RegistryRemote(
                        type = "streamable-http",
                        url = "https://example.com/postgres/mcp",
                        headers = emptyList()
                    )
                )
            )
        )
        return Result.success(servers to "next-cursor-1")
    }

    override suspend fun getServerDetail(serverName: String): Result<RegistryServerDetail> {
        return Result.success(
            RegistryServerDetail(
                name = serverName,
                title = serverName.substringAfterLast("/").replace("-", " ").replaceFirstChar { it.uppercase() },
                remotes = listOf(
                    RegistryRemote(
                        type = "streamable-http",
                        url = "https://example.com/$serverName/mcp",
                        headers = emptyList()
                    )
                )
            )
        )
    }
}

/**
 * Test helper that makes deterministic copies of seeded skills so toggle tests don't depend on
 * the exact IDs produced by the database seeder.
 */
fun fakeSkill(name: String, enabled: Boolean = true): ClaudeSkill =
    ClaudeSkill(name = name, description = "Fake", category = "Development", isEnabled = enabled)
