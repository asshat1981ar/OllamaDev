package com.example.data

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

object OllamaService {
    private val moshi = Moshi.Builder().build()
    private val jsonAdapter = moshi.adapter(OllamaRequest::class.java)
    private val responseAdapter = moshi.adapter(OllamaResponse::class.java)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun generate(
        nodeUrl: String,
        modelName: String,
        prompt: String,
        systemPrompt: String? = null
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

            val request = Request.Builder()
                .url(apiUrl)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
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
}
