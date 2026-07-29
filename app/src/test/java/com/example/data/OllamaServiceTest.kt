package com.example.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

// Robolectric (not plain JUnit): the HTTP-error path below calls android.util.Log.e, which
// throws in a bare JVM unit test unless a real Android Log implementation is present.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class OllamaServiceTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun generateStreaming_accumulatesNdjsonChunksAndStopsAtDone() = runBlocking {
        // Real Ollama /api/generate streaming responses are newline-delimited JSON objects,
        // each carrying a partial `response` fragment, with a final `done: true` chunk.
        val body = listOf(
            """{"response":"Hel","done":false}""",
            """{"response":"lo, ","done":false}""",
            """{"response":"world!","done":false}""",
            """{"response":"","done":true}"""
        ).joinToString("\n")

        server.enqueue(MockResponse().setBody(body).setResponseCode(200))

        val received = mutableListOf<String>()
        val result = OllamaServiceDefault.generateStreaming(
            nodeUrl = server.url("/").toString(),
            modelName = "llama3",
            prompt = "say hello",
            onToken = { partial -> received.add(partial) }
        )

        assertEquals("Hello, world!", result)
        // onToken fires once per NDJSON line, including the final `done` chunk (which carries no
        // new text, so the cumulative value repeats) -- callers throttle/dedupe as needed.
        assertEquals(listOf("Hel", "Hello, ", "Hello, world!", "Hello, world!"), received)
    }

    @Test
    fun generateStreaming_returnsNullOnHttpError() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = OllamaServiceDefault.generateStreaming(
            nodeUrl = server.url("/").toString(),
            modelName = "llama3",
            prompt = "say hello",
            onToken = {}
        )

        assertEquals(null, result)
    }
}
