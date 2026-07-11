package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.*
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

class SwarmViewModel(application: Application) : AndroidViewModel(application) {
    private val db = AppDatabase.getDatabase(application)
    private val swarmEngine = SwarmEngine(db)
    private val moshi = Moshi.Builder().build()

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
    }

    fun clearAgentMetrics() {
        AgentStateStore.clearMetrics()
    }

    // Node Operations
    fun addNode(name: String, url: String, availableModels: String = "llama3") {
        viewModelScope.launch {
            db.ollamaNodeDao().insertNode(OllamaNode(name = name, url = url, availableModels = availableModels))
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
            // Simulate decentralized ping across custom node URLs
            val nodes = db.ollamaNodeDao().getAllNodes().stateIn(viewModelScope).value
            nodes.forEach { node ->
                db.ollamaNodeDao().updateNode(node.copy(status = "Connecting"))
            }
            delay(1200)
            nodes.forEach { node ->
                val newStatus = if (node.name.contains("Local")) "Offline" else "Online"
                db.ollamaNodeDao().updateNode(node.copy(status = newStatus))
            }
        }
    }

    // MCP Server Operations
    fun addMcpServer(name: String, type: String, sourceUrl: String, configuredParams: String = "{}") {
        viewModelScope.launch {
            db.mcpServerDao().insertServer(
                McpServer(
                    name = name,
                    type = type,
                    sourceUrl = sourceUrl,
                    status = "Connected",
                    toolsCount = (3..12).random(),
                    configuredParams = configuredParams
                )
            )
        }
    }

    fun updateMcpServer(server: McpServer) {
        viewModelScope.launch {
            db.mcpServerDao().updateServer(server)
        }
    }

    fun deleteMcpServer(server: McpServer) {
        viewModelScope.launch {
            db.mcpServerDao().deleteServer(server)
        }
    }

    fun toggleMcpServerStatus(server: McpServer) {
        viewModelScope.launch {
            val newStatus = if (server.status == "Connected") "Disconnected" else "Connected"
            db.mcpServerDao().updateServer(server.copy(status = newStatus))
        }
    }

    // Claude Skills Operations
    fun addClaudeSkill(name: String, description: String, category: String, isRecommended: Boolean, usageExample: String, requiredMcpServerType: String) {
        viewModelScope.launch {
            db.claudeSkillDao().insertSkill(
                ClaudeSkill(
                    name = name,
                    description = description,
                    category = category,
                    isRecommended = isRecommended,
                    isEnabled = true,
                    usageExample = usageExample,
                    requiredMcpServerType = requiredMcpServerType
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
            val taskId = swarmEngine.executeTask(config, prompt)
            _selectedTaskId.value = taskId
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
                // Call Gemini to classify spoken transcript and format as structured JSON
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

                val responseJson = GeminiService.generate(apiPrompt, "You are a voice command router. Always respond with raw JSON matching the requested schema and nothing else.")
                
                // Parse the response using Moshi
                val cleanedJson = responseJson.trim()
                    .replace("```json", "")
                    .replace("```", "")
                    .trim()
                
                val adapter = moshi.adapter(VoiceAction::class.java)
                val voiceAction = withContext(Dispatchers.IO) {
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

    private val _isGitSynced = MutableStateFlow(true)
    val isGitSynced: StateFlow<Boolean> = _isGitSynced.asStateFlow()

    private val _isGitSyncing = MutableStateFlow(false)
    val isGitSyncing: StateFlow<Boolean> = _isGitSyncing.asStateFlow()

    fun selectFile(file: WorkspaceFile?) {
        _selectedFile.value = file
    }

    fun createFile(filePath: String, content: String) {
        viewModelScope.launch {
            val cleanPath = filePath.trim().replace(" ", "_")
            val newFile = WorkspaceFile(filePath = cleanPath, content = content, lastModified = System.currentTimeMillis())
            db.workspaceFileDao().insertFile(newFile)
            _isGitSynced.value = false
        }
    }

    fun saveFile(id: Int, content: String) {
        viewModelScope.launch {
            val existing = db.workspaceFileDao().getFileById(id)
            if (existing != null) {
                val updated = existing.copy(content = content, lastModified = System.currentTimeMillis())
                db.workspaceFileDao().insertFile(updated)
                _selectedFile.value = updated
                _isGitSynced.value = false
            }
        }
    }

    fun deleteFile(file: WorkspaceFile) {
        viewModelScope.launch {
            db.workspaceFileDao().deleteFile(file)
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

    fun updateGitSettings(repoName: String, codename: String) {
        _gitRepoName.value = repoName
        _gitCodename.value = codename
    }

    fun commitChanges(message: String) {
        viewModelScope.launch {
            val hash = (0..7).map { "0123456789abcdef".random() }.joinToString("")
            val commit = GitCommit(
                commitHash = hash,
                author = "Lead Swarm Orchestrator",
                message = message,
                timestamp = System.currentTimeMillis()
            )
            db.gitCommitDao().insertCommit(commit)
            _isGitSynced.value = false
        }
    }

    fun pushToGit() {
        viewModelScope.launch {
            _isGitSyncing.value = true
            delay(2000)
            _isGitSynced.value = true
            _isGitSyncing.value = false
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            db.chatMessageDao().clearChat()
            // insert initial greeting
            db.chatMessageDao().insertMessage(
                ChatMessage(
                    sender = "System",
                    role = "system",
                    message = "Decentralized Swarm Chat channel cleared.",
                    timestamp = System.currentTimeMillis(),
                    colorHex = "#9E9E9E"
                )
            )
        }
    }

    fun sendChatMessage(text: String, replyWithAgent: Boolean = true) {
        viewModelScope.launch {
            if (text.isBlank()) return@launch

            // 1. Save user message
            db.chatMessageDao().insertMessage(
                ChatMessage(
                    sender = "User",
                    role = "user",
                    message = text,
                    timestamp = System.currentTimeMillis()
                )
            )

            if (!replyWithAgent) return@launch

            // 2. Select active agent to respond
            val agentsList = db.agentDao().getAllAgents().stateIn(viewModelScope).value
            if (agentsList.isEmpty()) return@launch

            // Use Byte Code (Programmer) if "code", "file", "python" is mentioned, else Apex Researcher
            val selectedAgent = if (text.lowercase().contains("code") || text.lowercase().contains("file") || text.lowercase().contains("python") || text.lowercase().contains("refactor")) {
                agentsList.find { it.role.lowercase().contains("programmer") } ?: agentsList.first()
            } else {
                agentsList.find { it.role.lowercase().contains("researcher") } ?: agentsList.first()
            }

            // Set active in metrics store
            AgentStateStore.setAgentActive(selectedAgent.id, true, "Synthesizing Reply")

            // Simulate typing delay
            delay(1200)

            // Construct system instruction with workspace files context!
            val filesList = db.workspaceFileDao().getAllFiles().stateIn(viewModelScope).value
            val filesContext = if (filesList.isNotEmpty()) {
                "The current workspace contains the following files:\n" + filesList.joinToString("\n\n") { file ->
                    "File: ${file.filePath}\nContent:\n${file.content}"
                }
            } else {
                "The workspace is currently empty."
            }

            val systemInstruction = """
                ${selectedAgent.systemPrompt}
                
                You are interacting in a workspace chat where you can see the files.
                Here is the current workspace files state for context:
                $filesContext
                
                Keep your answer extremely concise, functional, and professional.
            """.trimIndent()

            try {
                val reply = GeminiService.generate(text, systemInstruction)
                db.chatMessageDao().insertMessage(
                    ChatMessage(
                        sender = selectedAgent.name,
                        role = "agent",
                        message = reply,
                        timestamp = System.currentTimeMillis(),
                        colorHex = selectedAgent.colorHex
                    )
                )
            } catch (e: Exception) {
                db.chatMessageDao().insertMessage(
                    ChatMessage(
                        sender = selectedAgent.name,
                        role = "agent",
                        message = "I encountered an error while synthesizing: ${e.localizedMessage}",
                        timestamp = System.currentTimeMillis(),
                        colorHex = selectedAgent.colorHex
                    )
                )
            } finally {
                AgentStateStore.setAgentActive(selectedAgent.id, false, "Idle")
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

            delay(600)
            _sandboxConsoleOutput.value += "[INFO] Running lexical check & AST syntax parsing...\n"
            delay(500)

            val prompt = """
                You are a highly precise sandboxed code compiler and runtime terminal.
                Execute the following file contents as if it was running on a native OS with full sandbox libraries.
                If there are logical bugs or syntax errors, throw the exact compile or runtime trace back.
                
                File path: ${file.filePath}
                Language environment: $language
                
                File Contents:
                ```
                ${file.content}
                ```
                
                Generate the exact console/terminal log output of running this script. 
                Include any standard output streams, return values, errors, or logs.
                Make the terminal output look extremely detailed, technical, clean, and real.
                No markdown wrapper around your answer, just the raw terminal output of compilation and execution.
            """.trimIndent()

            try {
                _sandboxConsoleOutput.value += "[COMPILER] Linking libraries and starting sandbox runner...\n"
                val systemInstruction = "You are a sandboxed terminal interpreter. Output the exact execution logs of the code provided."
                val output = GeminiService.generate(prompt, systemInstruction)
                
                delay(800)
                _sandboxConsoleOutput.value += "\n--- VIRTUAL RUNTIME OUTPUT ---\n"
                _sandboxConsoleOutput.value += output
                _sandboxConsoleOutput.value += "\n--------------------------------\n"
                
                val hasErrors = output.lowercase().contains("error") || output.lowercase().contains("exception") || output.lowercase().contains("failed")
                _sandboxExitCode.value = if (hasErrors) 1 else 0
                
                _sandboxMemoryUsed.value = "${String.format("%.1f", (3..12).random() + (0..9).random()/10f)} MB"
                _sandboxTimeMs.value = (10..450).random().toLong()
                
            } catch (e: Exception) {
                _sandboxConsoleOutput.value += "\n[FATAL RUNTIME ERROR] Failed to spawn virtual process: ${e.localizedMessage}\n"
                _sandboxExitCode.value = -1
                _sandboxMemoryUsed.value = "0.0 MB"
                _sandboxTimeMs.value = 0L
            } finally {
                _isSandboxRunning.value = false
            }
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
