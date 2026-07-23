package com.example.data

/** Seam over the Ollama node-pool router so tests can drive deterministic LLM routing without
 *  network I/O. Single entry point for every LLM call (agent-os/standards/backend/swarm-engine-llm-pool). */
interface LlmRouterInterface {
    /** Generate using a specific agent's persona (system prompt + skills context + preferred model). */
    suspend fun generateForAgent(
        agent: Agent,
        prompt: String,
        preferCloud: Boolean = false,
        onToken: (suspend (String) -> Unit)? = null
    ): String

    /** Generate with a freeform system prompt (consensus moderator, voice routing, chat, sandbox, self-heal). */
    suspend fun generateFreeform(prompt: String, systemPrompt: String, preferCloud: Boolean = false): String

    /** Streaming freeform variant (consensus-vote scoring). */
    suspend fun generateFreeformStreaming(prompt: String, systemPrompt: String, onToken: suspend (String) -> Unit): String
}
