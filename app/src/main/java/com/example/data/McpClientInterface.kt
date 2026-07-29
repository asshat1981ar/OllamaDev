package com.example.data

/**
 * Testable abstraction over [McpClient]. Production implementations delegate to a real
 * [McpClient]; tests can provide lightweight fakes that avoid network I/O.
 */
interface McpClientInterface {
    suspend fun initialize(serverUrl: String, authToken: String?): Result<McpSession>
    suspend fun notifyInitialized(serverUrl: String, session: McpSession, authToken: String?)
    suspend fun listTools(
        serverUrl: String,
        session: McpSession,
        authToken: String?
    ): Result<List<McpTool>>

    suspend fun callTool(
        serverUrl: String,
        session: McpSession,
        authToken: String?,
        toolName: String,
        arguments: Map<String, Any?>
    ): Result<String>

    fun toJsonString(value: Map<String, Any?>?): String?
}
