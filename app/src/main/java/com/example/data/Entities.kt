package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ollama_nodes")
data class OllamaNode(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val url: String,
    val status: String = "Offline", // Online, Offline, Connecting
    val availableModels: String = "llama3, mistral, phi3", // Comma-separated list
    val latencyMs: Int = -1,
    val apiKey: String? = null // Bearer token for authenticated endpoints (e.g. Ollama Cloud)
)

@Entity(tableName = "agents")
data class Agent(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val role: String, // Researcher, Programmer, Critic, Executive, Writer, etc.
    val modelName: String, // e.g., llama3:8b, mistral:7b, phi3, gemini-3.5-flash
    val systemPrompt: String,
    val colorHex: String, // Hex color code for agent's theme
    val isSystemTemplate: Boolean = false
)

@Entity(tableName = "swarm_configs")
data class SwarmConfig(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val description: String,
    val coordinationMode: String, // SEQUENTIAL, PEER_TO_PEER, CONSENSUS_VOTE
    val agentIds: String // Comma-separated agent IDs (e.g., "1,2,3")
)

@Entity(tableName = "swarm_tasks")
data class SwarmTask(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val prompt: String,
    val status: String, // Pending, Thinking, Debating, Completed, Failed
    val timestamp: Long = System.currentTimeMillis(),
    val result: String = "",
    val swarmName: String,
    val executionTimeMs: Long = 0,
    val tokenUsage: Int = 0
)

@Entity(tableName = "task_steps")
data class TaskStep(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val taskId: Int,
    val agentName: String,
    val agentRole: String,
    val actionType: String, // THINKING, EXECUTING, CRITIQUING, VOTING, FINAL_RESPONSE
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0
)

@Entity(tableName = "workspace_files")
data class WorkspaceFile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val filePath: String,
    val content: String,
    val lastModified: Long = System.currentTimeMillis(),
    val sourceUri: String? = null,
    val isConflict: Boolean = false,
    val conflictContent: String? = null
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val sender: String,
    val role: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val colorHex: String = "#3F51B5"
)

@Entity(tableName = "git_commits")
data class GitCommit(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val commitHash: String,
    val author: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "mcp_servers")
data class McpServer(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val type: String, // e.g., "GitHub", "Docker", "Database", "Slack", "Filesystem"
    val sourceUrl: String, // GitHub URL or Docker hub identifier
    val status: String = "Disconnected", // "Connected", "Disconnected", "Connecting", "Error"
    val toolsCount: Int = 0,
    val configuredParams: String = "{}" // Configuration parameters in JSON
)

@Entity(tableName = "mcp_tools")
data class McpToolEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val serverId: Int,
    val name: String,
    val description: String?,
    val inputSchemaJson: String = "{}",
    val outputSchemaJson: String? = null,
    val annotationsJson: String? = null
)

@Entity(tableName = "claude_skills")
data class ClaudeSkill(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val description: String,
    val category: String, // "Development", "Productivity", "Analysis", "Automation"
    val isRecommended: Boolean = false,
    val isEnabled: Boolean = false,
    val usageExample: String = "",
    val requiredMcpServerType: String = "None",
    val sourceToolName: String? = null // Binds this skill to a real MCP tool name
)

