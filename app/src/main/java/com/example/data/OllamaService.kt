package com.example.data

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.squareup.moshi.Moshi
import com.squareup.moshi.JsonClass
import java.util.concurrent.TimeUnit
import android.util.Log

// Ollama Cloud (https://ollama.com) hosts the same /api/generate & /api/tags surface as a
// local Ollama server, but requires "Authorization: Bearer <key>" auth. Nodes pointed at
// ollama.com use the app-wide BuildConfig key unless they were given their own per-node key.
private val OLLAMA_CLOUD_HOSTS = setOf("ollama.com", "api.ollama.com")

fun resolveApiKeyForNode(node: OllamaNode): String? {
    if (!node.apiKey.isNullOrBlank()) return node.apiKey
    val host = try {
        java.net.URI(node.url).host
    } catch (e: Exception) {
        null
    }
    if (host != null && OLLAMA_CLOUD_HOSTS.any { host == it || host.endsWith(".$it") }) {
        val bundledKey = BuildConfig.OLLAMA_API_KEY
        if (bundledKey.isNotBlank() && bundledKey != "MY_OLLAMA_API_KEY") {
            return bundledKey
        }
    }
    return null
}

@JsonClass(generateAdapter = true)
data class OllamaRequest(
    val model: String,
    val prompt: String,
    val system: String? = null,
    val stream: Boolean = false
)

@JsonClass(generateAdapter = true)
data class OllamaResponse(
    val response: String? = null
)

@JsonClass(generateAdapter = true)
data class OllamaStreamChunk(
    val response: String? = null,
    val done: Boolean = false
)

interface OllamaService {
    suspend fun generate(
        nodeUrl: String,
        modelName: String,
        prompt: String,
        systemPrompt: String? = null,
        apiKey: String? = null
    ): String?

    /**
     * Streaming variant of [generate]: invokes [onToken] with the *cumulative* text generated so
     * far each time a new NDJSON chunk arrives, and returns the full text once the model reports
     * done. Returns null on any transport failure, same as [generate].
     */
    suspend fun generateStreaming(
        nodeUrl: String,
        modelName: String,
        prompt: String,
        systemPrompt: String? = null,
        apiKey: String? = null,
        onToken: suspend (partial: String) -> Unit
    ): String?

    suspend fun pingAndFetchModels(nodeUrl: String, apiKey: String? = null): Pair<Boolean, List<String>>
}

object OllamaServiceDefault : OllamaService {
    private val moshi = Moshi.Builder().build()
    private val jsonAdapter = moshi.adapter(OllamaRequest::class.java)
    private val responseAdapter = moshi.adapter(OllamaResponse::class.java)
    private val streamChunkAdapter = moshi.adapter(OllamaStreamChunk::class.java)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun generate(
        nodeUrl: String,
        modelName: String,
        prompt: String,
        systemPrompt: String?,
        apiKey: String?
    ): String? = withContext(Dispatchers.IO) {
        val cleanUrl = if (nodeUrl.endsWith("/")) nodeUrl else "$nodeUrl/"
        val apiUrl = "${cleanUrl}api/generate"

        // Remove 'ollama/' prefix if present
        val cleanModel = modelName.substringAfter("ollama/")

        val requestBodyObj = OllamaRequest(
            model = cleanModel,
            prompt = prompt,
            system = systemPrompt,
            stream = false
        )

        try {
            val jsonString = jsonAdapter.toJson(requestBodyObj)
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = jsonString.toRequestBody(mediaType)

            val requestBuilder = Request.Builder()
                .url(apiUrl)
                .post(body)
            if (!apiKey.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("OllamaService", "Error response: ${response.code}")
                    return@withContext null
                }
                val bodyString = response.body?.string() ?: return@withContext null
                val resObj = responseAdapter.fromJson(bodyString)
                return@withContext resObj?.response
            }
        } catch (e: Exception) {
            Log.e("OllamaService", "Ollama connection failed: ${e.localizedMessage}")
            return@withContext null
        }
    }

    override suspend fun generateStreaming(
        nodeUrl: String,
        modelName: String,
        prompt: String,
        systemPrompt: String?,
        apiKey: String?,
        onToken: suspend (String) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        val cleanUrl = if (nodeUrl.endsWith("/")) nodeUrl else "$nodeUrl/"
        val apiUrl = "${cleanUrl}api/generate"
        val cleanModel = modelName.substringAfter("ollama/")

        val requestBodyObj = OllamaRequest(
            model = cleanModel,
            prompt = prompt,
            system = systemPrompt,
            stream = true
        )

        try {
            val jsonString = jsonAdapter.toJson(requestBodyObj)
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = jsonString.toRequestBody(mediaType)

            val requestBuilder = Request.Builder()
                .url(apiUrl)
                .post(body)
            if (!apiKey.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("OllamaService", "Error response: ${response.code}")
                    return@withContext null
                }
                val source = response.body?.source() ?: return@withContext null
                val accumulator = StringBuilder()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val chunk = try {
                        streamChunkAdapter.fromJson(line)
                    } catch (e: Exception) {
                        null
                    } ?: continue
                    chunk.response?.let { accumulator.append(it) }
                    onToken(accumulator.toString())
                    if (chunk.done) break
                }
                accumulator.toString()
            }
        } catch (e: Exception) {
            Log.e("OllamaService", "Ollama streaming failed: ${e.localizedMessage}")
            null
        }
    }

    private val tagsResponseAdapter = moshi.adapter(OllamaTagsResponse::class.java)

    override suspend fun pingAndFetchModels(nodeUrl: String, apiKey: String?): Pair<Boolean, List<String>> = withContext(Dispatchers.IO) {
        val cleanUrl = if (nodeUrl.endsWith("/")) nodeUrl else "$nodeUrl/"
        val apiUrl = "${cleanUrl}api/tags"

        try {
            val requestBuilder = Request.Builder()
                .url(apiUrl)
                .get()
            if (!apiKey.isNullOrBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext false to emptyList()
                }
                val bodyString = response.body?.string() ?: return@withContext true to emptyList()
                val tagsResponse = tagsResponseAdapter.fromJson(bodyString)
                val modelNames = tagsResponse?.models?.map { it.name } ?: emptyList()
                return@withContext true to modelNames
            }
        } catch (e: Exception) {
            Log.e("OllamaService", "Ping/Fetch failed for $nodeUrl: ${e.localizedMessage}")
            return@withContext false to emptyList()
        }
    }
}

@JsonClass(generateAdapter = true)
data class OllamaTagModel(val name: String)

@JsonClass(generateAdapter = true)
data class OllamaTagsResponse(val models: List<OllamaTagModel>)
