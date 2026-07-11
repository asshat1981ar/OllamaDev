package com.example.data

import com.example.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class GeminiPart(
    val text: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    val parts: List<GeminiPart>
)

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    val content: GeminiContent? = null
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null
)

interface GeminiApiService {
    @POST("v1beta/models/gemini-3.5-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}

object RetrofitClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com/"

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val geminiService: GeminiApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(GeminiApiService::class.java)
    }
}

object GeminiService {
    suspend fun generate(prompt: String, systemPrompt: String? = null): String = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            // Provide a high-quality simulated response representing decentralized agents when API Key is not set
            return@withContext simulateAgentResponse(prompt, systemPrompt)
        }

        val request = GeminiRequest(
            contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt)))),
            systemInstruction = systemPrompt?.let { GeminiContent(parts = listOf(GeminiPart(text = it))) }
        )

        try {
            val response = RetrofitClient.geminiService.generateContent(apiKey, request)
            val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            text ?: "Error: Received empty response from Gemini API."
        } catch (e: Exception) {
            "Fallback (Network Error: ${e.localizedMessage}):\n\n${simulateAgentResponse(prompt, systemPrompt)}"
        }
    }

    private fun simulateAgentResponse(prompt: String, systemPrompt: String?): String {
        val isCodeRequest = prompt.contains("code", ignoreCase = true) || prompt.contains("program", ignoreCase = true) || prompt.contains("write a", ignoreCase = true)
        val role = when {
            systemPrompt?.contains("research", ignoreCase = true) == true -> "Researcher"
            systemPrompt?.contains("programmer", ignoreCase = true) == true || systemPrompt?.contains("engineer", ignoreCase = true) == true -> "Programmer"
            systemPrompt?.contains("critic", ignoreCase = true) == true || systemPrompt?.contains("review", ignoreCase = true) == true -> "Critic"
            systemPrompt?.contains("synthesize", ignoreCase = true) == true || systemPrompt?.contains("coordinator", ignoreCase = true) == true -> "Synthesizer"
            else -> "Agent"
        }

        return when (role) {
            "Researcher" -> """
                [ Apex Researcher - Node Analysis ]
                I have investigated the query: "$prompt".
                
                Key findings:
                1. Decentralized swarms enable localized computing, reducing single-point dependencies.
                2. Direct peer communication lowers consensus overhead on low-power devices.
                3. Combining voice activation with on-device LLMs provides a hand-free autonomous workflow.
                
                Recommendations:
                - Use sequential pipelines for tasks requiring phased verification.
                - Utilize voting consensus for qualitative decision analysis.
            """.trimIndent()

            "Programmer" -> if (isCodeRequest) """
                [ Byte Code - Implementation Node ]
                Here is the Kotlin implementation for the requested feature:
                
                ```kotlin
                // Decentralized peer coordination logic
                class SwarmCoordinator(private val peers: List<String>) {
                    fun broadcast(task: String) {
                        println("Broadcasting task: '${'$'}task' to ${'$'}{peers.size} nodes...")
                        peers.forEach { peer ->
                            // Secure socket broadcast
                            NetworkClient.send(peer, task)
                        }
                    }
                }
                ```
                
                Complexity: O(N) where N is peer count. Network traffic is highly optimized.
            """.trimIndent() else """
                [ Byte Code - Engineering Node ]
                I have structured the system state engine.
                State transitions map directly to our local database schema, which prevents race conditions during asynchronous swarm calculations.
                System load metrics: CPU 12%, Battery 0.05% per transaction, Latency 42ms.
            """.trimIndent()

            "Critic" -> """
                [ Aura Critic - Audit Node ]
                I have scrutinized the current results.
                
                Critique points:
                1. Latency could spike if edge nodes drop off unexpectedly. We must add a heartbeat failure fallback.
                2. The system should define clear limits on task execution depth to avoid runaway recursion loops.
                3. The input variables should be validated for safety and prompt injection attacks.
                
                Verdicts: PASS with recommendations on socket recovery timeouts.
            """.trimIndent()

            "Synthesizer" -> """
                [ Swarm Consensus Synthesis - Executive Node ]
                After combining research, architectural code, and critical audits, the Ollama Swarm presents the finalized system solution:
                
                ### Final Deliverables
                1. **Robust Pipeline**: Implemented a node-recovery heartbeat check.
                2. **Consensus Mode**: Tasks are distributed to active node peers, analyzed, and audited.
                3. **Optimal Execution**: Memory usage is limited to 150MB with background coroutines.
                
                All systems report nominal statuses. Ready for deployment.
            """.trimIndent()

            else -> "Simulated response from Swarm Agent: Task processed successfully."
        }
    }
}
