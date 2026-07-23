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
        McpToolEntity::class,
        ClaudeSkill::class
    ],
    version = 13,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase(), AppDatabaseInterface {
    abstract override fun ollamaNodeDao(): OllamaNodeDao
    abstract override fun agentDao(): AgentDao
    abstract override fun swarmConfigDao(): SwarmConfigDao
    abstract override fun swarmTaskDao(): SwarmTaskDao
    abstract override fun taskStepDao(): TaskStepDao
    abstract override fun workspaceFileDao(): WorkspaceFileDao
    abstract override fun chatMessageDao(): ChatMessageDao
    abstract override fun gitCommitDao(): GitCommitDao
    abstract override fun mcpServerDao(): McpServerDao
    abstract override fun mcpToolDao(): McpToolDao
    abstract override fun claudeSkillDao(): ClaudeSkillDao

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
                .fallbackToDestructiveMigration(true)
                .addCallback(DatabaseSeederCallback())
                .build()
                INSTANCE = instance
                instance
            }
        }

        /**
         * Resets the singleton instance and deletes the underlying database file(s).
         * The next call to [getDatabase] will create a fresh database and re-run seeders.
         * Intended for test isolation.
         */
        fun reset(context: Context) {
            synchronized(this) {
                INSTANCE?.close()
                INSTANCE = null
                val dbName = "ollama_swarm_database"
                context.deleteDatabase(dbName)
            }
        }
    }

    private class DatabaseSeederCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            
            // Seed SDLC Agents
            db.execSQL(
                """
                INSERT INTO agents (id, name, role, modelName, systemPrompt, colorHex, isSystemTemplate) VALUES
                (1, 'Spec Architect', 'Product Manager', 'deepseek-v4-pro', 'You are an expert Product Manager and Spec Architect. Your job is to translate user requirements into detailed, structured, and clear feature specifications and user stories.', '#9C27B0', 1),
                (2, 'Byte Code', 'Programmer', 'qwen3-coder:480b', 'You are an elite, concise software engineer. Write pristine, commented, and performant code based on prompt requirements and architectural guidelines.', '#4CAF50', 1),
                (3, 'Aura Critic', 'Critic', 'kimi-k2.6', 'You are a critical code reviewer. Analyze code changes, test suites, and pull requests for potential logical loopholes, edge cases, bugs, or architectural violations.', '#E91E63', 1),
                (4, 'Core Architect', 'Architect', 'gpt-oss:120b', 'You are a Senior System Architect. Your job is to design robust system architectures, define clean API contracts, design database schemas, and establish implementation patterns.', '#2196F3', 1),
                (5, 'Bug Hunter', 'QA Engineer', 'deepseek-v4-pro', 'You are an automated QA & Testing Specialist. Your job is to design comprehensive unit/integration test plans, write JUnit/Compose tests, and execute test suites.', '#FF9800', 1),
                (6, 'Shield Guard', 'Security Auditor', 'kimi-k2.6', 'You are a DevSecOps Security Auditor. Your job is to analyze code for security vulnerabilities, OWASP Top 10 issues, hardcoded credentials, and package dependencies risks.', '#F44336', 1),
                (7, 'Pipeline Deployer', 'DevOps Engineer', 'qwen3-coder:480b', 'You are a DevOps and Release Engineer. Your job is to configure CI/CD pipelines, compose Dockerfiles, write Gradle deployment tasks, and monitor build outputs.', '#009688', 1)
                """.trimIndent()
            )

            // Seed SDLC Swarm Configurations
            db.execSQL(
                """
                INSERT INTO swarm_configs (id, name, description, coordinationMode, agentIds) VALUES 
                (1, 'SDLC Spec & Design Swarm', 'Product Spec Architect gathers details and designs the specification, then Tech Lead drafts the architecture, and Critic audits for technical constraints.', 'SEQUENTIAL', '1,4,3'),
                (2, 'Feature Implementation Swarm', 'Core Architect defines API contracts, Byte Code implements code, and Bug Hunter writes unit/integration tests to ensure full coverage.', 'PEER_TO_PEER', '4,2,5'),
                (3, 'SecOps Build & Release Swarm', 'Byte Code edits files, Shield Guard performs security audits, and Pipeline Deployer runs the CI build and stages deployment configurations.', 'SEQUENTIAL', '2,6,7'),
                (4, 'Full Auto-SDLC Swarm', 'An end-to-end SDLC pipeline: Spec Architect designs, Core Architect structures, Byte Code implements, Bug Hunter tests, Shield Guard audits, and Pipeline Deployer builds.', 'SEQUENTIAL', '1,4,2,5,6,7'),
                (5, 'Adaptive Dynamic Routing Swarm', 'Orchestrator analyzes requirements at runtime, selects the optimal agents, builds a dynamic routing path, and synthesizes the final outputs.', 'DYNAMIC_ROUTING', '1,4,2,3,5'),
                (6, 'Autonomous Coding Harness', 'A Replit-Agent/Manus-AI-style autonomous plan-act-verify loop: Core Architect plans and assigns roles, Byte Code implements each step, and Bug Hunter verifies every step (invoking real MCP tooling when available) before checkpointing progress.', 'AGENTIC_LOOP', '4,2,5')
                """.trimIndent()
            )

            // Seed Ollama Nodes. All non-loopback nodes start "Offline" with unknown latency
            // until the user actually pings them (refreshNodes/pingNode) -- we don't fabricate
            // a live connection status for a node the app has never contacted.
            db.execSQL(
                """
                INSERT INTO ollama_nodes (id, name, url, status, availableModels, latencyMs) VALUES
                (1, 'Local Node (Loopback)', 'http://127.0.0.1:11434', 'Offline', 'llama3, mistral, phi3', -1),
                (2, 'Decentralized Swarm-Peer A', 'http://192.168.1.154:11434', 'Offline', 'llama3, mistral', -1),
                (3, 'Autonomous Edge Node B', 'http://10.0.0.42:11434', 'Offline', 'phi3', -1),
                (4, 'Ollama Cloud Gateway', 'https://ollama.com', 'Offline', 'gpt-oss:120b, qwen3-coder:480b, glm-5.2, kimi-k2.6, deepseek-v4-pro, minimax-m3', -1)
                """.trimIndent()
            )

            // Seed Workspace Files
            db.execSQL(
                """
                INSERT INTO workspace_files (id, filePath, content, lastModified, isConflict) VALUES
                (1, 'workspace/auth_spec.md', '# User Authentication Specification\n\n## Requirements\n- Register a new user with username and password.\n- Authenticate user credentials and return a stateless JWT token.\n- Secure token validation on API endpoints.\n\n## Architecture\n- /register -> Store username + salted password hash.\n- /login -> Generate signed JWT token with 1-hour expiry.', 1718010000000, 0),
                (2, 'auth.py', 'import jwt\nimport datetime\n\nSECRET = "super-secret-swarm-key"\n\ndef generate_token(username):\n    payload = {\n        "sub": username,\n        "exp": datetime.datetime.utcnow() + datetime.timedelta(hours=1)\n    }\n    return jwt.encode(payload, SECRET, algorithm="HS256")\n\ndef verify_token(token):\n    try:\n        return jwt.decode(token, SECRET, algorithms=["HS256"])\n    except jwt.ExpiredSignatureError:\n        return "Token expired"\n    except jwt.InvalidTokenError:\n        return "Invalid token"', 1718012000000, 0),
                (3, 'tests/test_auth.py', 'import unittest\nfrom auth import generate_token, verify_token\n\nclass TestAuth(unittest.TestCase):\n    def test_token_valid(self):\n        token = generate_token("admin")\n        payload = verify_token(token)\n        self.assertEqual(payload["sub"], "admin")\n\nif __name__ == "__main__":\n    unittest.main()', 1718014000000, 0)
                """.trimIndent()
            )

            // Seed Chat Messages
            db.execSQL(
                """
                INSERT INTO chat_messages (id, sender, role, message, timestamp, colorHex) VALUES 
                (1, 'System', 'system', 'Decentralized Swarm SDLC Automation channel initialized. Active swarm configurations are ready to accept tasks.', 1718010000000, '#9E9E9E'),
                (2, 'Spec Architect', 'agent', 'Auth module specifications drafted in workspace/auth_spec.md. Core Architect, please define the service boundaries.', 1718010100000, '#9C27B0'),
                (3, 'Core Architect', 'agent', 'Auth specs analyzed. We will build a stateless JWT-based service. Byte Code, implement the token generation and validation logic in auth.py.', 1718010200000, '#2196F3'),
                (4, 'Byte Code', 'agent', 'Understood, starting work on auth.py with SHA-256 signatures.', 1718010300000, '#4CAF50'),
                (5, 'Bug Hunter', 'agent', 'I am preparing unit tests for token expiration and signature manipulation payloads.', 1718010400000, '#FF9800')
                """.trimIndent()
            )

            // Seed MCP Servers. Because Android cannot spawn local stdio MCP servers, these URLs
            // point to example local/self-hosted Streamable-HTTP endpoints (e.g. a gateway or bridge).
            // A real "Connected" status is only set after a genuine initialize+tools/list handshake.
            db.execSQL(
                """
                INSERT INTO mcp_servers (id, name, type, sourceUrl, status, toolsCount, configuredParams) VALUES
                (1, 'Local MCP Gateway', 'Gateway', 'http://localhost:3000/mcp', 'Disconnected', 0, '{}'),
                (2, 'SearXNG Search Bridge', 'Search', 'http://localhost:3002/mcp', 'Disconnected', 0, '{}'),
                (3, 'Private GitHub MCP', 'GitHub', 'http://localhost:3003/mcp', 'Disconnected', 0, '{"repo":"owner/repo"}'),
                (4, 'Workspace Postgres', 'Database', 'http://localhost:3004/mcp', 'Disconnected', 0, '{"connectionString":"postgresql://localhost:5432/app"}'),
                (5, 'Browser Automation', 'Browser', 'http://localhost:3005/mcp', 'Disconnected', 0, '{"headless":true}')
                """.trimIndent()
            )

            // Seed Claude Skills. Skills 7-10 map to native GitService/local compilation features
            // instead of MCP servers, so they don't require any MCP server connection.
            // sourceToolName is NULL for manually authored skills; auto-generated skills bind to real MCP tool names.
            db.execSQL(
                """
                INSERT INTO claude_skills (id, name, description, category, isRecommended, isEnabled, usageExample, requiredMcpServerType, sourceToolName) VALUES
                (1, 'GitHub Search & Pull', 'Search and manage issues, pull requests, and repositories using the GitHub MCP client.', 'Development', 1, 1, 'Search pull requests with query "bugfix" in example-repo', 'GitHub', NULL),
                (2, 'Docker Lifecycle Monitor', 'Monitor docker containers, list active tasks, view performance graphs, and inspect configurations.', 'Automation', 1, 1, 'List running docker containers and show port mappings', 'Docker', NULL),
                (3, 'Postgres SQL Optimizer', 'Examine Postgres schemas, run EXPLAIN queries, and automatically optimize database indexes.', 'Analysis', 0, 0, 'Analyze table "users" and recommend optimal column indexes', 'Database', NULL),
                (4, 'Automated Integration Tester', 'Simulate end-to-end user actions in headful browser environments using Puppeteer scripts.', 'Automation', 1, 0, 'Run Puppeteer E2E tests against live dev server on localhost:3000', 'Browser', NULL),
                (5, 'Code Linter & Formatter', 'Fast sandboxed syntax check, format, and style linting on code blocks prior to commits.', 'Development', 1, 1, 'Lint current active python or JS files with formatting recommendations', 'None', NULL),
                (6, 'Research Web Crawler', 'Leverage Brave / Google Search MCP server to browse, clean, and summarize web documentation.', 'Productivity', 1, 0, 'Summarize latest Jetpack Compose Room integration features', 'None', NULL),
                (7, 'Git Branch Creator', 'Create and check out new local Git branches for code tasks.', 'Development', 1, 1, 'git branch feature/auth-fix', 'None', NULL),
                (8, 'Git Auto-Stager & Committer', 'Stage modified files and write clean conventional commits automatically.', 'Development', 1, 1, 'git commit -am "feat: add user authentication tokens"', 'None', NULL),
                (9, 'Automated Compiler Self-Healer', 'Verify Kotlin compilation and run auto-repair loops on code errors.', 'Automation', 1, 1, 'Check kotlin compilation syntax and run healing loop', 'None', NULL),
                (10, 'Gradle Test Runner', 'Run local Gradle test tasks and parse trace reports.', 'Automation', 1, 1, 'gradle test :app', 'None', NULL)
                """.trimIndent()
            )
        }
    }
}
