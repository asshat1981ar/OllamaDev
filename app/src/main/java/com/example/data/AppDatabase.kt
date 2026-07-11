package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        OllamaNode::class, 
        Agent::class, 
        SwarmConfig::class, 
        SwarmTask::class, 
        TaskStep::class,
        WorkspaceFile::class,
        ChatMessage::class,
        GitCommit::class,
        McpServer::class,
        ClaudeSkill::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ollamaNodeDao(): OllamaNodeDao
    abstract fun agentDao(): AgentDao
    abstract fun swarmConfigDao(): SwarmConfigDao
    abstract fun swarmTaskDao(): SwarmTaskDao
    abstract fun taskStepDao(): TaskStepDao
    abstract fun workspaceFileDao(): WorkspaceFileDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun gitCommitDao(): GitCommitDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun claudeSkillDao(): ClaudeSkillDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ollama_swarm_database"
                )
                .fallbackToDestructiveMigration()
                .addCallback(DatabaseSeederCallback())
                .build()
                INSTANCE = instance
                instance
            }
        }
    }

    private class DatabaseSeederCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            
            // Seed Agents
            db.execSQL(
                """
                INSERT INTO agents (id, name, role, modelName, systemPrompt, colorHex, isSystemTemplate) VALUES 
                (1, 'Apex Researcher', 'Researcher', 'llama3:8b', 'You are a professional research agent specializing in exhaustive, accurate, and structured inquiries. Your job is to gather and present clear information.', '#3F51B5', 1),
                (2, 'Byte Code', 'Programmer', 'codegemma:7b', 'You are an elite, concise software engineer. Write pristine, commented, and performant code based on prompt requirements.', '#4CAF50', 1),
                (3, 'Aura Critic', 'Critic', 'mistral:7b', 'You are a critical reviewer. Analyze the researcher''s output and the programmer''s code for potential logical loopholes, edge cases, bugs, or omissions.', '#E91E63', 1),
                (4, 'Synthesizer', 'Executive', 'gemma2:9b', 'You are an executive coordinator agent. Your job is to synthesize conflicting opinions, edit draft reports, and make the final polished response.', '#FF9800', 1)
                """.trimIndent()
            )

            // Seed Swarm Configurations
            db.execSQL(
                """
                INSERT INTO swarm_configs (id, name, description, coordinationMode, agentIds) VALUES 
                (1, 'Research & Synthesize Swarm', 'A highly organized pipeline where Researcher gathers raw data, Critic audits it, and Synthesizer crafts the final comprehensive report.', 'SEQUENTIAL', '1,3,4'),
                (2, 'Elite Code Engineer Swarm', 'A specialized collaborative engine where Researcher plans the algorithm, Byte Code implements the logic, Aura Critic tests it, and Synthesizer formats it.', 'PEER_TO_PEER', '1,2,3,4'),
                (3, 'Decentralized Consensus Swarm', 'A flat collaborative group that analyzes tasks in parallel and runs a consensus voting mechanism to choose the best option.', 'CONSENSUS_VOTE', '1,2,3')
                """.trimIndent()
            )

            // Seed Ollama Nodes
            db.execSQL(
                """
                INSERT INTO ollama_nodes (id, name, url, status, availableModels) VALUES 
                (1, 'Local Node (Loopback)', 'http://127.0.0.1:11434', 'Offline', 'llama3, mistral, phi3'),
                (2, 'Decentralized Swarm-Peer A', 'http://192.168.1.154:11434', 'Online', 'llama3, mistral'),
                (3, 'Autonomous Edge Node B', 'http://10.0.0.42:11434', 'Online', 'phi3'),
                (4, 'Ollama Cloud Gateway', 'https://api.ollamacloud.com', 'Online', 'llama3:8b, mistral:7b, phi3, qwen2:7b, gemma2:9b, codegemma:7b')
                """.trimIndent()
            )

            // Seed Workspace Files
            db.execSQL(
                """
                INSERT INTO workspace_files (id, filePath, content, lastModified) VALUES 
                (1, 'README.md', '# Swarm Intelligence Workspace\n\nWelcome to your decentralized agent workspace! Here, you can write code, collaborate with your autonomous swarm, and deploy changes directly via Git.\n\n### Current Stack\n- Autonomous coordination mode: PEER_TO_PEER\n- Active local Node: Local Node (Loopback)\n- Active Agents: Researcher, Programmer, Critic, Executive', 1718010000000),
                (2, 'main.py', 'def coordinate_swarm(agents, task):\n    print(f"Initializing swarm coordination for: {task}")\n    proposals = []\n    for agent in agents:\n        proposal = agent.propose(task)\n        proposals.append(proposal)\n    \n    consensus = synthesize_proposals(proposals)\n    return consensus\n\ndef synthesize_proposals(proposals):\n    print("Criticizing and merging code proposals...")\n    return "def consolidated_result():\n    return True"', 1718012000000),
                (3, 'agent_config.json', '{\n  "swarm_codename": "project-nebula",\n  "version": "1.0.4",\n  "security_mode": "strict",\n  "auto_commit": false\n}', 1718014000000)
                """.trimIndent()
            )

            // Seed Chat Messages
            db.execSQL(
                """
                INSERT INTO chat_messages (id, sender, role, message, timestamp, colorHex) VALUES 
                (1, 'System', 'system', 'Decentralized Swarm Chat channel initialized. You can chat directly with your active swarm configurations.', 1718010000000, '#9E9E9E'),
                (2, 'Apex Researcher', 'agent', 'Hello! I am ready to research codebase modifications or answer queries. Ask me anything about the repository.', 1718010100000, '#3F51B5'),
                (3, 'Byte Code', 'agent', 'System online. Point me to any file in the browser, and I can refactor or optimize it.', 1718010200000, '#4CAF50')
                """.trimIndent()
            )

            // Seed MCP Servers
            db.execSQL(
                """
                INSERT INTO mcp_servers (id, name, type, sourceUrl, status, toolsCount, configuredParams) VALUES
                (1, 'GitHub MCP Integration', 'GitHub', 'https://github.com/modelcontextprotocol/servers/tree/main/src/github', 'Connected', 8, '{"repo":"example-repo","token":"ghp_xxxxxxxxxxxx"}'),
                (2, 'Docker Container Engine', 'Docker', 'https://github.com/modelcontextprotocol/servers/tree/main/src/docker', 'Connected', 5, '{"host":"unix:///var/run/docker.sock"}'),
                (3, 'PostgreSQL Database Analyzer', 'Database', 'https://github.com/modelcontextprotocol/servers/tree/main/src/postgres', 'Disconnected', 6, '{"connectionString":"postgresql://localhost:5432/production"}'),
                (4, 'Puppeteer Browser Automation', 'Docker', 'https://github.com/modelcontextprotocol/servers/tree/main/src/puppeteer', 'Disconnected', 4, '{"headless":true}')
                """.trimIndent()
            )

            // Seed Claude Skills
            db.execSQL(
                """
                INSERT INTO claude_skills (id, name, description, category, isRecommended, isEnabled, usageExample, requiredMcpServerType) VALUES
                (1, 'GitHub Search & Pull', 'Search and manage issues, pull requests, and repositories using the GitHub MCP client.', 'Development', 1, 1, 'Search pull requests with query "bugfix" in example-repo', 'GitHub'),
                (2, 'Docker Lifecycle Monitor', 'Monitor docker containers, list active tasks, view performance graphs, and inspect configurations.', 'Automation', 1, 1, 'List running docker containers and show port mappings', 'Docker'),
                (3, 'Postgres SQL Optimizer', 'Examine Postgres schemas, run EXPLAIN queries, and automatically optimize database indexes.', 'Analysis', 0, 0, 'Analyze table "users" and recommend optimal column indexes', 'Database'),
                (4, 'Automated Integration Tester', 'Simulate end-to-end user actions in headful browser environments using Puppeteer scripts.', 'Automation', 1, 0, 'Run Puppeteer E2E tests against live dev server on localhost:3000', 'Docker'),
                (5, 'Code Linter & Formatter', 'Fast sandboxed syntax check, format, and style linting on code blocks prior to commits.', 'Development', 1, 1, 'Lint current active python or JS files with formatting recommendations', 'None'),
                (6, 'Research Web Crawler', 'Leverage Brave / Google Search MCP server to browse, clean, and summarize web documentation.', 'Productivity', 1, 0, 'Summarize latest Jetpack Compose Room integration features', 'None')
                """.trimIndent()
            )
        }
    }
}
