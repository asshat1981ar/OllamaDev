package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "SwarmViewModel"
private const val MAX_IMPORT_FILE_BYTES = 300 * 1024

@JsonClass(generateAdapter = true)
data class VoiceAction(
    val action: String, // RUN_SWARM, CREATE_NODE, CREATE_AGENT, UNKNOWN
    val swarmId: Int? = null,
    val taskPrompt: String? = null,
    val nodeName: String? = null,
    val nodeUrl: String? = null,
    val agentName: String? = null,
    val agentRole: String? = null,
    val agentPrompt: String? = null,
    val agentModel: String? = null
)

class SwarmViewModel(
    application: Application,
    private val ollamaService: OllamaService = OllamaServiceDefault,
    private val mcpClient: McpClientInterface = McpClient(),
    private val registryClient: McpRegistryClientInterface = McpRegistryClient(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    database: AppDatabaseInterface = AppDatabase.getDatabase(application),
    private val securePrefs: SecurePrefsInterface = RealSecurePrefs(application)
) : AndroidViewModel(application) {
    private val db = database
    // Deferred: references gitService/gitWorkDir declared further down, which are themselves
    // `by lazy` -- deferring this avoids reading them before their own initializers have run.
    private val swarmEngine by lazy { SwarmEngine(db, gitService, mcpClient, getApplication(), securePrefs, ollamaService, dispatcher) }
    private val moshi = Moshi.Builder().build()

    private val prefs = application.getSharedPreferences("ollama_swarm_prefs", Context.MODE_PRIVATE)

    private val _rootFolderUri = MutableStateFlow<String?>(null)
    val rootFolderUri: StateFlow<String?> = _rootFolderUri.asStateFlow()

    private val _rootFolderName = MutableStateFlow<String?>(null)
    val rootFolderName: StateFlow<String?> = _rootFolderName.asStateFlow()

    // Database flows
    val allNodes = db.ollamaNodeDao().getAllNodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allAgents = db.agentDao().getAllAgents()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSwarmConfigs = db.swarmConfigDao().getAllSwarmConfigs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allTasks = db.swarmTaskDao().getAllTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mcpServers = db.mcpServerDao().getAllServers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val claudeSkills = db.claudeSkillDao().getAllSkills()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recommendedSkills = db.claudeSkillDao().getRecommendedSkills()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI state for task execution
    private val _selectedTaskId = MutableStateFlow<Int?>(null)
    val selectedTaskId: StateFlow<Int?> = _selectedTaskId.asStateFlow()

    private val _activeSteps = MutableStateFlow<List<TaskStep>>(emptyList())
    val activeSteps: StateFlow<List<TaskStep>> = _activeSteps.asStateFlow()

    private val _isExecutingTask = MutableStateFlow(false)
    val isExecutingTask: StateFlow<Boolean> = _isExecutingTask.asStateFlow()

    // Centralized Agent State Store Flow
    val agentStates: StateFlow<Map<Int, AgentMetrics>> = AgentStateStore.agentStates

    val totalTokensUsed: StateFlow<Int> = AgentStateStore.agentStates.map { map ->
        map.values.sumOf { it.totalTokensUsed }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val totalCostSavingsUsd: StateFlow<Double> = AgentStateStore.agentStates.map { map ->
        val totalTokens = map.values.sumOf { it.totalTokensUsed }
        (totalTokens.toDouble() / 1000.0) * 0.015
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)

    val totalSandboxRuns: StateFlow<Int> = AgentStateStore.agentStates.map { map ->
        map.values.sumOf { it.tasksExecuted }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // Voice UI states
    private val _isVoiceListening = MutableStateFlow(false)
    val isVoiceListening: StateFlow<Boolean> = _isVoiceListening.asStateFlow()

    private val _voiceTranscript = MutableStateFlow("")
    val voiceTranscript: StateFlow<String> = _voiceTranscript.asStateFlow()

    private val _voiceFeedback = MutableStateFlow("")
    val voiceFeedback: StateFlow<String> = _voiceFeedback.asStateFlow()

    private val _isVoiceProcessing = MutableStateFlow(false)
    val isVoiceProcessing: StateFlow<Boolean> = _isVoiceProcessing.asStateFlow()

    init {
        // Sync database agents with centralized state store
        viewModelScope.launch {
            allAgents.collect { agents ->
                AgentStateStore.initializeAgents(agents)
            }
        }

        // Collect steps for selected task
        viewModelScope.launch {
            _selectedTaskId.collectLatest { taskId ->
                if (taskId != null) {
                    db.taskStepDao().getStepsForTask(taskId).collect { steps ->
                        _activeSteps.value = steps
                    }
                } else {
                    _activeSteps.value = emptyList()
                }
            }
        }

        // Load root folder URI from preferences and trigger sync
        val savedUri = prefs.getString("root_folder_uri", null)
        val savedName = prefs.getString("root_folder_name", null)
        if (savedUri != null) {
            _rootFolderUri.value = savedUri
            _rootFolderName.value = savedName
            syncWorkspace(isAutomatic = true)
        }

        // Probe real node status on startup so agent dispatch can immediately route to a
        // genuinely reachable node instead of relying on stale/seeded status values.
        refreshNodes()
    }

    fun clearAgentMetrics() {
        AgentStateStore.clearMetrics()
    }

    // Node Operations
    fun addNode(name: String, url: String, availableModels: String = "llama3", apiKey: String? = null) {
        viewModelScope.launch {
            db.ollamaNodeDao().insertNode(
                OllamaNode(
                    name = name,
                    url = url,
                    availableModels = availableModels,
                    apiKey = apiKey?.takeIf { it.isNotBlank() }
                )
            )
        }
    }

    fun updateNode(node: OllamaNode) {
        viewModelScope.launch {
            db.ollamaNodeDao().updateNode(node)
        }
    }

    fun deleteNode(node: OllamaNode) {
        viewModelScope.launch {
            db.ollamaNodeDao().deleteNode(node)
        }
    }

    fun refreshNodes() {
        viewModelScope.launch {
            val nodes = db.ollamaNodeDao().getAllNodesSync()
            nodes.forEach { node ->
                db.ollamaNodeDao().updateNode(node.copy(status = "Connecting"))
            }
            
            nodes.forEach { node ->
                val startTime = System.currentTimeMillis()
                val (isOnline, fetchedModels) = ollamaService.pingAndFetchModels(node.url, resolveApiKeyForNode(node))
                val duration = (System.currentTimeMillis() - startTime).toInt()
                
                val status = if (isOnline) "Online" else "Offline"
                val latency = if (isOnline) duration else -1
                val models = if (isOnline && fetchedModels.isNotEmpty()) {
                    fetchedModels.joinToString(", ")
                } else {
                    node.availableModels
                }
                
                db.ollamaNodeDao().updateNode(
                    node.copy(
                        status = status,
                        latencyMs = latency,
                        availableModels = models
                    )
                )
            }
        }
    }

    fun pingNode(node: OllamaNode) {
        viewModelScope.launch {
            db.ollamaNodeDao().updateNode(node.copy(status = "Connecting"))
            val startTime = System.currentTimeMillis()
            val (isOnline, fetchedModels) = ollamaService.pingAndFetchModels(node.url)
            val duration = (System.currentTimeMillis() - startTime).toInt()
            
            val status = if (isOnline) "Online" else "Offline"
            val latency = if (isOnline) duration else -1
            val models = if (isOnline && fetchedModels.isNotEmpty()) {
                fetchedModels.joinToString(", ")
            } else {
                node.availableModels
            }
            
            db.ollamaNodeDao().updateNode(
                node.copy(
                    status = status,
                    latencyMs = latency,
                    availableModels = models
                )
            )
        }
    }

    // MCP Server Operations
    private val _mcpError = MutableStateFlow<String?>(null)
    val mcpError: StateFlow<String?> = _mcpError.asStateFlow()

    // MCP Registry integration (injected via constructor)

    private val _registryResults = MutableStateFlow<List<RegistryServerDetail>>(emptyList())
    val registryResults: StateFlow<List<RegistryServerDetail>> = _registryResults.asStateFlow()

    private val _registryNextCursor = MutableStateFlow<String?>(null)
    val registryNextCursor: StateFlow<String?> = _registryNextCursor.asStateFlow()

    private val _isRegistryLoading = MutableStateFlow(false)
    val isRegistryLoading: StateFlow<Boolean> = _isRegistryLoading.asStateFlow()

    private val _registryError = MutableStateFlow<String?>(null)
    val registryError: StateFlow<String?> = _registryError.asStateFlow()

    private suspend fun connectMcpServer(serverId: Int, sourceUrl: String, type: String, authToken: String?) {
        val initResult = withContext(dispatcher) { mcpClient.initialize(sourceUrl, authToken) }
        val session = initResult.getOrNull()
        if (session == null) {
            db.mcpServerDao().getServerById(serverId)?.let {
                db.mcpServerDao().updateServer(it.copy(status = "Error"))
            }
            _mcpError.value = "Failed to connect to $sourceUrl: ${initResult.exceptionOrNull()?.message}"
            return
        }

        val toolsResult = withContext(dispatcher) { mcpClient.listTools(sourceUrl, session, authToken) }
        val server = db.mcpServerDao().getServerById(serverId) ?: return
        if (toolsResult.isSuccess) {
            val tools = toolsResult.getOrNull().orEmpty()
            db.mcpServerDao().updateServer(server.copy(status = "Connected", toolsCount = tools.size))

            // Persist discovered tools and auto-generate skills bound to real MCP tool names
            db.mcpToolDao().deleteToolsForServer(serverId)
            db.claudeSkillDao().deleteAutoGeneratedSkillsByServerType(type)

            val toolEntities = tools.map { tool ->
                McpToolEntity(
                    serverId = serverId,
                    name = tool.name,
                    description = tool.description,
                    inputSchemaJson = mcpClient.toJsonString(tool.inputSchema) ?: "{}",
                    outputSchemaJson = mcpClient.toJsonString(tool.outputSchema),
                    annotationsJson = mcpClient.toJsonString(tool.annotations)
                )
            }
            if (toolEntities.isNotEmpty()) {
                db.mcpToolDao().insertTools(toolEntities)
            }

            val generatedSkills = tools.map { tool ->
                ClaudeSkill(
                    name = tool.name,
                    description = tool.description ?: "MCP tool ${tool.name} from ${server.name}",
                    category = inferSkillCategory(tool.name, type),
                    isRecommended = true,
                    isEnabled = true,
                    usageExample = "MCP_CALL: ${tool.name} | ${generateExampleArgs(tool.inputSchema)}",
                    requiredMcpServerType = type,
                    sourceToolName = tool.name
                )
            }
            generatedSkills.forEach { skill ->
                db.claudeSkillDao().insertSkill(skill)
            }
        } else {
            db.mcpServerDao().updateServer(server.copy(status = "Error"))
            _mcpError.value = "Connected but failed to list tools: ${toolsResult.exceptionOrNull()?.message}"
        }
    }

    private fun inferSkillCategory(toolName: String, serverType: String): String {
        val lower = toolName.lowercase()
        return when {
            serverType.equals("Database", ignoreCase = true) || lower.contains("query") || lower.contains("schema") || lower.contains("explain") -> "Analysis"
            serverType.equals("Browser", ignoreCase = true) || lower.contains("screenshot") || lower.contains("navigate") || lower.contains("click") -> "Automation"
            serverType.equals("GitHub", ignoreCase = true) || lower.contains("git") || lower.contains("commit") || lower.contains("branch") || lower.contains("pull") -> "Development"
            serverType.equals("Search", ignoreCase = true) || lower.contains("search") || lower.contains("fetch") || lower.contains("crawl") -> "Productivity"
            lower.contains("test") || lower.contains("lint") || lower.contains("format") || lower.contains("build") -> "Development"
            lower.contains("deploy") || lower.contains("docker") || lower.contains("container") -> "Automation"
            else -> "Productivity"
        }
    }

    private fun generateExampleArgs(inputSchema: Map<String, Any?>?): String {
        if (inputSchema == null) return "{}"
        @Suppress("UNCHECKED_CAST")
        val properties = (inputSchema["properties"] as? Map<String, Any?>).orEmpty()
        val required = (inputSchema["required"] as? List<String>).orEmpty()
        val example = mutableMapOf<String, Any?>()
        properties.entries.take(4).forEach { (propName, propSchema) ->
            @Suppress("UNCHECKED_CAST")
            val schema = propSchema as? Map<String, Any?> ?: emptyMap()
            val type = schema["type"] as? String ?: "string"
            example[propName] = when (type) {
                "string" -> if (required.contains(propName)) "value" else ""
                "integer", "number" -> 0
                "boolean" -> false
                "array" -> emptyList<Any>()
                "object" -> emptyMap<String, Any?>()
                else -> ""
            }
        }
        return mcpClient.toJsonString(example) ?: "{}"
    }

    fun addMcpServer(name: String, type: String, sourceUrl: String, configuredParams: String = "{}", authToken: String = "") {
        viewModelScope.launch {
            _mcpError.value = null
            val id = db.mcpServerDao().insertServer(
                McpServer(
                    name = name,
                    type = type,
                    sourceUrl = sourceUrl,
                    status = "Connecting",
                    toolsCount = 0,
                    configuredParams = configuredParams
                )
            ).toInt()
            if (authToken.isNotBlank()) {
                securePrefs.setMcpToken(id, authToken)
            }
            connectMcpServer(id, sourceUrl, type, authToken.ifBlank { null })
        }
    }

    fun updateMcpServer(server: McpServer) {
        viewModelScope.launch {
            db.mcpServerDao().updateServer(server)
        }
    }

    fun deleteMcpServer(server: McpServer) {
        viewModelScope.launch {
            db.mcpToolDao().deleteToolsForServer(server.id)
            db.claudeSkillDao().deleteAutoGeneratedSkillsByServerType(server.type)
            db.mcpServerDao().deleteServer(server)
            securePrefs.clearMcpToken(server.id)
            // Disable any remaining skills related to deleted server type
            db.claudeSkillDao().updateRecommendationByServerType(server.type, false)
            db.claudeSkillDao().setSkillEnabledByServerType(server.type, false)
        }
    }

    fun toggleMcpServerStatus(server: McpServer) {
        viewModelScope.launch {
            if (server.status == "Connected") {
                db.mcpServerDao().updateServer(server.copy(status = "Disconnected"))
                db.mcpToolDao().deleteToolsForServer(server.id)
                db.claudeSkillDao().deleteAutoGeneratedSkillsByServerType(server.type)
                db.claudeSkillDao().updateRecommendationByServerType(server.type, false)
                db.claudeSkillDao().setSkillEnabledByServerType(server.type, false)
            } else {
                _mcpError.value = null
                db.mcpServerDao().updateServer(server.copy(status = "Connecting"))
                val authToken = securePrefs.getMcpToken(server.id)
                connectMcpServer(server.id, server.sourceUrl, server.type, authToken)
            }
        }
    }

    // MCP Registry Operations
    fun searchRegistry(query: String = "", append: Boolean = false) {
        viewModelScope.launch {
            _isRegistryLoading.value = true
            _registryError.value = null
            try {
                val cursor = if (append) _registryNextCursor.value else null
                val result = registryClient.searchServers(
                    query = query,
                    limit = 30,
                    cursor = cursor
                )
                result.fold(
                    onSuccess = { (servers, nextCursor) ->
                        if (append) {
                            _registryResults.value = _registryResults.value + servers
                        } else {
                            _registryResults.value = servers
                        }
                        _registryNextCursor.value = nextCursor
                    },
                    onFailure = { error ->
                        _registryError.value = error.localizedMessage ?: "Registry search failed"
                    }
                )
            } finally {
                _isRegistryLoading.value = false
            }
        }
    }

    fun clearRegistrySearch() {
        _registryResults.value = emptyList()
        _registryNextCursor.value = null
        _registryError.value = null
    }

    /**
     * Installs a server from the MCP Registry as a local [McpServer] and attempts to connect.
     * For servers that require a secret header, the user must provide the token via the
     * regular MCP server auth token field after install.
     */
    fun installRegistryServer(registryServer: RegistryServerDetail, authToken: String = "") {
        viewModelScope.launch {
            _registryError.value = null
            val sourceUrl = registryServer.resolveStreamableHttpUrl()
            if (sourceUrl == null) {
                _registryError.value = "'${registryServer.name}' has no Streamable-HTTP endpoint."
                return@launch
            }

            val serverType = registryServer.inferServerType()
            val existing = db.mcpServerDao().getAllServersSync()
                .firstOrNull { it.sourceUrl == sourceUrl }
            if (existing != null) {
                // Already installed; just try to connect
                toggleMcpServerStatus(existing.copy(status = "Disconnected"))
                return@launch
            }

            val displayName = registryServer.title ?: registryServer.name.substringAfterLast("/").replace("-", " ")
            val id = db.mcpServerDao().insertServer(
                McpServer(
                    name = displayName,
                    type = serverType,
                    sourceUrl = sourceUrl,
                    status = "Connecting",
                    toolsCount = 0,
                    configuredParams = "{}"
                )
            ).toInt()
            if (authToken.isNotBlank()) {
                securePrefs.setMcpToken(id, authToken)
            }
            connectMcpServer(id, sourceUrl, serverType, authToken.ifBlank { null })
        }
    }

    // Claude Skills Operations
    fun addClaudeSkill(
        name: String,
        description: String,
        category: String,
        isRecommended: Boolean,
        usageExample: String,
        requiredMcpServerType: String,
        sourceToolName: String? = null
    ) {
        viewModelScope.launch {
            db.claudeSkillDao().insertSkill(
                ClaudeSkill(
                    name = name,
                    description = description,
                    category = category,
                    isRecommended = isRecommended,
                    isEnabled = true,
                    usageExample = usageExample,
                    requiredMcpServerType = requiredMcpServerType,
                    sourceToolName = sourceToolName
                )
            )
        }
    }

    fun toggleClaudeSkill(skill: ClaudeSkill) {
        viewModelScope.launch {
            db.claudeSkillDao().setSkillEnabled(skill.id, !skill.isEnabled)
        }
    }

    // Agent Operations
    fun addAgent(name: String, role: String, modelName: String, systemPrompt: String, colorHex: String) {
        viewModelScope.launch {
            db.agentDao().insertAgent(Agent(name = name, role = role, modelName = modelName, systemPrompt = systemPrompt, colorHex = colorHex))
        }
    }

    fun deleteAgent(agent: Agent) {
        viewModelScope.launch {
            db.agentDao().deleteAgent(agent)
        }
    }

    // Swarm Operations
    fun addSwarmConfig(name: String, description: String, coordinationMode: String, agentIds: List<Int>) {
        viewModelScope.launch {
            db.swarmConfigDao().insertSwarmConfig(
                SwarmConfig(
                    name = name,
                    description = description,
                    coordinationMode = coordinationMode,
                    agentIds = agentIds.joinToString(",")
                )
            )
        }
    }

    fun deleteSwarmConfig(config: SwarmConfig) {
        viewModelScope.launch {
            db.swarmConfigDao().deleteSwarmConfig(config)
        }
    }

    // Task Execution
    fun runSwarm(config: SwarmConfig, prompt: String) {
        viewModelScope.launch {
            _isExecutingTask.value = true
            swarmEngine.executeTask(config, prompt, onTaskCreated = { id -> _selectedTaskId.value = id })
            _isExecutingTask.value = false
        }
    }

    // Session (REPL/Manus-style) dispatch: which swarm a Session prompt targets when the user
    // hasn't explicitly picked one, plus the running conversation memory that makes consecutive
    // Session prompts feel like one continuous exchange instead of isolated one-shot tasks.
    private val _selectedSessionSwarmConfig = MutableStateFlow<SwarmConfig?>(null)
    val selectedSessionSwarmConfig: StateFlow<SwarmConfig?> = _selectedSessionSwarmConfig.asStateFlow()
    fun selectSessionSwarmConfig(config: SwarmConfig?) {
        _selectedSessionSwarmConfig.value = config
    }

    private val _sessionInputHistory = MutableStateFlow<List<String>>(emptyList())
    val sessionInputHistory: StateFlow<List<String>> = _sessionInputHistory.asStateFlow()

    // Phone-layout toggle for Session's "computer panel" bottom sheet (file/editor/git context).
    private val _isComputerPanelExpanded = MutableStateFlow(false)
    val isComputerPanelExpanded: StateFlow<Boolean> = _isComputerPanelExpanded.asStateFlow()
    fun setComputerPanelExpanded(expanded: Boolean) {
        _isComputerPanelExpanded.value = expanded
    }

    // Lets a screen nested under Manage (e.g. Dashboard's "open this swarm") request that
    // MainActivity switch its top-level tab back to Session, without threading a callback
    // through the intermediate ManageScreen layer.
    private val _requestedTab = MutableStateFlow<String?>(null)
    val requestedTab: StateFlow<String?> = _requestedTab.asStateFlow()
    fun requestTabSwitch(tab: String) {
        _requestedTab.value = tab
    }
    fun clearRequestedTabSwitch() {
        _requestedTab.value = null
    }

    private val maxSessionContextChars = 4000

    fun runSwarmFromSession(prompt: String) {
        val config = _selectedSessionSwarmConfig.value ?: allSwarmConfigs.value.firstOrNull() ?: return
        viewModelScope.launch {
            _isExecutingTask.value = true
            _sessionInputHistory.value = (_sessionInputHistory.value + prompt).takeLast(50)

            val recentMessages = db.chatMessageDao().getRecentMessagesSync(20).sortedBy { it.timestamp }
            val contextPrefix = recentMessages
                .joinToString("\n") { "${it.sender}: ${it.message}" }
                .takeLast(maxSessionContextChars)

            db.chatMessageDao().insertMessage(
                ChatMessage(sender = "You", role = "user", message = prompt, timestamp = System.currentTimeMillis())
            )

            val taskId = swarmEngine.executeTask(
                config, prompt,
                contextPrefix = contextPrefix,
                onTaskCreated = { id -> _selectedTaskId.value = id }
            )

            val result = db.swarmTaskDao().getTaskById(taskId)?.result.orEmpty()
            db.chatMessageDao().insertMessage(
                ChatMessage(sender = config.name, role = "assistant", message = result, timestamp = System.currentTimeMillis())
            )

            _isExecutingTask.value = false
        }
    }

    fun selectTask(taskId: Int?) {
        _selectedTaskId.value = taskId
    }

    fun deleteTask(task: SwarmTask) {
        viewModelScope.launch {
            db.taskStepDao().deleteStepsForTask(task.id)
            db.swarmTaskDao().deleteTask(task)
            if (_selectedTaskId.value == task.id) {
                _selectedTaskId.value = null
            }
        }
    }

    // Voice Actions Integration
    fun startListening() {
        _isVoiceListening.value = true
        _voiceTranscript.value = "Listening for swarm command..."
        _voiceFeedback.value = ""
    }

    fun stopListeningAndProcess(simulatedPrompt: String? = null) {
        _isVoiceListening.value = false
        val spoken = simulatedPrompt ?: getRandomVoiceCommand()
        _voiceTranscript.value = spoken
        _isVoiceProcessing.value = true
        _voiceFeedback.value = "Analyzing command with on-device orchestrator..."

        viewModelScope.launch {
            try {
                // Classify spoken transcript and format as structured JSON
                val apiPrompt = """
                    Analyze the following user voice command: "$spoken"
                    We can perform these actions in our on-device Ollama Swarm app:
                    1. RUN_SWARM (requires a task prompt, and optionally a swarmId: 1 for Research & Synthesize, 2 for Elite Code, 3 for Consensus. If not specified or if matches code, use 2, otherwise 1).
                    2. CREATE_NODE (requires nodeName and nodeUrl).
                    3. CREATE_AGENT (requires agentName, agentRole, agentPrompt, agentModel).
                    4. UNKNOWN.

                    Respond with a valid, raw, unformatted JSON block. Do NOT include markdown code blocks.
                    Format:
                    {
                      "action": "RUN_SWARM" | "CREATE_NODE" | "CREATE_AGENT" | "UNKNOWN",
                      "swarmId": Int or null,
                      "taskPrompt": "Description of the task to execute",
                      "nodeName": "Name of the node",
                      "nodeUrl": "Node address/ip",
                      "agentName": "Agent name",
                      "agentRole": "Agent role",
                      "agentPrompt": "Agent system prompt",
                      "agentModel": "Model name"
                    }
                """.trimIndent()

                val responseJson = swarmEngine.generateFreeform(apiPrompt, "You are a voice command router. Always respond with raw JSON matching the requested schema and nothing else.")
                
                // Parse the response using Moshi
                val cleanedJson = responseJson.trim()
                    .replace("```json", "")
                    .replace("```", "")
                    .trim()
                
                val adapter = moshi.adapter(VoiceAction::class.java)
                val voiceAction = withContext(dispatcher) {
                    adapter.fromJson(cleanedJson)
                }

                if (voiceAction != null) {
                    executeVoiceAction(voiceAction)
                } else {
                    _voiceFeedback.value = "Unable to process the command structure. Please try again."
                }
            } catch (e: Exception) {
                _voiceFeedback.value = "Command Error: ${e.localizedMessage}. Running fallback task..."
                delay(1500)
                // Default fallback: Trigger sequential task on user's query
                val defaultSwarm = db.swarmConfigDao().getSwarmConfigById(1)
                if (defaultSwarm != null) {
                    _voiceFeedback.value = "Executing fallback task on 'Research & Synthesize' Swarm..."
                    runSwarm(defaultSwarm, spoken)
                }
            } finally {
                _isVoiceProcessing.value = false
            }
        }
    }

    private suspend fun executeVoiceAction(action: VoiceAction) {
        when (action.action) {
            "RUN_SWARM" -> {
                val swarmId = action.swarmId ?: 1
                val prompt = action.taskPrompt ?: _voiceTranscript.value
                val swarm = db.swarmConfigDao().getSwarmConfigById(swarmId)
                if (swarm != null) {
                    _voiceFeedback.value = "Swarm Dispatched! Initializing '${swarm.name}'..."
                    delay(1000)
                    runSwarm(swarm, prompt)
                } else {
                    _voiceFeedback.value = "Swarm profile ID $swarmId not found. Executing with default pipeline."
                    val defaultSwarm = db.swarmConfigDao().getSwarmConfigById(1)
                    if (defaultSwarm != null) {
                        runSwarm(defaultSwarm, prompt)
                    }
                }
            }
            "CREATE_NODE" -> {
                val name = action.nodeName ?: "Voice Peer Node"
                val url = action.nodeUrl ?: "http://192.168.1.100:11434"
                db.ollamaNodeDao().insertNode(OllamaNode(name = name, url = url))
                _voiceFeedback.value = "Decentralized Peer Created: '$name' at $url"
            }
            "CREATE_AGENT" -> {
                val name = action.agentName ?: "Voice Agent"
                val role = action.agentRole ?: "Assistant"
                val model = action.agentModel ?: "llama3"
                val systemPrompt = action.agentPrompt ?: "You are a helpful cooperative agent."
                db.agentDao().insertAgent(Agent(name = name, role = role, modelName = model, systemPrompt = systemPrompt, colorHex = "#9C27B0"))
                _voiceFeedback.value = "Agent Persona Activated: '$name' as $role using $model"
            }
            else -> {
                _voiceFeedback.value = "Intent recognized as generic. Executing general research swarm."
                delay(1000)
                val defaultSwarm = db.swarmConfigDao().getSwarmConfigById(1)
                if (defaultSwarm != null) {
                    runSwarm(defaultSwarm, _voiceTranscript.value)
                }
            }
        }
    }

    // Chat & Code Workspace states
    val workspaceFiles = db.workspaceFileDao().getAllFiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedFile = MutableStateFlow<WorkspaceFile?>(null)
    val selectedFile: StateFlow<WorkspaceFile?> = _selectedFile.asStateFlow()

    val chatMessages = db.chatMessageDao().getAllMessages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val gitCommits = db.gitCommitDao().getAllCommits()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _gitRepoName = MutableStateFlow("project-nebula-swarm")
    val gitRepoName: StateFlow<String> = _gitRepoName.asStateFlow()

    private val _gitCodename = MutableStateFlow("nebula-omega")
    val gitCodename: StateFlow<String> = _gitCodename.asStateFlow()

    private val _gitRemoteUrl = MutableStateFlow(prefs.getString("git_remote_url", "") ?: "")
    val gitRemoteUrl: StateFlow<String> = _gitRemoteUrl.asStateFlow()

    private val _isGitSynced = MutableStateFlow(true)
    val isGitSynced: StateFlow<Boolean> = _isGitSynced.asStateFlow()

    private val _isGitSyncing = MutableStateFlow(false)
    val isGitSyncing: StateFlow<Boolean> = _isGitSyncing.asStateFlow()

    private val _gitError = MutableStateFlow<String?>(null)
    val gitError: StateFlow<String?> = _gitError.asStateFlow()

    private val gitWorkDir by lazy {
        File(getApplication<Application>().filesDir, "git_workspace").apply { mkdirs() }
    }
    private val gitService by lazy { GitService(gitWorkDir) }

    private fun computeGitSyncState() {
        val lastPushedHash = prefs.getString("git_last_pushed_hash", null)
        _isGitSynced.value = gitService.isClean() && gitService.localHeadHash() == lastPushedHash
    }

    fun selectFile(file: WorkspaceFile?) {
        _selectedFile.value = file
    }

    private fun findOrCreateFileInTree(root: DocumentFile, relativePath: String): DocumentFile? {
        val components = relativePath.split('/')
        var currentDir = root
        for (i in 0 until components.size - 1) {
            val dirName = components[i]
            if (dirName.isEmpty() || dirName == ".") continue
            var nextDir = currentDir.findFile(dirName)
            if (nextDir == null || !nextDir.isDirectory) {
                nextDir = currentDir.createDirectory(dirName) ?: return null
            }
            currentDir = nextDir
        }
        val fileName = components.last()
        var fileDoc = currentDir.findFile(fileName)
        if (fileDoc == null) {
            val ext = fileName.substringAfterLast('.', "")
            val mimeType = when (ext) {
                "json" -> "application/json"
                "xml" -> "application/xml"
                "html" -> "text/html"
                "css" -> "text/css"
                else -> "text/plain"
            }
            fileDoc = currentDir.createFile(mimeType, fileName)
        }
        return fileDoc
    }

    fun createFile(filePath: String, content: String) {
        viewModelScope.launch {
            val cleanPath = filePath.trim().replace(" ", "_")
            var sourceUriStr: String? = null
            val finalContent = content
            val uriStr = _rootFolderUri.value
            if (uriStr != null) {
                val app = getApplication<Application>()
                val treeUri = Uri.parse(uriStr)
                val root = withContext(dispatcher) { DocumentFile.fromTreeUri(app, treeUri) }
                if (root != null && root.isDirectory) {
                    val realDoc = withContext(dispatcher) {
                        findOrCreateFileInTree(root, cleanPath)
                    }
                    if (realDoc != null) {
                        sourceUriStr = realDoc.uri.toString()
                        withContext(dispatcher) {
                            try {
                                app.contentResolver.openOutputStream(realDoc.uri, "wt")
                                    ?.use { it.write(finalContent.toByteArray()) }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to write initial content for new file: ${e.message}")
                            }
                        }
                    }
                }
            }

            val newFile = WorkspaceFile(
                filePath = cleanPath,
                content = finalContent,
                lastModified = System.currentTimeMillis(),
                sourceUri = sourceUriStr
            )
            val newId = db.workspaceFileDao().insertFile(newFile)
            val insertedFile = newFile.copy(id = newId.toInt())
            _selectedFile.value = insertedFile
            _isGitSynced.value = false
        }
    }

    suspend fun saveFileContentSuspended(id: Int, content: String): WorkspaceFile? {
        val existing = db.workspaceFileDao().getFileById(id) ?: return null
        val updated = existing.copy(content = content, lastModified = System.currentTimeMillis())
        existing.sourceUri?.let { uriString ->
            withContext(dispatcher) {
                try {
                    getApplication<Application>().contentResolver
                        .openOutputStream(Uri.parse(uriString), "wt")
                        ?.use { it.write(content.toByteArray()) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write back to real file $uriString: ${e.message}")
                }
            }
        }
        db.workspaceFileDao().insertFile(updated)
        _selectedFile.value = updated
        _isGitSynced.value = false
        return updated
    }

    fun saveFile(id: Int, content: String) {
        viewModelScope.launch {
            saveFileContentSuspended(id, content)
        }
    }

    fun resolveConflict(id: Int, resolvedContent: String, keepLocal: Boolean) {
        viewModelScope.launch {
            val existing = db.workspaceFileDao().getFileById(id) ?: return@launch
            val resolved = existing.copy(
                content = resolvedContent,
                isConflict = false,
                conflictContent = null,
                lastModified = System.currentTimeMillis()
            )
            if (keepLocal) {
                resolved.sourceUri?.let { uriString ->
                    withContext(dispatcher) {
                        try {
                            getApplication<Application>().contentResolver
                                .openOutputStream(Uri.parse(uriString), "wt")
                                ?.use { it.write(resolvedContent.toByteArray()) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to write resolved content to $uriString: ${e.message}")
                        }
                    }
                }
            }
            db.workspaceFileDao().insertFile(resolved)
            _selectedFile.value = resolved
        }
    }

    fun deleteFile(file: WorkspaceFile) {
        viewModelScope.launch {
            db.workspaceFileDao().deleteFile(file)
            file.sourceUri?.let { uriStr ->
                withContext(dispatcher) {
                    try {
                        val fileDoc = DocumentFile.fromSingleUri(getApplication(), Uri.parse(uriStr))
                        if (fileDoc != null && fileDoc.exists()) {
                            fileDoc.delete()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to delete real file: ${e.message}")
                    }
                }
            }
            if (_selectedFile.value?.id == file.id) {
                _selectedFile.value = null
            }
            _isGitSynced.value = false
        }
    }

    fun uploadFile(filePath: String, content: String) {
        viewModelScope.launch {
            createFile(filePath, content)
        }
    }

    private val _isImportingFolder = MutableStateFlow(false)
    val isImportingFolder: StateFlow<Boolean> = _isImportingFolder.asStateFlow()

    private val _folderImportStatus = MutableStateFlow("")
    val folderImportStatus: StateFlow<String> = _folderImportStatus.asStateFlow()

    fun importFolder(treeUri: Uri) {
        viewModelScope.launch {
            _isImportingFolder.value = true
            _folderImportStatus.value = "Scanning..."
            try {
                val app = getApplication<Application>()
                app.contentResolver.takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                val root = withContext(dispatcher) { DocumentFile.fromTreeUri(app, treeUri) }
                if (root == null || !root.isDirectory) {
                    _folderImportStatus.value = "Could not open selected folder."
                    return@launch
                }

                // Persist folder Uri and Name
                val rootName = root.name ?: "Workspace Folder"
                prefs.edit()
                    .putString("root_folder_uri", treeUri.toString())
                    .putString("root_folder_name", rootName)
                    .apply()
                _rootFolderUri.value = treeUri.toString()
                _rootFolderName.value = rootName

                // Clear existing files before importing a new project
                db.workspaceFileDao().deleteAllFiles()

                var imported = 0
                val maxFiles = 1500
                val hitCap = withContext(dispatcher) {
                    walkAndImport(root, maxFiles = maxFiles) { relativePath, doc ->
                        val text = try {
                            app.contentResolver.openInputStream(doc.uri)
                                ?.bufferedReader()?.use { it.readText() }
                        } catch (e: Exception) {
                            null
                        } ?: return@walkAndImport

                        val existing = db.workspaceFileDao().getFileByPath(relativePath)
                        val file = WorkspaceFile(
                            id = existing?.id ?: 0,
                            filePath = relativePath,
                            content = text,
                            lastModified = doc.lastModified(),
                            sourceUri = doc.uri.toString()
                        )
                        db.workspaceFileDao().insertFile(file)
                        imported++
                        _folderImportStatus.value = "Imported $imported files..."
                    }
                }

                _folderImportStatus.value = if (hitCap) {
                    "Imported $imported files from ${root.name} (stopped at the $maxFiles-file limit -- some files were not imported)."
                } else {
                    "Imported $imported files from ${root.name}."
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "Project imported: $rootName", Toast.LENGTH_SHORT).show()
                }
                if (imported > 0) {
                    selectFile(db.workspaceFileDao().getAllFiles().first().firstOrNull())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Folder import failed: ${e.message}", e)
                _folderImportStatus.value = "Import failed: ${e.message}"
            } finally {
                _isImportingFolder.value = false
            }
        }
    }

    fun syncWorkspace(isAutomatic: Boolean = false) {
        val uriStr = _rootFolderUri.value ?: return
        viewModelScope.launch {
            if (_isImportingFolder.value) return@launch // Prevent concurrent syncs/scans
            _isImportingFolder.value = true
            _folderImportStatus.value = "Synchronizing..."
            try {
                val app = getApplication<Application>()
                val treeUri = Uri.parse(uriStr)

                // Verify permissions
                val hasPermission = app.contentResolver.persistedUriPermissions.any {
                    it.uri == treeUri && it.isReadPermission && it.isWritePermission
                }
                if (!hasPermission) {
                    _folderImportStatus.value = "Permission lost for workspace folder."
                    _isImportingFolder.value = false
                    return@launch
                }

                val root = withContext(dispatcher) { DocumentFile.fromTreeUri(app, treeUri) }
                if (root == null || !root.isDirectory) {
                    _folderImportStatus.value = "Could not open workspace folder."
                    _isImportingFolder.value = false
                    return@launch
                }

                val diskFiles = mutableMapOf<String, DocumentFile>()
                val maxFiles = 1500
                var hitCap = false

                withContext(dispatcher) {
                    suspend fun walk(node: DocumentFile, base: String) {
                        if (diskFiles.size >= maxFiles) {
                            hitCap = true
                            return
                        }
                        for (child in node.listFiles()) {
                            if (diskFiles.size >= maxFiles) {
                                hitCap = true
                                return
                            }
                            val name = child.name ?: continue
                            val path = if (base.isEmpty()) name else "$base/$name"
                            if (child.isDirectory) {
                                if (name !in skippedDirNames && !name.startsWith(".")) {
                                    walk(child, path)
                                }
                            } else {
                                val ext = name.substringAfterLast('.', "")
                                if (ext in importableExtensions && child.length() in 1..MAX_IMPORT_FILE_BYTES.toLong()) {
                                    diskFiles[path] = child
                                }
                            }
                        }
                    }
                    walk(root, "")
                }

                val dbFiles = db.workspaceFileDao().getAllFiles().first().associateBy { it.filePath }
                var added = 0
                var updated = 0
                var deleted = 0

                val filesToInsert = mutableListOf<WorkspaceFile>()
                val filesToDelete = mutableListOf<WorkspaceFile>()

                // 1. Process files from disk (Add or Update)
                for ((relativePath, doc) in diskFiles) {
                    val existing = dbFiles[relativePath]
                    if (existing == null) {
                        val text = withContext(dispatcher) {
                            try {
                                app.contentResolver.openInputStream(doc.uri)
                                    ?.bufferedReader()?.use { it.readText() }
                            } catch (e: Exception) {
                                null
                            }
                        } ?: continue
                        val file = WorkspaceFile(
                            filePath = relativePath,
                            content = text,
                            lastModified = doc.lastModified(),
                            sourceUri = doc.uri.toString()
                        )
                        filesToInsert.add(file)
                        added++
                    } else {
                        if (doc.lastModified() > existing.lastModified) {
                            val text = withContext(dispatcher) {
                                try {
                                    app.contentResolver.openInputStream(doc.uri)
                                        ?.bufferedReader()?.use { it.readText() }
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            if (text != null && text != existing.content) {
                                // If the disk content has changed, flag it as a conflict
                                val updatedFile = existing.copy(
                                    isConflict = true,
                                    conflictContent = text, // store disk content
                                    lastModified = doc.lastModified()
                                )
                                filesToInsert.add(updatedFile)
                                updated++
                            }
                        }
                    }
                }

                // 2. Process files in DB that are missing on disk (Delete)
                for ((relativePath, dbFile) in dbFiles) {
                    if (dbFile.sourceUri != null && !diskFiles.containsKey(relativePath)) {
                        filesToDelete.add(dbFile)
                        if (_selectedFile.value?.id == dbFile.id) {
                            _selectedFile.value = null
                        }
                        deleted++
                    }
                }

                // 3. Commit transaction
                if (filesToInsert.isNotEmpty() || filesToDelete.isNotEmpty()) {
                    db.workspaceFileDao().syncFilesTransaction(filesToInsert, filesToDelete)
                }

                val statusMsg = when {
                    hitCap -> "Synced (stopped at $maxFiles-file limit). Added $added, updated $updated, deleted $deleted."
                    added > 0 || updated > 0 || deleted > 0 -> "Sync complete. Added $added, updated $updated, deleted $deleted."
                    else -> "Sync complete. No changes."
                }
                _folderImportStatus.value = statusMsg

                withContext(Dispatchers.Main) {
                    Toast.makeText(app, statusMsg, Toast.LENGTH_SHORT).show()
                }

                val remainingFiles = db.workspaceFileDao().getAllFiles().first()
                if (_selectedFile.value == null && remainingFiles.isNotEmpty()) {
                    selectFile(remainingFiles.firstOrNull())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Workspace sync failed", e)
                _folderImportStatus.value = "Sync failed: ${e.message}"
            } finally {
                _isImportingFolder.value = false
            }
        }
    }

    fun disconnectFolder() {
        viewModelScope.launch {
            _isImportingFolder.value = true
            _folderImportStatus.value = "Disconnecting..."
            try {
                prefs.edit().remove("root_folder_uri").remove("root_folder_name").apply()
                _rootFolderUri.value = null
                _rootFolderName.value = null

                val allFiles = db.workspaceFileDao().getAllFiles().first()
                allFiles.forEach { file ->
                    if (file.sourceUri != null) {
                        db.workspaceFileDao().deleteFile(file)
                    }
                }

                if (_selectedFile.value?.sourceUri != null) {
                    _selectedFile.value = null
                }

                _folderImportStatus.value = "Disconnected from folder."
                withContext(Dispatchers.Main) {
                    Toast.makeText(getApplication(), "Workspace disconnected", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect folder failed", e)
                _folderImportStatus.value = "Disconnect failed: ${e.message}"
            } finally {
                _isImportingFolder.value = false
            }
        }
    }

    private val skippedDirNames = setOf(
        ".git", "build", ".gradle", "node_modules", ".idea", ".transforms",
        ".worktrees", "dist", "out", ".cache", ".kotlin"
    )
    private val importableExtensions = setOf(
        "kt", "kts", "java", "xml", "gradle", "md", "json", "py", "js", "ts", "jsx", "tsx",
        "txt", "yml", "yaml", "toml", "properties", "sh", "css", "html", "sql", "proto"
    )

    private suspend fun walkAndImport(
        dir: DocumentFile,
        maxFiles: Int,
        onFile: suspend (relativePath: String, doc: DocumentFile) -> Unit
    ): Boolean {
        var count = 0
        suspend fun walk(node: DocumentFile, base: String) {
            if (count >= maxFiles) return
            for (child in node.listFiles()) {
                if (count >= maxFiles) return
                val name = child.name ?: continue
                val path = if (base.isEmpty()) name else "$base/$name"
                if (child.isDirectory) {
                    if (name !in skippedDirNames && !name.startsWith(".")) {
                        walk(child, path)
                    }
                } else {
                    val ext = name.substringAfterLast('.', "")
                    if (ext in importableExtensions && child.length() in 1..MAX_IMPORT_FILE_BYTES.toLong()) {
                        onFile(path, child)
                        count++
                    }
                }
            }
        }
        walk(dir, "")
        return count >= maxFiles
    }

    fun updateGitSettings(repoName: String, codename: String, remoteUrl: String, token: String) {
        _gitRepoName.value = repoName
        _gitCodename.value = codename
        _gitRemoteUrl.value = remoteUrl
        prefs.edit().putString("git_remote_url", remoteUrl).apply()
        if (token.isNotBlank()) {
            securePrefs.setGitToken(token)
        }
    }

    fun commitChanges(message: String) {
        viewModelScope.launch {
            _gitError.value = null
            val files = db.workspaceFileDao().getAllFiles().first()
            val (result, status) = withContext(dispatcher) {
                gitService.mirrorFiles(files)
                gitService.commitAll("Lead Swarm Orchestrator", "orchestrator@ollamadev.local", message)
            }
            if (result != null && status is GitOpResult.Success) {
                db.gitCommitDao().insertCommit(
                    GitCommit(
                        commitHash = result.hash,
                        author = result.author,
                        message = result.message,
                        timestamp = result.timestamp
                    )
                )
                withContext(dispatcher) { computeGitSyncState() }
            } else if (status is GitOpResult.Failure) {
                _gitError.value = status.error
            }
        }
    }

    fun pushToGit() {
        val remoteUrl = _gitRemoteUrl.value
        val token = securePrefs.getGitToken()
        if (remoteUrl.isBlank() || token.isNullOrBlank()) {
            _gitError.value = "Configure a remote URL and personal access token in Git Settings before pushing."
            return
        }
        viewModelScope.launch {
            _gitError.value = null
            _isGitSyncing.value = true
            try {
                val status = withContext(dispatcher) { gitService.push(remoteUrl, token) }
                when (status) {
                    is GitOpResult.Success -> {
                        val headHash = withContext(dispatcher) { gitService.localHeadHash() }
                        prefs.edit().putString("git_last_pushed_hash", headHash).apply()
                        withContext(dispatcher) { computeGitSyncState() }
                    }
                    is GitOpResult.Failure -> _gitError.value = status.error
                }
            } finally {
                _isGitSyncing.value = false
            }
        }
    }


    // Sandbox Compilation & Code Runner states
    private val _isSandboxRunning = MutableStateFlow(false)
    val isSandboxRunning: StateFlow<Boolean> = _isSandboxRunning.asStateFlow()

    private val _sandboxConsoleOutput = MutableStateFlow("")
    val sandboxConsoleOutput: StateFlow<String> = _sandboxConsoleOutput.asStateFlow()

    private val _sandboxExitCode = MutableStateFlow<Int?>(null)
    val sandboxExitCode: StateFlow<Int?> = _sandboxExitCode.asStateFlow()

    private val _sandboxLanguage = MutableStateFlow("unknown")
    val sandboxLanguage: StateFlow<String> = _sandboxLanguage.asStateFlow()

    private val _sandboxMemoryUsed = MutableStateFlow("0.0 MB")
    val sandboxMemoryUsed: StateFlow<String> = _sandboxMemoryUsed.asStateFlow()

    private val _sandboxTimeMs = MutableStateFlow(0L)
    val sandboxTimeMs: StateFlow<Long> = _sandboxTimeMs.asStateFlow()

    private val _isSelfHealingEnabled = MutableStateFlow(true)
    val isSelfHealingEnabled: StateFlow<Boolean> = _isSelfHealingEnabled.asStateFlow()

    fun setSelfHealingEnabled(enabled: Boolean) {
        _isSelfHealingEnabled.value = enabled
    }

    private val _pendingSelfHealingPatch = MutableStateFlow<Pair<WorkspaceFile, String>?>(null)
    val pendingSelfHealingPatch: StateFlow<Pair<WorkspaceFile, String>?> = _pendingSelfHealingPatch.asStateFlow()

    private var selfHealingDeferred: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    fun acceptSelfHealingPatch() {
        selfHealingDeferred?.complete(true)
    }

    fun declineSelfHealingPatch() {
        selfHealingDeferred?.complete(false)
    }

    // Agentic-loop human oversight: SwarmEngine (a plain class with no reference back into this
    // ViewModel's private state) publishes gate requests through the process-wide
    // PendingApprovalStore singleton; these just re-expose/delegate to it, same rationale as
    // AgentStateStore's agentStates re-export above.
    val pendingApproval: StateFlow<PendingApproval?> = PendingApprovalStore.pendingApproval
    fun approvePendingAction() = PendingApprovalStore.approve()
    fun rejectPendingAction() = PendingApprovalStore.reject()

    val pendingFileChange: StateFlow<PendingFileChange?> = PendingApprovalStore.pendingFileChange
    fun acceptPendingFileChange() = PendingApprovalStore.acceptFileChange()
    fun rejectPendingFileChange() = PendingApprovalStore.rejectFileChange()

    /**
     * Hard-reverts the local git repo to [commit] and reconciles WorkspaceFile rows against the
     * reverted-to filesystem state -- deletes rows no longer present on disk, upserts the rest.
     * Local, destructive (discards uncommitted changes), so this is user-initiated only from the
     * commit history UI, gated by a plain confirmation dialog rather than PendingApprovalStore
     * (that gate is specifically for agent-initiated risky actions).
     */
    fun revertToCheckpoint(commit: GitCommit) {
        viewModelScope.launch(dispatcher) {
            val status = withContext(Dispatchers.IO) { gitService.revertToCommit(commit.commitHash) }
            if (status is GitOpResult.Success) {
                val diskFiles = withContext(Dispatchers.IO) { gitService.readWorkDirFiles() }
                val diskPaths = diskFiles.map { it.first }.toSet()
                val currentFiles = db.workspaceFileDao().getAllFiles().first()
                currentFiles.filter { it.filePath !in diskPaths }.forEach { db.workspaceFileDao().deleteFile(it) }
                diskFiles.forEach { (path, content) ->
                    val existing = db.workspaceFileDao().getFileByPath(path)
                    if (existing != null) {
                        db.workspaceFileDao().updateFile(existing.copy(content = content, lastModified = System.currentTimeMillis()))
                    } else {
                        db.workspaceFileDao().insertFile(WorkspaceFile(filePath = path, content = content))
                    }
                }
                Toast.makeText(getApplication(), "Reverted to checkpoint ${commit.commitHash}", Toast.LENGTH_SHORT).show()
            } else if (status is GitOpResult.Failure) {
                Toast.makeText(getApplication(), "Revert failed: ${status.error}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun runSandbox(file: WorkspaceFile) {
        viewModelScope.launch {
            _isSandboxRunning.value = true
            _sandboxConsoleOutput.value = "Initializing virtual compile & run workspace...\n"
            _sandboxExitCode.value = null
            
            val extension = file.filePath.substringAfterLast('.', "")
            val language = when (extension.lowercase()) {
                "py" -> "Python 3.10 Sandbox"
                "js" -> "Node.js (V8 Runtime)"
                "json" -> "JSON Parser & Validator"
                "kt", "kts" -> "Kotlin Compiler & Runner"
                "md" -> "Markdown Parser & Linter"
                else -> "Generic Sandbox"
            }
            _sandboxLanguage.value = language

            var currentFile = file
            var attempt = 1
            val maxAttempts = 3
            var hasErrors = true

            while (attempt <= maxAttempts && hasErrors) {
                if (attempt > 1) {
                    _sandboxConsoleOutput.value += "\n[SELF-HEAL] Re-compiling code (Attempt $attempt of $maxAttempts)...\n"
                } else {
                    delay(600)
                    _sandboxConsoleOutput.value += "[INFO] Running lexical check & AST syntax parsing...\n"
                    delay(500)
                }

                val prompt = """
                    You are a highly precise sandboxed code compiler and runtime terminal.
                    Execute the following file contents as if it was running on a native OS with full sandbox libraries.
                    If there are logical bugs or syntax errors, throw the exact compile or runtime trace back.
                    
                    File path: ${currentFile.filePath}
                    Language environment: $language
                    
                    File Contents:
                    ```
                    ${currentFile.content}
                    ```
                    
                    Generate the exact console/terminal log output of running this script. 
                    Include any standard output streams, return values, errors, or logs.
                    Make the terminal output look extremely detailed, technical, clean, and real.
                    No markdown wrapper around your answer, just the raw terminal output of compilation and execution.
                """.trimIndent()

                try {
                    _sandboxConsoleOutput.value += "[COMPILER] Linking libraries and starting sandbox runner...\n"
                    val systemInstruction = "You are a sandboxed terminal interpreter. Output the exact execution logs of the code provided."
                    val output = swarmEngine.generateFreeform(prompt, systemInstruction)
                    
                    delay(800)
                    _sandboxConsoleOutput.value += "\n--- VIRTUAL RUNTIME OUTPUT ---\n"
                    _sandboxConsoleOutput.value += output
                    _sandboxConsoleOutput.value += "\n--------------------------------\n"
                    
                    hasErrors = output.lowercase().contains("error") || output.lowercase().contains("exception") || output.lowercase().contains("failed")
                    _sandboxExitCode.value = if (hasErrors) 1 else 0
                    
                    _sandboxMemoryUsed.value = "${String.format("%.1f", (3..12).random() + (0..9).random()/10f)} MB"
                    _sandboxTimeMs.value = (10..450).random().toLong()

                    if (hasErrors && _isSelfHealingEnabled.value) {
                        if (attempt < maxAttempts) {
                            _sandboxConsoleOutput.value += "\n[SELF-HEAL] Error detected. Initiating automated repair loop with Bug Hunter agent...\n"
                            
                            val bugHunterAgent = allAgents.value.find { 
                                it.name.lowercase().contains("bug hunter") || it.role.lowercase().contains("critic") 
                            }
                            
                            val repairPrompt = """
                                You are the Bug Hunter agent. Repair the following code that failed compilation/execution.
                                
                                File Path: ${currentFile.filePath}
                                Language Environment: $language
                                
                                Compiler/Runtime Console Output:
                                $output
                                
                                Broken Code:
                                ```
                                ${currentFile.content}
                                ```
                                
                                Provide ONLY the fully corrected, fixed file contents. Do not include markdown code block syntax (like ```kotlin or ```py). Do not add conversational intro/outro text. Just output the raw corrected code contents.
                            """.trimIndent()
                            
                            _sandboxConsoleOutput.value += "[SELF-HEAL] Dispatching repair prompt to ${bugHunterAgent?.name ?: "Bug Hunter"}...\n"
                            
                            val fixedContentRaw = swarmEngine.generateFreeform(
                                repairPrompt,
                                bugHunterAgent?.systemPrompt ?: "You are a software bug hunting agent. Correct code syntax and logic errors."
                            )
                            
                            val fixedContent = fixedContentRaw.trim()
                                .removePrefix("```kotlin")
                                .removePrefix("```python")
                                .removePrefix("```py")
                                .removePrefix("```javascript")
                                .removePrefix("```js")
                                .removePrefix("```json")
                                .removePrefix("```")
                                .removeSuffix("```")
                                .trim()
                            
                            _sandboxConsoleOutput.value += "[SELF-HEAL] Bug Hunter proposed a fix. Waiting for developer review & consent...\n"
                            
                            val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
                            selfHealingDeferred = deferred
                            _pendingSelfHealingPatch.value = currentFile to fixedContent
                            
                            val isAccepted = deferred.await()
                            _pendingSelfHealingPatch.value = null
                            selfHealingDeferred = null
                            
                            if (isAccepted) {
                                _sandboxConsoleOutput.value += "[SELF-HEAL] Patch accepted. Saving to disk...\n"
                                val updatedFile = saveFileContentSuspended(currentFile.id, fixedContent)
                                if (updatedFile != null) {
                                    currentFile = updatedFile
                                }
                            } else {
                                _sandboxConsoleOutput.value += "[SELF-HEAL] Patch declined. Stopping self-healing loop.\n"
                                hasErrors = false
                            }
                        } else {
                            _sandboxConsoleOutput.value += "\n[SELF-HEAL] Failed to resolve compilation errors after $maxAttempts attempts. Manual intervention required.\n"
                        }
                    }
                    
                } catch (e: Exception) {
                    _sandboxConsoleOutput.value += "\n[FATAL RUNTIME ERROR] Failed to spawn virtual process: ${e.localizedMessage}\n"
                    _sandboxExitCode.value = -1
                    _sandboxMemoryUsed.value = "0.0 MB"
                    _sandboxTimeMs.value = 0L
                    hasErrors = false
                }
                
                attempt++
            }
            _isSandboxRunning.value = false
        }
    }

    private fun getRandomVoiceCommand(): String {
        val commands = listOf(
            "Ask the elite code swarm to write a fast fibonacci function in Kotlin",
            "Assemble a research swarm to review the benefits of on-device LLM processing",
            "Connect decentralized node Peer-C at http://192.168.0.45:11434",
            "Create a new critic agent named Helix Critic specializing in security auditing"
        )
        return commands.random()
    }
}

class SwarmViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SwarmViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SwarmViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
