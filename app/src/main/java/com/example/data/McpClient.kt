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

private const val LEGACY_PROTOCOL_VERSION = "2025-06-18"
private const val MODERN_PROTOCOL_VERSION = "2026-07-28"

private const val META_PROTOCOL_VERSION_KEY = "io.modelcontextprotocol/protocolVersion"
private const val META_CLIENT_CAPABILITIES_KEY = "io.modelcontextprotocol/clientCapabilities"

private val CLIENT_CAPABILITIES = emptyMap<String, Any?>()

private const val MCP_METHOD_HEADER = "mcp-method"

data class McpSession(val sessionId: String?, val protocolVersion: String)
data class McpTool(
    val name: String,
    val description: String?,
    val inputSchema: Map<String, Any?>? = null,
    val outputSchema: Map<String, Any?>? = null,
    val annotations: Map<String, Any?>? = null
)

/**
 * Minimal MCP Streamable HTTP client (https://modelcontextprotocol.io). Android apps can't
 * spawn local stdio MCP servers, so this only supports remote/hosted servers reachable over
 * HTTP(S).
 *
 * This client now handles both protocol transports:
 * - **Legacy (2025-06-18):** `initialize` handshake + `notifications/initialized`, per-request
 *   `Mcp-Session-Id` header.
 * - **Modern (2026-07-28):** no handshake; every request carries `MCP-Protocol-Version`,
 *   `mcp-method`, and a `_meta` envelope with protocol version and client capabilities.
 *
 * The transport is auto-detected during [initialize].
 */
class McpClient : McpClientInterface {
    private val moshi = Moshi.Builder().build()
    private val jsonObjectType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val jsonObjectAdapter = moshi.adapter<Map<String, Any?>>(jsonObjectType)
    private val idCounter = AtomicLong(1)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /** True when the negotiated session uses the modern 2026-07-28 transport. */
    private fun isModern(session: McpSession?): Boolean =
        session?.protocolVersion == MODERN_PROTOCOL_VERSION

    /** Build the `_meta` envelope required by the modern streamable HTTP transport. */
    private fun modernMeta(): Map<String, Any?> = mapOf(
        META_PROTOCOL_VERSION_KEY to MODERN_PROTOCOL_VERSION,
        META_CLIENT_CAPABILITIES_KEY to CLIENT_CAPABILITIES
    )

    /** Inject the `_meta` envelope into modern request params. */
    private fun paramsWithMeta(params: Map<String, Any?>?): Map<String, Any?> {
        if (params == null) return mapOf("_meta" to modernMeta())
        if (params.containsKey("_meta")) return params
        return params + ("_meta" to modernMeta())
    }

    private fun buildRequest(
        serverUrl: String,
        method: String,
        params: Map<String, Any?>?,
        session: McpSession?,
        authToken: String?,
        requestId: Long?
    ): Request {
        val protocolVersion = session?.protocolVersion ?: LEGACY_PROTOCOL_VERSION
        val modern = isModern(session)

        val envelope = mutableMapOf<String, Any?>("jsonrpc" to "2.0", "method" to method)
        if (requestId != null) envelope["id"] = requestId
        envelope["params"] = if (modern) paramsWithMeta(params) else (params ?: emptyMap())

        val json = jsonObjectAdapter.toJson(envelope)
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())

        val builder = Request.Builder()
            .url(serverUrl)
            .post(body)
            .addHeader("Accept", "application/json, text/event-stream")
            .addHeader("Content-Type", "application/json")
            .addHeader("MCP-Protocol-Version", protocolVersion)
        if (modern) {
            builder.addHeader(MCP_METHOD_HEADER, method)
        }
        session?.sessionId?.let { builder.addHeader("Mcp-Session-Id", it) }
        if (!authToken.isNullOrBlank()) {
            builder.addHeader("Authorization", "Bearer $authToken")
        }
        return builder.build()
    }

    /** Build a `tools/call` request. Extracted so the tool name can be sent as `mcp-name` header. */
    private fun buildToolCallRequest(
        serverUrl: String,
        method: String,
        params: Map<String, Any?>,
        session: McpSession,
        authToken: String?,
        requestId: Long,
        toolName: String
    ): Request {
        val request = buildRequest(serverUrl, method, params, session, authToken, requestId)
        if (isModern(session)) {
            return request.newBuilder()
                .addHeader("mcp-name", toolName)
                .build()
        }
        return request
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

    override fun toJsonString(value: Map<String, Any?>?): String? {
        return value?.let { jsonObjectAdapter.toJson(it) }
    }

    private fun extractError(parsed: Map<String, Any?>): String? {
        @Suppress("UNCHECKED_CAST")
        val error = parsed["error"] as? Map<String, Any?> ?: return null
        return error["message"] as? String ?: "Unknown MCP error"
    }

    /**
     * Extract a human-readable string from a tool result. Modern (2026-07-28) servers return
     * a `content` list of text objects; legacy servers often return the result directly.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractToolResult(result: Any?): String {
        if (result == null) return ""
        if (result is String) return result
        val resultMap = result as? Map<String, Any?> ?: return result.toString()
        val content = resultMap["content"] as? List<Map<String, Any?>>
        if (!content.isNullOrEmpty()) {
            return content
                .filter { it["type"] == "text" }
                .joinToString("\n") { it["text"]?.toString() ?: "" }
        }
        return result.toString()
    }

    /**
     * Probe the server with a modern `tools/list` request (no initialize handshake).
     * Returns a modern session if the server speaks 2026-07-28.
     */
    private suspend fun tryModernConnect(serverUrl: String, authToken: String?): Result<McpSession> =
        withContext(Dispatchers.IO) {
            try {
                val requestId = idCounter.getAndIncrement()
                val params = mapOf("_meta" to modernMeta())
                val request = buildRequest(
                    serverUrl,
                    "tools/list",
                    params,
                    McpSession(null, MODERN_PROTOCOL_VERSION),
                    authToken,
                    requestId
                )
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
                    Result.success(McpSession(sessionId, MODERN_PROTOCOL_VERSION))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Perform the legacy `initialize` handshake and notify the server that the client is initialized.
     */
    private suspend fun tryLegacyConnect(serverUrl: String, authToken: String?): Result<McpSession> =
        withContext(Dispatchers.IO) {
            try {
                val requestId = idCounter.getAndIncrement()
                val params = mapOf(
                    "protocolVersion" to LEGACY_PROTOCOL_VERSION,
                    "capabilities" to emptyMap<String, Any?>(),
                    "clientInfo" to mapOf("name" to "OllamaDev", "version" to "1.0")
                )
                val request = buildRequest(
                    serverUrl,
                    "initialize",
                    params,
                    McpSession(null, LEGACY_PROTOCOL_VERSION),
                    authToken,
                    requestId
                )
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
                    val session = McpSession(sessionId, LEGACY_PROTOCOL_VERSION)
                    notifyInitialized(serverUrl, session, authToken)
                    Result.success(session)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun initialize(serverUrl: String, authToken: String?): Result<McpSession> =
        withContext(Dispatchers.IO) {
            // Modern transport first: if the server replies to a tools/list probe with the
            // required 2026-07-28 envelope, use it. Otherwise fall back to the legacy handshake.
            val modernResult = tryModernConnect(serverUrl, authToken)
            if (modernResult.isSuccess) {
                return@withContext modernResult
            }
            val legacyResult = tryLegacyConnect(serverUrl, authToken)
            if (legacyResult.isSuccess) {
                return@withContext legacyResult
            }
            // Surface the legacy error as the more informative failure for older servers.
            legacyResult
        }

    override suspend fun notifyInitialized(serverUrl: String, session: McpSession, authToken: String?) {
        // Modern transport has no initialized notification; skip silently.
        if (isModern(session)) return

        withContext(Dispatchers.IO) {
            try {
                val request = buildRequest(serverUrl, "notifications/initialized", null, session, authToken, null)
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                // Best-effort notification; a failure here shouldn't break an otherwise-valid session.
            }
        }
    }

    override suspend fun listTools(
        serverUrl: String,
        session: McpSession,
        authToken: String?
    ): Result<List<McpTool>> =
        withContext(Dispatchers.IO) {
            try {
                val requestId = idCounter.getAndIncrement()
                val baseParams = if (isModern(session)) emptyMap() else emptyMap<String, Any?>()
                val request = buildRequest(serverUrl, "tools/list", baseParams, session, authToken, requestId)
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
                    val tools = toolsList.map { toolMap ->
                        @Suppress("UNCHECKED_CAST")
                        McpTool(
                            name = toolMap["name"] as? String ?: "unknown",
                            description = toolMap["description"] as? String,
                            inputSchema = toolMap["inputSchema"] as? Map<String, Any?>,
                            outputSchema = toolMap["outputSchema"] as? Map<String, Any?>,
                            annotations = toolMap["annotations"] as? Map<String, Any?>
                        )
                    }
                    Result.success(tools)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    override suspend fun callTool(
        serverUrl: String,
        session: McpSession,
        authToken: String?,
        toolName: String,
        arguments: Map<String, Any?>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val requestId = idCounter.getAndIncrement()
            val params = mapOf("name" to toolName, "arguments" to arguments)
            val request = buildToolCallRequest(
                serverUrl,
                "tools/call",
                params,
                session,
                authToken,
                requestId,
                toolName
            )
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use Result.failure<String>(IllegalStateException("HTTP ${response.code}"))
                }
                val parsed = parseResponse(response, requestId)
                val error = extractError(parsed)
                if (error != null) {
                    return@use Result.failure<String>(IllegalStateException(error))
                }
                Result.success(extractToolResult(parsed["result"]))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
