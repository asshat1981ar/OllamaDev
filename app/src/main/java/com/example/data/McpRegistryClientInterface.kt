package com.example.data

/**
 * Testable abstraction over [McpRegistryClient]. Production implementations delegate to a real
 * client; tests can provide deterministic registry responses without hitting the network.
 */
interface McpRegistryClientInterface {
    suspend fun searchServers(
        query: String = "",
        limit: Int = 30,
        cursor: String? = null
    ): Result<Pair<List<RegistryServerDetail>, String?>>

    suspend fun getServerDetail(serverName: String): Result<RegistryServerDetail>
}
