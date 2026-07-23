package com.example.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class LlmRouter(
    private val ollamaService: OllamaService,
    private val nodeDao: OllamaNodeDao,
    private val skillDao: ClaudeSkillDao,
    private val securePrefs: SecurePrefsInterface,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : LlmRouterInterface {

    override suspend fun generateForAgent(
        agent: Agent,
        prompt: String,
        preferCloud: Boolean,
        onToken: (suspend (String) -> Unit)?
    ): String {
        val systemPromptWithSkills = agent.systemPrompt + buildSkillsContext()
        return generateFromFallbackPool(prompt, systemPromptWithSkills, preferredModelName = agent.modelName, preferCloud = preferCloud, onToken = onToken)
    }

    override suspend fun generateFreeform(prompt: String, systemPrompt: String, preferCloud: Boolean): String =
        generateFromFallbackPool(prompt, systemPrompt, preferredModelName = null, preferCloud = preferCloud)

    override suspend fun generateFreeformStreaming(prompt: String, systemPrompt: String, onToken: suspend (String) -> Unit): String =
        generateFromFallbackPool(prompt, systemPrompt, preferredModelName = null, onToken = onToken)

    private suspend fun buildSkillsContext(): String {
        val activeSkills = skillDao.getAllSkillsSync().filter { it.isEnabled }
        return if (activeSkills.isNotEmpty()) {
            "\n[AVAILABLE SYSTEM TOOLS & MCP SKILLS]\n" +
            activeSkills.joinToString("\n") { skill ->
                val invokeName = skill.sourceToolName ?: skill.name
                "- [${skill.category}] Tool: ${skill.name} (invoke as '$invokeName'; Requires MCP Link: ${skill.requiredMcpServerType}). Description: ${skill.description}. Example Usage: ${skill.usageExample}"
            } + "\nTo actually invoke one of these tools, emit a line in this exact format: " +
            "MCP_CALL: <tool name> | <json arguments object>. " +
            "The call only succeeds if the tool's MCP server is currently Connected; otherwise it will fail for real. " +
            "Do not fabricate or narrate tool output yourself -- only the MCP_CALL directive produces real results.\n"
        } else {
            ""
        }
    }

    /**
     * Selects an online Ollama node and generates a response. When [preferredModelName] is given,
     * prefers a node whose available models match it; otherwise (or if no match exists) falls back
     * to the lowest-latency online node running any model -- there is no Gemini fallback anymore,
     * so this pool is the only path to a response. When [preferCloud] is true (used by the
     * agentic-loop harness, which needs stronger planning/tool-use reasoning), the online pool is
     * narrowed to cloud-gateway nodes first, falling back to the full online pool if none are
     * online -- never a hard failure just because the cloud gateway happens to be offline.
     */
    private suspend fun generateFromFallbackPool(
        prompt: String,
        systemPrompt: String,
        preferredModelName: String? = null,
        preferCloud: Boolean = false,
        onToken: (suspend (String) -> Unit)? = null
    ): String {
        try {
            val allOnlineNodes = nodeDao.getAllNodesSync().filter { it.status == "Online" }
            val onlineNodes = if (preferCloud) {
                allOnlineNodes.filter { it.isCloudGatewayNode() }.ifEmpty { allOnlineNodes }
            } else {
                allOnlineNodes
            }

            val modelMatchedNode = preferredModelName?.let { model ->
                onlineNodes.filter { node ->
                    node.availableModels.split(",").map { it.trim().lowercase() }.any {
                        it.contains(model.lowercase()) || model.lowercase().contains(it)
                    } || model.lowercase().contains(node.name.lowercase())
                }.minByOrNull { if (it.latencyMs > 0) it.latencyMs else 999999 }
            }
            val fallbackNode = modelMatchedNode
                ?: onlineNodes.minByOrNull { if (it.latencyMs > 0) it.latencyMs else 999999 }
                ?: return "Error: no online Ollama node available to service this request. Configure at least one node in Manage > Nodes."

            // fallbackNode == modelMatchedNode can only be true when preferredModelName was
            // non-null (that's the only way modelMatchedNode gets set), so it's safe to use here.
            val effectiveModel = if (fallbackNode == modelMatchedNode) {
                preferredModelName!!
            } else {
                fallbackNode.availableModels.split(",").firstOrNull()?.trim() ?: (preferredModelName ?: "llama3")
            }

            val response = if (onToken != null) {
                ollamaService.generateStreaming(
                    nodeUrl = fallbackNode.url,
                    modelName = effectiveModel,
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    apiKey = resolveApiKeyForNode(fallbackNode),
                    onToken = onToken
                )
            } else {
                ollamaService.generate(
                    nodeUrl = fallbackNode.url,
                    modelName = effectiveModel,
                    prompt = prompt,
                    systemPrompt = systemPrompt,
                    apiKey = resolveApiKeyForNode(fallbackNode)
                )
            }
            return response?.takeIf { it.isNotEmpty() }
                ?: "Error: node '${fallbackNode.name}' returned an empty response."
        } catch (e: Exception) {
            android.util.Log.e("SwarmEngine", "Ollama routing error: ${e.localizedMessage}")
            return "Error: Ollama routing failed: ${e.localizedMessage}"
        }
    }

    /** Name/URL heuristic identifying the seeded "Ollama Cloud Gateway" node (or any node
     *  pointed at ollama.com), used by [preferCloud] routing -- no dedicated schema field. */
    private fun OllamaNode.isCloudGatewayNode(): Boolean =
        name.equals("Ollama Cloud Gateway", ignoreCase = true) || url.contains("ollama.com", ignoreCase = true)
}

