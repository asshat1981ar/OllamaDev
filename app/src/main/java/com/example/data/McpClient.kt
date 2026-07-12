package com.example.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private const val MCP_PROTOCOL_VERSION = "2025-06-18"

data class McpSession(val sessionId: String?, val protocolVersion: String)
data class McpTool(val name: String, val description: String?)

/**
 * Minimal MCP Streamable HTTP client (https://modelcontextprotocol.io). Android apps can't
 * spawn local stdio MCP servers, so this only supports remote/hosted servers reachable over
 * HTTP(S). Handles both a single application/json response and a text/event-stream response
 * (reads until the SSE event carrying the matching JSON-RPC response id arrives).
 */
class McpClient {
    private val moshi = Moshi.Builder().build()
    private val jsonObjectType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val jsonObjectAdapter = moshi.adapter<Map<String, Any?>>(jsonObjectType)
    private val idCounter = AtomicLong(1)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun buildRequest(
        serverUrl: String,
        method: String,
        params: Map<String, Any?>?,
        session: McpSession?,
        authToken: String?,
        requestId: Long?
    ): Request {
        val envelope = mutableMapOf<String, Any?>("jsonrpc" to "2.0", "method" to method)
        if (requestId != null) envelope["id"] = requestId
        if (params != null) envelope["params"] = params

        val json = jsonObjectAdapter.toJson(envelope)
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())

        val builder = Request.Builder()
            .url(serverUrl)
            .post(body)
            .addHeader("Accept", "application/json, text/event-stream")
            .addHeader("Content-Type", "application/json")
            .addHeader("MCP-Protocol-Version", session?.protocolVersion ?: MCP_PROTOCOL_VERSION)
        session?.sessionId?.let { builder.addHeader("Mcp-Session-Id", it) }
        if (!authToken.isNullOrBlank()) {
            builder.addHeader("Authorization", "Bearer $authToken")
        }
        return builder.build()
    }

    /** Parses either a single JSON body or an SSE stream, returning the response matching [expectedId]. */
    private fun parseResponse(response: Response, expectedId: Long): Map<String, Any?> {
        val contentType = response.header("Content-Type") ?: ""
        val body = response.body ?: throw IllegalStateException("Empty response body")

        if (contentType.contains("text/event-stream")) {
            body.charStream().buffered().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val current = line ?: continue
                    if (!current.startsWith("data:")) continue
                    val payload = current.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    val parsed = runCatching { jsonObjectAdapter.fromJson(payload) }.getOrNull()
                    val idNumber = (parsed?.get("id") as? Double)?.toLong()
                    if (parsed != null && idNumber == expectedId) return parsed
                }
            }
            throw IllegalStateException("No matching SSE response received from server")
        }

        val text = body.string()
        return jsonObjectAdapter.fromJson(text) ?: throw IllegalStateException("Invalid JSON-RPC response")
    }

    private fun extractError(parsed: Map<String, Any?>): String? {
        @Suppress("UNCHECKED_CAST")
        val error = parsed["error"] as? Map<String, Any?> ?: return null
        return error["message"] as? String ?: "Unknown MCP error"
    }

    suspend fun initialize(serverUrl: String, authToken: String?): Result<McpSession> =
        withContext(Dispatchers.IO) {
            try {
                val requestId = idCounter.getAndIncrement()
                val params = mapOf(
                    "protocolVersion" to MCP_PROTOCOL_VERSION,
                    "capabilities" to emptyMap<String, Any?>(),
                    "clientInfo" to mapOf("name" to "OllamaDev", "version" to "1.0")
                )
                val request = buildRequest(serverUrl, "initialize", params, null, authToken, requestId)
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use Result.failure<McpSession>(IllegalStateException("HTTP ${response.code}"))
                    }
                    val sessionId = response.header("Mcp-Session-Id")
                    val parsed = parseResponse(response, requestId)
                    val error = extractError(parsed)
                    if (error != null) {
                        return@use Result.failure<McpSession>(IllegalStateException(error))
                    }
                    val session = McpSession(sessionId, MCP_PROTOCOL_VERSION)
                    notifyInitialized(serverUrl, session, authToken)
                    Result.success(session)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun notifyInitialized(serverUrl: String, session: McpSession, authToken: String?) {
        withContext(Dispatchers.IO) {
            try {
                val request = buildRequest(serverUrl, "notifications/initialized", null, session, authToken, null)
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                // Best-effort notification; a failure here shouldn't break an otherwise-valid session.
            }
        }
    }

    suspend fun listTools(serverUrl: String, session: McpSession, authToken: String?): Result<List<McpTool>> =
        withContext(Dispatchers.IO) {
            try {
                val requestId = idCounter.getAndIncrement()
                val request = buildRequest(serverUrl, "tools/list", emptyMap(), session, authToken, requestId)
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@use Result.failure<List<McpTool>>(IllegalStateException("HTTP ${response.code}"))
                    }
                    val parsed = parseResponse(response, requestId)
                    val error = extractError(parsed)
                    if (error != null) {
                        return@use Result.failure<List<McpTool>>(IllegalStateException(error))
                    }
                    @Suppress("UNCHECKED_CAST")
                    val result = parsed["result"] as? Map<String, Any?>
                    @Suppress("UNCHECKED_CAST")
                    val toolsList = (result?.get("tools") as? List<Map<String, Any?>>).orEmpty()
                    val tools = toolsList.map {
                        McpTool(name = it["name"] as? String ?: "unknown", description = it["description"] as? String)
                    }
                    Result.success(tools)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun callTool(
        serverUrl: String,
        session: McpSession,
        authToken: String?,
        toolName: String,
        arguments: Map<String, Any?>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val requestId = idCounter.getAndIncrement()
            val params = mapOf("name" to toolName, "arguments" to arguments)
            val request = buildRequest(serverUrl, "tools/call", params, session, authToken, requestId)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use Result.failure<String>(IllegalStateException("HTTP ${response.code}"))
                }
                val parsed = parseResponse(response, requestId)
                val error = extractError(parsed)
                if (error != null) {
                    return@use Result.failure<String>(IllegalStateException(error))
                }
                Result.success(parsed["result"]?.toString() ?: "")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
