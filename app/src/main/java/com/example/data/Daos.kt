package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface OllamaNodeDao {
    @Query("SELECT * FROM ollama_nodes ORDER BY name ASC")
    fun getAllNodes(): Flow<List<OllamaNode>>

    @Query("SELECT * FROM ollama_nodes ORDER BY name ASC")
    suspend fun getAllNodesSync(): List<OllamaNode>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNode(node: OllamaNode): Long

    @Update
    suspend fun updateNode(node: OllamaNode)

    @Delete
    suspend fun deleteNode(node: OllamaNode)
}

@Dao
interface AgentDao {
    @Query("SELECT * FROM agents ORDER BY name ASC")
    fun getAllAgents(): Flow<List<Agent>>

    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun getAgentById(id: Int): Agent?

    @Query("SELECT * FROM agents WHERE id IN (:ids)")
    suspend fun getAgentsByIds(ids: List<Int>): List<Agent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgent(agent: Agent): Long

    @Update
    suspend fun updateAgent(agent: Agent)

    @Delete
    suspend fun deleteAgent(agent: Agent)
}

@Dao
interface SwarmConfigDao {
    @Query("SELECT * FROM swarm_configs ORDER BY name ASC")
    fun getAllSwarmConfigs(): Flow<List<SwarmConfig>>

    @Query("SELECT * FROM swarm_configs WHERE id = :id")
    suspend fun getSwarmConfigById(id: Int): SwarmConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSwarmConfig(config: SwarmConfig): Long

    @Update
    suspend fun updateSwarmConfig(config: SwarmConfig)

    @Delete
    suspend fun deleteSwarmConfig(config: SwarmConfig)
}

@Dao
interface SwarmTaskDao {
    @Query("SELECT * FROM swarm_tasks ORDER BY timestamp DESC")
    fun getAllTasks(): Flow<List<SwarmTask>>

    @Query("SELECT * FROM swarm_tasks WHERE id = :id")
    suspend fun getTaskById(id: Int): SwarmTask?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: SwarmTask): Long

    @Update
    suspend fun updateTask(task: SwarmTask)

    @Delete
    suspend fun deleteTask(task: SwarmTask)
}

@Dao
interface TaskStepDao {
    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY timestamp ASC")
    fun getStepsForTask(taskId: Int): Flow<List<TaskStep>>

    @Query("SELECT * FROM task_steps WHERE taskId = :taskId ORDER BY timestamp ASC")
    suspend fun getStepsForTaskSync(taskId: Int): List<TaskStep>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStep(step: TaskStep): Long

    @Query("DELETE FROM task_steps WHERE taskId = :taskId")
    suspend fun deleteStepsForTask(taskId: Int)
}

@Dao
interface WorkspaceFileDao {
    @Query("SELECT * FROM workspace_files ORDER BY filePath ASC")
    fun getAllFiles(): Flow<List<WorkspaceFile>>

    @Query("SELECT * FROM workspace_files WHERE id = :id")
    suspend fun getFileById(id: Int): WorkspaceFile?

    @Query("SELECT * FROM workspace_files WHERE filePath = :filePath")
    suspend fun getFileByPath(filePath: String): WorkspaceFile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: WorkspaceFile): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFilesBatch(files: List<WorkspaceFile>)

    @Update
    suspend fun updateFile(file: WorkspaceFile)

    @Delete
    suspend fun deleteFile(file: WorkspaceFile)

    @Delete
    suspend fun deleteFilesBatch(files: List<WorkspaceFile>)

    @Transaction
    suspend fun syncFilesTransaction(filesToInsert: List<WorkspaceFile>, filesToDelete: List<WorkspaceFile>) {
        insertFilesBatch(filesToInsert)
        deleteFilesBatch(filesToDelete)
    }

    @Query("DELETE FROM workspace_files")
    suspend fun deleteAllFiles()
}

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<ChatMessage>>

    // Most-recent-first, capped at :limit -- used to build a bounded context window of prior
    // turns for Session's REPL-style memory (see SwarmViewModel.runSwarmFromSession).
    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessagesSync(limit: Int): List<ChatMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage): Long

    @Query("DELETE FROM chat_messages")
    suspend fun clearChat()
}

@Dao
interface GitCommitDao {
    @Query("SELECT * FROM git_commits ORDER BY timestamp DESC")
    fun getAllCommits(): Flow<List<GitCommit>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCommit(commit: GitCommit): Long

    @Query("DELETE FROM git_commits")
    suspend fun clearCommits()
}

@Dao
interface McpToolDao {
    @Query("SELECT * FROM mcp_tools WHERE serverId = :serverId ORDER BY name ASC")
    fun getToolsForServer(serverId: Int): Flow<List<McpToolEntity>>

    @Query("SELECT * FROM mcp_tools WHERE serverId = :serverId ORDER BY name ASC")
    suspend fun getToolsForServerSync(serverId: Int): List<McpToolEntity>

    @Query("SELECT * FROM mcp_tools WHERE name = :toolName LIMIT 1")
    suspend fun getToolByName(toolName: String): McpToolEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTool(tool: McpToolEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTools(tools: List<McpToolEntity>)

    @Query("DELETE FROM mcp_tools WHERE serverId = :serverId")
    suspend fun deleteToolsForServer(serverId: Int)

    @Query("DELETE FROM mcp_tools")
    suspend fun deleteAllTools()
}

@Dao
interface McpServerDao {
    @Query("SELECT * FROM mcp_servers ORDER BY name ASC")
    fun getAllServers(): Flow<List<McpServer>>

    @Query("SELECT * FROM mcp_servers")
    suspend fun getAllServersSync(): List<McpServer>

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    suspend fun getServerById(id: Int): McpServer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: McpServer): Long

    @Update
    suspend fun updateServer(server: McpServer)

    @Delete
    suspend fun deleteServer(server: McpServer)

    @Query("DELETE FROM mcp_servers")
    suspend fun clearAllServers()
}

@Dao
interface ClaudeSkillDao {
    @Query("SELECT * FROM claude_skills ORDER BY name ASC")
    fun getAllSkills(): Flow<List<ClaudeSkill>>

    @Query("SELECT * FROM claude_skills")
    suspend fun getAllSkillsSync(): List<ClaudeSkill>

    @Query("SELECT * FROM claude_skills WHERE isRecommended = 1")
    fun getRecommendedSkills(): Flow<List<ClaudeSkill>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSkill(skill: ClaudeSkill): Long

    @Update
    suspend fun updateSkill(skill: ClaudeSkill)

    @Query("UPDATE claude_skills SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun setSkillEnabled(id: Int, isEnabled: Boolean)

    @Query("UPDATE claude_skills SET isRecommended = :isRec WHERE requiredMcpServerType = :mcpType")
    suspend fun updateRecommendationByServerType(mcpType: String, isRec: Boolean)

    @Query("UPDATE claude_skills SET isEnabled = :isEnabled WHERE requiredMcpServerType = :mcpType")
    suspend fun setSkillEnabledByServerType(mcpType: String, isEnabled: Boolean)

    @Query("SELECT * FROM claude_skills WHERE sourceToolName = :toolName LIMIT 1")
    suspend fun getSkillBySourceToolName(toolName: String): ClaudeSkill?

    @Query("DELETE FROM claude_skills WHERE sourceToolName IS NOT NULL AND requiredMcpServerType = :serverType")
    suspend fun deleteAutoGeneratedSkillsByServerType(serverType: String)

    @Query("DELETE FROM claude_skills")
    suspend fun clearAllSkills()
}

