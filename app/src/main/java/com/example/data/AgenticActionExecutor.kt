package com.example.data

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class AgenticActionExecutor(
    private val db: AppDatabaseInterface,
    private val gitService: GitService,
    private val mcpClient: McpClientInterface,
    private val appContext: Context,
    private val securePrefs: SecurePrefsInterface,
    private val llmRouter: LlmRouterInterface,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : AgenticActionExecutorInterface {

    override suspend fun parseAndExecute(
        taskId: Int,
        agentName: String,
        output: String,
        mcpSuccessActionType: String,
        mcpFailureActionType: String
    ): ActionOutcome {
        var outcome = ActionOutcome(mcpCallAttempted = false, mcpCallSucceeded = false, mcpResultText = null)
        for (line in output.split("\n")) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("git ") || trimmed.startsWith("$ git ") ->
                    executeAgenticGitCommand(taskId, agentName, trimmed.removePrefix("$ ").trim())
                trimmed.startsWith("MCP_CALL:") -> {
                    val result = executeAgenticMcpCall(taskId, agentName, trimmed.removePrefix("MCP_CALL:").trim(), mcpSuccessActionType, mcpFailureActionType)
                    outcome = ActionOutcome(
                        mcpCallAttempted = true,
                        mcpCallSucceeded = result.isSuccess,
                        mcpResultText = result.getOrNull() ?: result.exceptionOrNull()?.message
                    )
                }
                trimmed.startsWith("WRITE_FILE:") ->
                    executeAgenticFileWrite(taskId, agentName, trimmed.removePrefix("WRITE_FILE:").trim(), output)
            }
        }
        return outcome
    }

    private suspend fun executeAgenticGitCommand(taskId: Int, agentName: String, command: String) {
        if (command.contains("commit")) {
            val message = try {
                val match = Regex("""-m\s+["']([^"']+)["']""").find(command)
                match?.groupValues?.get(1) ?: Regex("""-am\s+["']([^"']+)["']""").find(command)?.groupValues?.get(1) ?: "Agent commit"
            } catch (e: Exception) {
                "Agent commit"
            }

            val files = db.workspaceFileDao().getAllFiles().first()
            val (result, status) = withContext(dispatcher) {
                gitService.mirrorFiles(files)
                gitService.commitAll(agentName, "${agentName.lowercase().replace(" ", "-")}@swarm.local", message)
            }

            if (result != null && status is GitOpResult.Success) {
                db.gitCommitDao().insertCommit(
                    GitCommit(commitHash = result.hash, author = result.author, message = result.message, timestamp = result.timestamp, taskId = taskId)
                )
                db.taskStepDao().insertStep(
                    TaskStep(
                        taskId = taskId,
                        agentName = "Git Integration",
                        agentRole = "System",
                        actionType = "GIT_COMMIT",
                        content = "Commit created: [${result.hash}] ${result.message} (by $agentName)"
                    )
                )
            } else if (status is GitOpResult.Failure) {
                db.taskStepDao().insertStep(
                    TaskStep(
                        taskId = taskId,
                        agentName = "Git Integration",
                        agentRole = "System",
                        actionType = "GIT_COMMIT_FAILED",
                        content = "Commit requested by $agentName failed: ${status.error}"
                    )
                )
            }
        } else if (command.startsWith("git branch ")) {
            val branchName = command.removePrefix("git branch ").trim()
            db.taskStepDao().insertStep(
                TaskStep(
                    taskId = taskId,
                    agentName = "Git Integration",
                    agentRole = "System",
                    actionType = "GIT_BRANCH",
                    content = "Branch creation ('$branchName' requested by $agentName) is not yet implemented -- no branch was created."
                )
            )
        } else if (command.startsWith("git push")) {
            val prefs = appContext.getSharedPreferences("ollama_swarm_prefs", Context.MODE_PRIVATE)
            val remoteUrl = prefs.getString("git_remote_url", null)
            val token = securePrefs.getGitToken()

            if (remoteUrl.isNullOrBlank() || token.isNullOrBlank()) {
                db.taskStepDao().insertStep(
                    TaskStep(
                        taskId = taskId,
                        agentName = "Git Integration",
                        agentRole = "System",
                        actionType = "GIT_PUSH_FAILED",
                        content = "Push requested by $agentName failed: no remote URL/token configured in Git Settings."
                    )
                )
                return
            }

            val approved = PendingApprovalStore.requestApproval(
                taskId, agentName, ApprovalRiskCategory.GIT_PUSH,
                "Push local commits to remote ($remoteUrl) requested by $agentName"
            )
            if (!approved) {
                db.taskStepDao().insertStep(
                    TaskStep(
                        taskId = taskId,
                        agentName = "Git Integration",
                        agentRole = "System",
                        actionType = "ACTION_DECLINED",
                        content = "Push requested by $agentName was declined by user."
                    )
                )
                return
            }

            val status = withContext(dispatcher) { gitService.push(remoteUrl, token) }
            when (status) {
                is GitOpResult.Success -> {
                    val headHash = withContext(dispatcher) { gitService.localHeadHash() }
                    prefs.edit().putString("git_last_pushed_hash", headHash).apply()
                    db.taskStepDao().insertStep(
                        TaskStep(
                            taskId = taskId,
                            agentName = "Git Integration",
                            agentRole = "System",
                            actionType = "GIT_PUSH",
                            content = "Pushed local commits to remote (by $agentName)."
                        )
                    )
                }
                is GitOpResult.Failure -> {
                    db.taskStepDao().insertStep(
                        TaskStep(
                            taskId = taskId,
                            agentName = "Git Integration",
                            agentRole = "System",
                            actionType = "GIT_PUSH_FAILED",
                            content = "Push requested by $agentName failed: ${status.error}"
                        )
                    )
                }
            }
        }
    }

    private suspend fun executeAgenticMcpCall(
        taskId: Int,
        agentName: String,
        payload: String,
        successActionType: String = "MCP_TOOL_CALL",
        failureActionType: String = "MCP_CALL_FAILED"
    ): Result<String> {
        val parts = payload.split("|", limit = 2)
        val skillName = parts.getOrNull(0)?.trim().orEmpty()
        val argsJson = parts.getOrNull(1)?.trim().orEmpty()

        // Resolve skill by display name or by the bound MCP tool name
        val skill = db.claudeSkillDao().getAllSkillsSync()
            .firstOrNull {
                (it.name.equals(skillName, ignoreCase = true) || it.sourceToolName.equals(skillName, ignoreCase = true))
                        && it.isEnabled
            }
        if (skill == null) {
            val message = "$agentName tried to call unknown or disabled skill '$skillName'."
            db.taskStepDao().insertStep(
                TaskStep(taskId = taskId, agentName = "MCP Tool", agentRole = "System", actionType = failureActionType, content = message)
            )
            return Result.failure(IllegalStateException(message))
        }

        val server = db.mcpServerDao().getAllServersSync()
            .firstOrNull { it.type == skill.requiredMcpServerType && it.status == "Connected" }
        if (server == null) {
            val message = "$agentName tried to call '${skill.name}' but no Connected MCP server of type '${skill.requiredMcpServerType}' is available."
            db.taskStepDao().insertStep(
                TaskStep(taskId = taskId, agentName = "MCP Tool", agentRole = "System", actionType = failureActionType, content = message)
            )
            return Result.failure(IllegalStateException(message))
        }

        val arguments = parseJsonArguments(argsJson)

        // Validate required arguments against the stored input schema
        val toolName = skill.sourceToolName ?: skill.name
        val toolEntity = db.mcpToolDao().getToolByName(toolName)
        val missingRequired = validateRequiredArgs(toolEntity, arguments)
        if (missingRequired.isNotEmpty()) {
            val message = "$agentName's call to '${skill.name}' is missing required arguments: ${missingRequired.joinToString(", ")}."
            db.taskStepDao().insertStep(
                TaskStep(taskId = taskId, agentName = "MCP Tool", agentRole = "System", actionType = failureActionType, content = message)
            )
            return Result.failure(IllegalStateException(message))
        }

        if (isRiskyMcpCall(toolEntity, skill)) {
            val approved = PendingApprovalStore.requestApproval(
                taskId, agentName, ApprovalRiskCategory.MCP_DESTRUCTIVE_CALL,
                "Call '${skill.name}' ($toolName) on ${server.name}", detail = argsJson
            )
            if (!approved) {
                val message = "$agentName's call to '${skill.name}' ($toolName) was declined by user."
                db.taskStepDao().insertStep(
                    TaskStep(taskId = taskId, agentName = "MCP Tool", agentRole = "System", actionType = "ACTION_DECLINED", content = message)
                )
                return Result.failure(IllegalStateException(message))
            }
        }

        val authToken = securePrefs.getMcpToken(server.id)

        val outcome = mcpClient.initialize(server.sourceUrl, authToken).mapCatching { session ->
            mcpClient.callTool(server.sourceUrl, session, authToken, toolName, arguments).getOrThrow()
        }

        outcome.fold(
            onSuccess = { toolResult ->
                db.taskStepDao().insertStep(
                    TaskStep(
                        taskId = taskId,
                        agentName = "MCP Tool",
                        agentRole = "System",
                        actionType = successActionType,
                        content = "$agentName called '${skill.name}' ($toolName) on ${server.name}. Result: $toolResult"
                    )
                )
            },
            onFailure = { error ->
                db.taskStepDao().insertStep(
                    TaskStep(
                        taskId = taskId,
                        agentName = "MCP Tool",
                        agentRole = "System",
                        actionType = failureActionType,
                        content = "$agentName's call to '${skill.name}' ($toolName) on ${server.name} failed: ${error.message}"
                    )
                )
            }
        )
        return outcome
    }

    /** MCP's standard `destructiveHint`/`readOnlyHint` tool annotations, when a live server
     *  provides them, take priority; otherwise fall back to keyword inference over the skill/tool
     *  name+description -- same precedent as [com.example.data.inferServerType]'s registry
     *  keyword matching, since no seeded/example server here has real annotations yet. */
    private fun isRiskyMcpCall(toolEntity: McpToolEntity?, skill: ClaudeSkill): Boolean {
        val annotations = toolEntity?.annotationsJson?.let { parseJsonArguments(it) }
        (annotations?.get("destructiveHint") as? Boolean)?.let { if (it) return true }
        (annotations?.get("readOnlyHint") as? Boolean)?.let { if (it) return false }
        val text = "${skill.name} ${skill.description} ${toolEntity?.name.orEmpty()}".lowercase()
        return listOf("delete", "remove", "drop", "push", "deploy", "destroy", "force", "publish", "merge").any(text::contains)
    }

    /**
     * Handles a `WRITE_FILE: <path>` directive with a dedicated, focused LLM round-trip whose
     * entire response is expected to be the raw file content -- deliberately not parsed out of the
     * act step's freeform response, since that would be brittle to the model not following an
     * exact fenced-block format. The proposed content is routed through the same approval-gate
     * singleton as risky actions for human diff review before it's written.
     */
    private suspend fun executeAgenticFileWrite(taskId: Int, agentName: String, filePath: String, context: String) {
        if (filePath.isBlank()) return
        val existing = db.workspaceFileDao().getFileByPath(filePath)
        val contentPrompt = "Write the complete contents of the file at '$filePath' based on this " +
            "context:\n$context\n\nRespond with ONLY the raw file content -- no markdown fences, " +
            "no commentary, no explanation."
        val proposedContent = llmRouter.generateFreeform(
            contentPrompt,
            "You are an expert software engineer producing exact file contents for direct use, not a chat response.",
            preferCloud = true
        )

        val change = PendingFileChange(
            taskId = taskId,
            agentName = agentName,
            filePath = filePath,
            originalContent = existing?.content.orEmpty(),
            proposedContent = proposedContent,
            isNewFile = existing == null
        )
        val approved = PendingApprovalStore.requestFileChangeReview(change)
        if (approved) {
            if (existing != null) {
                db.workspaceFileDao().updateFile(existing.copy(content = proposedContent, lastModified = System.currentTimeMillis()))
            } else {
                db.workspaceFileDao().insertFile(WorkspaceFile(filePath = filePath, content = proposedContent))
            }
            db.taskStepDao().insertStep(
                TaskStep(taskId = taskId, agentName = agentName, agentRole = "System", actionType = "FILE_CHANGE_APPLIED", content = "Updated $filePath (${proposedContent.length} chars)")
            )
        } else {
            db.taskStepDao().insertStep(
                TaskStep(taskId = taskId, agentName = agentName, agentRole = "System", actionType = "FILE_CHANGE_REJECTED", content = "Proposed change to $filePath was declined by user.")
            )
        }
    }

    /**
     * Engine-driven checkpoint after a todo's verify phase passes cleanly -- deliberately not
     * left to the LLM's discretion to remember to emit a `git commit` line, since that's not a
     * reliable checkpoint story. Mirrors the current WorkspaceFile set and commits; a "no changes"
     * failure (the todo touched no files) is expected/benign and not surfaced as an error step.
     */
    override suspend fun autoCheckpoint(taskId: Int, agentName: String, todoText: String) {
        val files = db.workspaceFileDao().getAllFiles().first()
        val (result, status) = withContext(dispatcher) {
            gitService.mirrorFiles(files)
            gitService.commitAll("Agentic Loop", "agentic-loop@swarm.local", "Checkpoint: $todoText")
        }
        if (result != null && status is GitOpResult.Success) {
            db.gitCommitDao().insertCommit(
                GitCommit(commitHash = result.hash, author = result.author, message = result.message, timestamp = result.timestamp, taskId = taskId)
            )
            db.taskStepDao().insertStep(
                TaskStep(taskId = taskId, agentName = "Git Integration", agentRole = "System", actionType = "CHECKPOINT_COMMIT", content = "Checkpoint [${result.hash}] after: $todoText")
            )
        }
    }

    private fun validateRequiredArgs(toolEntity: McpToolEntity?, arguments: Map<String, Any?>): List<String> {
        if (toolEntity == null) return emptyList()
        val schema = parseJsonArguments(toolEntity.inputSchemaJson)
        @Suppress("UNCHECKED_CAST")
        val required = (schema["required"] as? List<String>).orEmpty()
        return required.filter { argName ->
            arguments[argName] == null || arguments[argName] == "" || arguments[argName] == emptyList<Any>()
        }
    }

    private fun parseJsonArguments(argsJson: String): Map<String, Any?> {
        if (argsJson.isBlank()) return emptyMap()
        return try {
            val moshi = com.squareup.moshi.Moshi.Builder().build()
            val type = com.squareup.moshi.Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
            @Suppress("UNCHECKED_CAST")
            moshi.adapter<Map<String, Any?>>(type).fromJson(argsJson) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
