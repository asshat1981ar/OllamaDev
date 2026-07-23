package com.example.data

/**
 * Testable abstraction over [AppDatabase]. Production code delegates to a real Room-backed
 * [AppDatabase]; tests can provide an in-memory fake that avoids real SQLite entirely
 * (Robolectric has no SQLite support -- native or legacy -- on linux-aarch64).
 */
interface AppDatabaseInterface {
    fun ollamaNodeDao(): OllamaNodeDao
    fun agentDao(): AgentDao
    fun swarmConfigDao(): SwarmConfigDao
    fun swarmTaskDao(): SwarmTaskDao
    fun taskStepDao(): TaskStepDao
    fun workspaceFileDao(): WorkspaceFileDao
    fun chatMessageDao(): ChatMessageDao
    fun gitCommitDao(): GitCommitDao
    fun mcpServerDao(): McpServerDao
    fun mcpToolDao(): McpToolDao
    fun claudeSkillDao(): ClaudeSkillDao
}
