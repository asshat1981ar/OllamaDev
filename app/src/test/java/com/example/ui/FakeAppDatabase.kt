package com.example.ui

import com.example.data.Agent
import com.example.data.AgentDao
import com.example.data.AppDatabaseInterface
import com.example.data.ChatMessage
import com.example.data.ChatMessageDao
import com.example.data.ClaudeSkill
import com.example.data.ClaudeSkillDao
import com.example.data.GitCommit
import com.example.data.GitCommitDao
import com.example.data.McpServer
import com.example.data.McpServerDao
import com.example.data.McpToolDao
import com.example.data.McpToolEntity
import com.example.data.OllamaNode
import com.example.data.OllamaNodeDao
import com.example.data.SwarmConfig
import com.example.data.SwarmConfigDao
import com.example.data.SwarmTask
import com.example.data.SwarmTaskDao
import com.example.data.TaskStep
import com.example.data.TaskStepDao
import com.example.data.WorkspaceFile
import com.example.data.WorkspaceFileDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/**
 * Backs a fake DAO with an in-memory list, mirroring Room's actual semantics closely enough for
 * tests: [insertOrReplace] auto-assigns ids like `@Insert(REPLACE)` (id == 0 -> autogenerate,
 * id != 0 -> replace that row), [updateIfExists] is a silent no-op on a missing row like
 * `@Update`, and [deleteById]/[deleteAllById] match by primary key only like `@Delete` (not full
 * entity equality, so a stale-field copy still deletes correctly).
 */
private class InMemoryStore<T : Any>(
    seed: List<T> = emptyList(),
    private val getId: (T) -> Int,
    private val withId: (T, Int) -> T,
) {
    private val state = MutableStateFlow(seed)
    private val nextId = AtomicInteger((seed.maxOfOrNull(getId) ?: 0) + 1)

    fun flow(): Flow<List<T>> = state
    fun snapshot(): List<T> = state.value

    fun insertOrReplace(item: T): Long {
        val id = if (getId(item) != 0) getId(item) else nextId.getAndIncrement()
        val withAssignedId = withId(item, id)
        state.update { current -> current.filterNot { getId(it) == id } + withAssignedId }
        return id.toLong()
    }

    fun insertOrReplaceAll(items: List<T>) {
        items.forEach { insertOrReplace(it) }
    }

    fun updateIfExists(item: T) {
        state.update { current ->
            if (current.none { getId(it) == getId(item) }) current
            else current.map { if (getId(it) == getId(item)) item else it }
        }
    }

    fun deleteById(item: T) {
        state.update { current -> current.filterNot { getId(it) == getId(item) } }
    }

    fun deleteAllById(items: List<T>) {
        val ids = items.map(getId).toSet()
        state.update { current -> current.filterNot { getId(it) in ids } }
    }

    fun clear() {
        state.update { emptyList() }
    }
}

private class FakeOllamaNodeDao(seed: List<OllamaNode>) : OllamaNodeDao {
    private val store = InMemoryStore(seed, { it.id }, { item, id -> item.copy(id = id) })
    override fun getAllNodes(): Flow<List<OllamaNode>> = store.flow()
    override suspend fun getAllNodesSync(): List<OllamaNode> = store.snapshot()
    override suspend fun insertNode(node: OllamaNode): Long = store.insertOrReplace(node)
    override suspend fun updateNode(node: OllamaNode) = store.updateIfExists(node)
    override suspend fun deleteNode(node: OllamaNode) = store.deleteById(node)
}

private class FakeAgentDao(seed: List<Agent>) : AgentDao {
    private val store = InMemoryStore(seed, { it.id }, { item, id -> item.copy(id = id) })
    override fun getAllAgents(): Flow<List<Agent>> = store.flow()
    override suspend fun getAgentById(id: Int): Agent? = store.snapshot().find { it.id == id }
    override suspend fun getAgentsByIds(ids: List<Int>): List<Agent> = store.snapshot().filter { it.id in ids }
    override suspend fun insertAgent(agent: Agent): Long = store.insertOrReplace(agent)
    override suspend fun updateAgent(agent: Agent) = store.updateIfExists(agent)
    override suspend fun deleteAgent(agent: Agent) = store.deleteById(agent)
}

private class FakeSwarmConfigDao(seed: List<SwarmConfig>) : SwarmConfigDao {
    private val store = InMemoryStore(seed, { it.id }, { item, id -> item.copy(id = id) })
    override fun getAllSwarmConfigs(): Flow<List<SwarmConfig>> = store.flow()
    override suspend fun getSwarmConfigById(id: Int): SwarmConfig? = store.snapshot().find { it.id == id }
    override suspend fun insertSwarmConfig(config: SwarmConfig): Long = store.insertOrReplace(config)
    override suspend fun updateSwarmConfig(config: SwarmConfig) = store.updateIfExists(config)
    override suspend fun deleteSwarmConfig(config: SwarmConfig) = store.deleteById(config)
}

private class FakeSwarmTaskDao : SwarmTaskDao {
    private val store = InMemoryStore<SwarmTask>(emptyList(), { it.id }, { item, id -> item.copy(id = id) })
    override fun getAllTasks(): Flow<List<SwarmTask>> = store.flow()
    override suspend fun getTaskById(id: Int): SwarmTask? = store.snapshot().find { it.id == id }
    override suspend fun insertTask(task: SwarmTask): Long = store.insertOrReplace(task)
    override suspend fun updateTask(task: SwarmTask) = store.updateIfExists(task)
    override suspend fun deleteTask(task: SwarmTask) = store.deleteById(task)
}

private class FakeTaskStepDao : TaskStepDao {
    private val store = InMemoryStore<TaskStep>(emptyList(), { it.id }, { item, id -> item.copy(id = id) })
    override fun getStepsForTask(taskId: Int): Flow<List<TaskStep>> =
        store.flow().map { list -> list.filter { it.taskId == taskId } }
    override suspend fun getStepsForTaskSync(taskId: Int): List<TaskStep> =
        store.snapshot().filter { it.taskId == taskId }
    override suspend fun insertStep(step: TaskStep): Long = store.insertOrReplace(step)
    override suspend fun deleteStepsForTask(taskId: Int) {
        store.deleteAllById(store.snapshot().filter { it.taskId == taskId })
    }
}

private class FakeWorkspaceFileDao(seed: List<WorkspaceFile>) : WorkspaceFileDao {
    private val store = InMemoryStore(seed, { it.id }, { item, id -> item.copy(id = id) })
    override fun getAllFiles(): Flow<List<WorkspaceFile>> = store.flow()
    override suspend fun getFileById(id: Int): WorkspaceFile? = store.snapshot().find { it.id == id }
    override suspend fun getFileByPath(filePath: String): WorkspaceFile? =
        store.snapshot().find { it.filePath == filePath }
    override suspend fun insertFile(file: WorkspaceFile): Long = store.insertOrReplace(file)
    override suspend fun insertFilesBatch(files: List<WorkspaceFile>) = store.insertOrReplaceAll(files)
    override suspend fun updateFile(file: WorkspaceFile) = store.updateIfExists(file)
    override suspend fun deleteFile(file: WorkspaceFile) = store.deleteById(file)
    override suspend fun deleteFilesBatch(files: List<WorkspaceFile>) = store.deleteAllById(files)
    // syncFilesTransaction is left un-overridden: the interface's own default body already calls
    // insertFilesBatch() then deleteFilesBatch() in sequence, which is exactly right here too.
    override suspend fun deleteAllFiles() = store.clear()
}

private class FakeChatMessageDao(seed: List<ChatMessage>) : ChatMessageDao {
    private val store = InMemoryStore(seed, { it.id }, { item, id -> item.copy(id = id) })
    override fun getAllMessages(): Flow<List<ChatMessage>> = store.flow()
    override suspend fun getRecentMessagesSync(limit: Int): List<ChatMessage> =
        store.snapshot().sortedByDescending { it.timestamp }.take(limit)
    override suspend fun insertMessage(message: ChatMessage): Long = store.insertOrReplace(message)
    override suspend fun clearChat() = store.clear()
}

private class FakeGitCommitDao : GitCommitDao {
    private val store = InMemoryStore<GitCommit>(emptyList(), { it.id }, { item, id -> item.copy(id = id) })
    override fun getAllCommits(): Flow<List<GitCommit>> = store.flow()
    override suspend fun insertCommit(commit: GitCommit): Long = store.insertOrReplace(commit)
    override suspend fun clearCommits() = store.clear()
}

private class FakeMcpToolDao : McpToolDao {
    private val store = InMemoryStore<McpToolEntity>(emptyList(), { it.id }, { item, id -> item.copy(id = id) })
    override fun getToolsForServer(serverId: Int): Flow<List<McpToolEntity>> =
        store.flow().map { list -> list.filter { it.serverId == serverId } }
    override suspend fun getToolsForServerSync(serverId: Int): List<McpToolEntity> =
        store.snapshot().filter { it.serverId == serverId }
    override suspend fun getToolByName(toolName: String): McpToolEntity? =
        store.snapshot().find { it.name == toolName }
    override suspend fun insertTool(tool: McpToolEntity): Long = store.insertOrReplace(tool)
    override suspend fun insertTools(tools: List<McpToolEntity>) = store.insertOrReplaceAll(tools)
    override suspend fun deleteToolsForServer(serverId: Int) {
        store.deleteAllById(store.snapshot().filter { it.serverId == serverId })
    }
    override suspend fun deleteAllTools() = store.clear()
}

private class FakeMcpServerDao(seed: List<McpServer>) : McpServerDao {
    private val store = InMemoryStore(seed, { it.id }, { item, id -> item.copy(id = id) })
    override fun getAllServers(): Flow<List<McpServer>> = store.flow()
    override suspend fun getAllServersSync(): List<McpServer> = store.snapshot()
    override suspend fun getServerById(id: Int): McpServer? = store.snapshot().find { it.id == id }
    override suspend fun insertServer(server: McpServer): Long = store.insertOrReplace(server)
    override suspend fun updateServer(server: McpServer) = store.updateIfExists(server)
    override suspend fun deleteServer(server: McpServer) = store.deleteById(server)
    override suspend fun clearAllServers() = store.clear()
}

private class FakeClaudeSkillDao(seed: List<ClaudeSkill>) : ClaudeSkillDao {
    private val store = InMemoryStore(seed, { it.id }, { item, id -> item.copy(id = id) })
    override fun getAllSkills(): Flow<List<ClaudeSkill>> = store.flow()
    override suspend fun getAllSkillsSync(): List<ClaudeSkill> = store.snapshot()
    override fun getRecommendedSkills(): Flow<List<ClaudeSkill>> =
        store.flow().map { list -> list.filter { it.isRecommended } }
    override suspend fun insertSkill(skill: ClaudeSkill): Long = store.insertOrReplace(skill)
    override suspend fun updateSkill(skill: ClaudeSkill) = store.updateIfExists(skill)
    override suspend fun setSkillEnabled(id: Int, isEnabled: Boolean) {
        store.snapshot().find { it.id == id }?.let { store.updateIfExists(it.copy(isEnabled = isEnabled)) }
    }
    override suspend fun updateRecommendationByServerType(mcpType: String, isRec: Boolean) {
        store.snapshot().filter { it.requiredMcpServerType == mcpType }
            .forEach { store.updateIfExists(it.copy(isRecommended = isRec)) }
    }
    override suspend fun setSkillEnabledByServerType(mcpType: String, isEnabled: Boolean) {
        store.snapshot().filter { it.requiredMcpServerType == mcpType }
            .forEach { store.updateIfExists(it.copy(isEnabled = isEnabled)) }
    }
    override suspend fun getSkillBySourceToolName(toolName: String): ClaudeSkill? =
        store.snapshot().find { it.sourceToolName == toolName }
    override suspend fun deleteAutoGeneratedSkillsByServerType(serverType: String) {
        store.deleteAllById(
            store.snapshot().filter { it.sourceToolName != null && it.requiredMcpServerType == serverType }
        )
    }
    override suspend fun clearAllSkills() = store.clear()
}

// Seed data ported verbatim from AppDatabase.DatabaseSeederCallback's raw SQL, so tests that
// assert against seeded names (e.g. "SDLC Spec & Design Swarm", "Local Node (Loopback)") keep
// working unchanged. swarm_tasks/task_steps/git_commits/mcp_tools start empty, matching production.

private fun seedAgents() = listOf(
    Agent(id = 1, name = "Spec Architect", role = "Product Manager", modelName = "deepseek-v4-pro", systemPrompt = "You are an expert Product Manager and Spec Architect. Your job is to translate user requirements into detailed, structured, and clear feature specifications and user stories.", colorHex = "#9C27B0", isSystemTemplate = true),
    Agent(id = 2, name = "Byte Code", role = "Programmer", modelName = "qwen3-coder:480b", systemPrompt = "You are an elite, concise software engineer. Write pristine, commented, and performant code based on prompt requirements and architectural guidelines.", colorHex = "#4CAF50", isSystemTemplate = true),
    Agent(id = 3, name = "Aura Critic", role = "Critic", modelName = "kimi-k2.6", systemPrompt = "You are a critical code reviewer. Analyze code changes, test suites, and pull requests for potential logical loopholes, edge cases, bugs, or architectural violations.", colorHex = "#E91E63", isSystemTemplate = true),
    Agent(id = 4, name = "Core Architect", role = "Architect", modelName = "gpt-oss:120b", systemPrompt = "You are a Senior System Architect. Your job is to design robust system architectures, define clean API contracts, design database schemas, and establish implementation patterns.", colorHex = "#2196F3", isSystemTemplate = true),
    Agent(id = 5, name = "Bug Hunter", role = "QA Engineer", modelName = "deepseek-v4-pro", systemPrompt = "You are an automated QA & Testing Specialist. Your job is to design comprehensive unit/integration test plans, write JUnit/Compose tests, and execute test suites.", colorHex = "#FF9800", isSystemTemplate = true),
    Agent(id = 6, name = "Shield Guard", role = "Security Auditor", modelName = "kimi-k2.6", systemPrompt = "You are a DevSecOps Security Auditor. Your job is to analyze code for security vulnerabilities, OWASP Top 10 issues, hardcoded credentials, and package dependencies risks.", colorHex = "#F44336", isSystemTemplate = true),
    Agent(id = 7, name = "Pipeline Deployer", role = "DevOps Engineer", modelName = "qwen3-coder:480b", systemPrompt = "You are a DevOps and Release Engineer. Your job is to configure CI/CD pipelines, compose Dockerfiles, write Gradle deployment tasks, and monitor build outputs.", colorHex = "#009688", isSystemTemplate = true)
)

private fun seedSwarmConfigs() = listOf(
    SwarmConfig(id = 1, name = "SDLC Spec & Design Swarm", description = "Product Spec Architect gathers details and designs the specification, then Tech Lead drafts the architecture, and Critic audits for technical constraints.", coordinationMode = "SEQUENTIAL", agentIds = "1,4,3"),
    SwarmConfig(id = 2, name = "Feature Implementation Swarm", description = "Core Architect defines API contracts, Byte Code implements code, and Bug Hunter writes unit/integration tests to ensure full coverage.", coordinationMode = "PEER_TO_PEER", agentIds = "4,2,5"),
    SwarmConfig(id = 3, name = "SecOps Build & Release Swarm", description = "Byte Code edits files, Shield Guard performs security audits, and Pipeline Deployer runs the CI build and stages deployment configurations.", coordinationMode = "SEQUENTIAL", agentIds = "2,6,7"),
    SwarmConfig(id = 4, name = "Full Auto-SDLC Swarm", description = "An end-to-end SDLC pipeline: Spec Architect designs, Core Architect structures, Byte Code implements, Bug Hunter tests, Shield Guard audits, and Pipeline Deployer builds.", coordinationMode = "SEQUENTIAL", agentIds = "1,4,2,5,6,7"),
    SwarmConfig(id = 5, name = "Adaptive Dynamic Routing Swarm", description = "Orchestrator analyzes requirements at runtime, selects the optimal agents, builds a dynamic routing path, and synthesizes the final outputs.", coordinationMode = "DYNAMIC_ROUTING", agentIds = "1,4,2,3,5"),
    SwarmConfig(id = 6, name = "Autonomous Coding Harness", description = "A Replit-Agent/Manus-AI-style autonomous plan-act-verify loop: Core Architect plans and assigns roles, Byte Code implements each step, and Bug Hunter verifies every step (invoking real MCP tooling when available) before checkpointing progress.", coordinationMode = "AGENTIC_LOOP", agentIds = "4,2,5")
)

private fun seedNodes() = listOf(
    OllamaNode(id = 1, name = "Local Node (Loopback)", url = "http://127.0.0.1:11434", status = "Offline", availableModels = "llama3, mistral, phi3", latencyMs = -1),
    OllamaNode(id = 2, name = "Decentralized Swarm-Peer A", url = "http://192.168.1.154:11434", status = "Offline", availableModels = "llama3, mistral", latencyMs = -1),
    OllamaNode(id = 3, name = "Autonomous Edge Node B", url = "http://10.0.0.42:11434", status = "Offline", availableModels = "phi3", latencyMs = -1),
    OllamaNode(id = 4, name = "Ollama Cloud Gateway", url = "https://ollama.com", status = "Offline", availableModels = "gpt-oss:120b, qwen3-coder:480b, glm-5.2, kimi-k2.6, deepseek-v4-pro, minimax-m3", latencyMs = -1)
)

private fun seedWorkspaceFiles() = listOf(
    WorkspaceFile(
        id = 1,
        filePath = "workspace/auth_spec.md",
        content = "# User Authentication Specification\n\n## Requirements\n- Register a new user with username and password.\n- Authenticate user credentials and return a stateless JWT token.\n- Secure token validation on API endpoints.\n\n## Architecture\n- /register -> Store username + salted password hash.\n- /login -> Generate signed JWT token with 1-hour expiry.",
        lastModified = 1718010000000,
        isConflict = false
    ),
    WorkspaceFile(
        id = 2,
        filePath = "auth.py",
        content = "import jwt\nimport datetime\n\nSECRET = \"super-secret-swarm-key\"\n\ndef generate_token(username):\n    payload = {\n        \"sub\": username,\n        \"exp\": datetime.datetime.utcnow() + datetime.timedelta(hours=1)\n    }\n    return jwt.encode(payload, SECRET, algorithm=\"HS256\")\n\ndef verify_token(token):\n    try:\n        return jwt.decode(token, SECRET, algorithms=[\"HS256\"])\n    except jwt.ExpiredSignatureError:\n        return \"Token expired\"\n    except jwt.InvalidTokenError:\n        return \"Invalid token\"",
        lastModified = 1718012000000,
        isConflict = false
    ),
    WorkspaceFile(
        id = 3,
        filePath = "tests/test_auth.py",
        content = "import unittest\nfrom auth import generate_token, verify_token\n\nclass TestAuth(unittest.TestCase):\n    def test_token_valid(self):\n        token = generate_token(\"admin\")\n        payload = verify_token(token)\n        self.assertEqual(payload[\"sub\"], \"admin\")\n\nif __name__ == \"__main__\":\n    unittest.main()",
        lastModified = 1718014000000,
        isConflict = false
    )
)

private fun seedChatMessages() = listOf(
    ChatMessage(id = 1, sender = "System", role = "system", message = "Decentralized Swarm SDLC Automation channel initialized. Active swarm configurations are ready to accept tasks.", timestamp = 1718010000000, colorHex = "#9E9E9E"),
    ChatMessage(id = 2, sender = "Spec Architect", role = "agent", message = "Auth module specifications drafted in workspace/auth_spec.md. Core Architect, please define the service boundaries.", timestamp = 1718010100000, colorHex = "#9C27B0"),
    ChatMessage(id = 3, sender = "Core Architect", role = "agent", message = "Auth specs analyzed. We will build a stateless JWT-based service. Byte Code, implement the token generation and validation logic in auth.py.", timestamp = 1718010200000, colorHex = "#2196F3"),
    ChatMessage(id = 4, sender = "Byte Code", role = "agent", message = "Understood, starting work on auth.py with SHA-256 signatures.", timestamp = 1718010300000, colorHex = "#4CAF50"),
    ChatMessage(id = 5, sender = "Bug Hunter", role = "agent", message = "I am preparing unit tests for token expiration and signature manipulation payloads.", timestamp = 1718010400000, colorHex = "#FF9800")
)

private fun seedMcpServers() = listOf(
    McpServer(id = 1, name = "Local MCP Gateway", type = "Gateway", sourceUrl = "http://localhost:3000/mcp", status = "Disconnected", toolsCount = 0, configuredParams = "{}"),
    McpServer(id = 2, name = "SearXNG Search Bridge", type = "Search", sourceUrl = "http://localhost:3002/mcp", status = "Disconnected", toolsCount = 0, configuredParams = "{}"),
    McpServer(id = 3, name = "Private GitHub MCP", type = "GitHub", sourceUrl = "http://localhost:3003/mcp", status = "Disconnected", toolsCount = 0, configuredParams = "{\"repo\":\"owner/repo\"}"),
    McpServer(id = 4, name = "Workspace Postgres", type = "Database", sourceUrl = "http://localhost:3004/mcp", status = "Disconnected", toolsCount = 0, configuredParams = "{\"connectionString\":\"postgresql://localhost:5432/app\"}"),
    McpServer(id = 5, name = "Browser Automation", type = "Browser", sourceUrl = "http://localhost:3005/mcp", status = "Disconnected", toolsCount = 0, configuredParams = "{\"headless\":true}")
)

private fun seedClaudeSkills() = listOf(
    ClaudeSkill(id = 1, name = "GitHub Search & Pull", description = "Search and manage issues, pull requests, and repositories using the GitHub MCP client.", category = "Development", isRecommended = true, isEnabled = true, usageExample = "Search pull requests with query \"bugfix\" in example-repo", requiredMcpServerType = "GitHub", sourceToolName = null),
    ClaudeSkill(id = 2, name = "Docker Lifecycle Monitor", description = "Monitor docker containers, list active tasks, view performance graphs, and inspect configurations.", category = "Automation", isRecommended = true, isEnabled = true, usageExample = "List running docker containers and show port mappings", requiredMcpServerType = "Docker", sourceToolName = null),
    ClaudeSkill(id = 3, name = "Postgres SQL Optimizer", description = "Examine Postgres schemas, run EXPLAIN queries, and automatically optimize database indexes.", category = "Analysis", isRecommended = false, isEnabled = false, usageExample = "Analyze table \"users\" and recommend optimal column indexes", requiredMcpServerType = "Database", sourceToolName = null),
    ClaudeSkill(id = 4, name = "Automated Integration Tester", description = "Simulate end-to-end user actions in headful browser environments using Puppeteer scripts.", category = "Automation", isRecommended = true, isEnabled = false, usageExample = "Run Puppeteer E2E tests against live dev server on localhost:3000", requiredMcpServerType = "Browser", sourceToolName = null),
    ClaudeSkill(id = 5, name = "Code Linter & Formatter", description = "Fast sandboxed syntax check, format, and style linting on code blocks prior to commits.", category = "Development", isRecommended = true, isEnabled = true, usageExample = "Lint current active python or JS files with formatting recommendations", requiredMcpServerType = "None", sourceToolName = null),
    ClaudeSkill(id = 6, name = "Research Web Crawler", description = "Leverage Brave / Google Search MCP server to browse, clean, and summarize web documentation.", category = "Productivity", isRecommended = true, isEnabled = false, usageExample = "Summarize latest Jetpack Compose Room integration features", requiredMcpServerType = "None", sourceToolName = null),
    ClaudeSkill(id = 7, name = "Git Branch Creator", description = "Create and check out new local Git branches for code tasks.", category = "Development", isRecommended = true, isEnabled = true, usageExample = "git branch feature/auth-fix", requiredMcpServerType = "None", sourceToolName = null),
    ClaudeSkill(id = 8, name = "Git Auto-Stager & Committer", description = "Stage modified files and write clean conventional commits automatically.", category = "Development", isRecommended = true, isEnabled = true, usageExample = "git commit -am \"feat: add user authentication tokens\"", requiredMcpServerType = "None", sourceToolName = null),
    ClaudeSkill(id = 9, name = "Automated Compiler Self-Healer", description = "Verify Kotlin compilation and run auto-repair loops on code errors.", category = "Automation", isRecommended = true, isEnabled = true, usageExample = "Check kotlin compilation syntax and run healing loop", requiredMcpServerType = "None", sourceToolName = null),
    ClaudeSkill(id = 10, name = "Gradle Test Runner", description = "Run local Gradle test tasks and parse trace reports.", category = "Automation", isRecommended = true, isEnabled = true, usageExample = "gradle test :app", requiredMcpServerType = "None", sourceToolName = null)
)

/**
 * In-memory stand-in for [com.example.data.AppDatabase]. Robolectric has no SQLite support -- in
 * either native or legacy mode -- on linux-aarch64, so tests must never touch real Room; this
 * reproduces the same seeded rows the real seeder inserts so existing assertions keep working.
 */
class FakeAppDatabase : AppDatabaseInterface {
    private val ollamaNodeDaoImpl = FakeOllamaNodeDao(seedNodes())
    private val agentDaoImpl = FakeAgentDao(seedAgents())
    private val swarmConfigDaoImpl = FakeSwarmConfigDao(seedSwarmConfigs())
    private val swarmTaskDaoImpl = FakeSwarmTaskDao()
    private val taskStepDaoImpl = FakeTaskStepDao()
    private val workspaceFileDaoImpl = FakeWorkspaceFileDao(seedWorkspaceFiles())
    private val chatMessageDaoImpl = FakeChatMessageDao(seedChatMessages())
    private val gitCommitDaoImpl = FakeGitCommitDao()
    private val mcpServerDaoImpl = FakeMcpServerDao(seedMcpServers())
    private val mcpToolDaoImpl = FakeMcpToolDao()
    private val claudeSkillDaoImpl = FakeClaudeSkillDao(seedClaudeSkills())

    override fun ollamaNodeDao(): OllamaNodeDao = ollamaNodeDaoImpl
    override fun agentDao(): AgentDao = agentDaoImpl
    override fun swarmConfigDao(): SwarmConfigDao = swarmConfigDaoImpl
    override fun swarmTaskDao(): SwarmTaskDao = swarmTaskDaoImpl
    override fun taskStepDao(): TaskStepDao = taskStepDaoImpl
    override fun workspaceFileDao(): WorkspaceFileDao = workspaceFileDaoImpl
    override fun chatMessageDao(): ChatMessageDao = chatMessageDaoImpl
    override fun gitCommitDao(): GitCommitDao = gitCommitDaoImpl
    override fun mcpServerDao(): McpServerDao = mcpServerDaoImpl
    override fun mcpToolDao(): McpToolDao = mcpToolDaoImpl
    override fun claudeSkillDao(): ClaudeSkillDao = claudeSkillDaoImpl
}
