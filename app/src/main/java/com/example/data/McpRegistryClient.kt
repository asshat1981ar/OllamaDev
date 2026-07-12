package com.example.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val REGISTRY_BASE_URL = "https://registry.modelcontextprotocol.io"

@JsonClass(generateAdapter = true)
data class RegistryServerList(
    val servers: List<RegistryServerEntry> = emptyList(),
    val metadata: RegistryMetadata? = null
)

@JsonClass(generateAdapter = true)
data class RegistryServerEntry(
    val server: RegistryServerDetail,
    @param:Json(name = "_meta") val meta: RegistryMeta? = null
)

@JsonClass(generateAdapter = true)
data class RegistryServerDetail(
    @param:Json(name = "\$schema") val schema: String? = null,
    val name: String,
    val description: String? = null,
    val title: String? = null,
    val version: String? = null,
    val repository: RegistryRepository? = null,
    val remotes: List<RegistryRemote> = emptyList(),
    val packages: List<RegistryPackage> = emptyList()
)

@JsonClass(generateAdapter = true)
data class RegistryRepository(
    val url: String? = null,
    val source: String? = null
)

@JsonClass(generateAdapter = true)
data class RegistryRemote(
    val type: String,
    val url: String,
    val headers: List<RegistryRemoteHeader> = emptyList()
)

@JsonClass(generateAdapter = true)
data class RegistryRemoteHeader(
    val name: String,
    val value: String? = null,
    val description: String? = null,
    val isRequired: Boolean? = null,
    val isSecret: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class RegistryPackage(
    val registryType: String? = null,
    val identifier: String? = null,
    val transport: RegistryTransport? = null
)

@JsonClass(generateAdapter = true)
data class RegistryTransport(
    val type: String? = null,
    val url: String? = null
)

@JsonClass(generateAdapter = true)
data class RegistryMetadata(
    val count: Int? = null,
    val cursor: String? = null,
    val nextCursor: String? = null,
    val hasMore: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class RegistryMeta(
    @param:Json(name = "io.modelcontextprotocol.registry/official") val official: RegistryOfficialMeta? = null
)

@JsonClass(generateAdapter = true)
data class RegistryOfficialMeta(
    val status: String? = null,
    val publishedAt: String? = null,
    val updatedAt: String? = null,
    val isLatest: Boolean? = null
)

/**
 * Read-only client for the official MCP Registry (https://registry.modelcontextprotocol.io).
 * Supports listing/searching servers and fetching server details.
 */
class McpRegistryClient {
    private val moshi = Moshi.Builder().build()
    private val listAdapter = moshi.adapter(RegistryServerList::class.java)
    private val detailAdapter = moshi.adapter(RegistryServerEntry::class.java)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Searches the registry. Returns the page and the next cursor (if any). */
    suspend fun searchServers(
        query: String = "",
        limit: Int = 30,
        cursor: String? = null
    ): Result<Pair<List<RegistryServerDetail>, String?>> = withContext(Dispatchers.IO) {
        try {
            val params = buildList {
                add("limit=$limit")
                if (query.isNotBlank()) add("search=${URLEncoder.encode(query, "UTF-8")}")
                if (!cursor.isNullOrBlank()) add("cursor=${URLEncoder.encode(cursor, "UTF-8")}")
            }.joinToString("&")

            val url = "$REGISTRY_BASE_URL/v0.1/servers?$params"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use Result.failure<Pair<List<RegistryServerDetail>, String?>>(
                        IllegalStateException("Registry HTTP ${response.code}")
                    )
                }
                val bodyString = response.body?.string()
                    ?: return@use Result.success(emptyList<RegistryServerDetail>() to null)
                val parsed = listAdapter.fromJson(bodyString)
                    ?: return@use Result.success(emptyList<RegistryServerDetail>() to null)

                val details = parsed.servers.map { it.server }
                val nextCursor = parsed.metadata?.nextCursor ?: parsed.metadata?.cursor
                Result.success(details to nextCursor)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Fetches the latest version details for a server by its fully-qualified name. */
    suspend fun getServerDetail(serverName: String): Result<RegistryServerDetail> = withContext(Dispatchers.IO) {
        try {
            val encodedName = URLEncoder.encode(serverName, "UTF-8")
            val url = "$REGISTRY_BASE_URL/v0.1/servers/$encodedName/versions/latest"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use Result.failure<RegistryServerDetail>(IllegalStateException("Registry HTTP ${response.code}"))
                }
                val bodyString = response.body?.string()
                    ?: return@use Result.failure<RegistryServerDetail>(IllegalStateException("Empty response"))
                val parsed = detailAdapter.fromJson(bodyString)
                    ?: return@use Result.failure<RegistryServerDetail>(IllegalStateException("Invalid JSON"))
                Result.success(parsed.server)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/** Picks the best Streamable-HTTP URL from a registry server detail. */
fun RegistryServerDetail.resolveStreamableHttpUrl(): String? {
    val remoteUrl = remotes
        .firstOrNull { it.type.equals("streamable-http", ignoreCase = true) || it.type.equals("http", ignoreCase = true) }
        ?.url
    if (remoteUrl != null) return remoteUrl

    return packages
        .firstOrNull { it.transport?.type.equals("streamable-http", ignoreCase = true) }
        ?.transport?.url
}

/** Infers a server type label from the registry server name/description. */
fun RegistryServerDetail.inferServerType(): String {
    val text = "${name} ${description ?: ""} ${title ?: ""}".lowercase()
    return when {
        text.contains("github") -> "GitHub"
        text.contains("postgres") || text.contains("database") || text.contains("sql") -> "Database"
        text.contains("browser") || text.contains("puppeteer") || text.contains("playwright") -> "Browser"
        text.contains("search") || text.contains("brave") || text.contains("google") || text.contains("searx") -> "Search"
        text.contains("docker") || text.contains("container") -> "Docker"
        text.contains("slack") -> "Slack"
        text.contains("filesystem") || text.contains("file") -> "Filesystem"
        else -> "Gateway"
    }
}
