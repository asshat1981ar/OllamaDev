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
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * When true the executor is running in a headless context (e.g. from [AgenticLoopService])
     * where no UI approval dialog can be shown. Any action that would require human approval is
     * auto-declined and recorded as [APPROVAL_SKIPPED_HEADLESS] instead of suspending forever.
     */
    private val isHeadless: Boolean = false
) : AgenticActionExecutorInterface {

    override suspend fun parseAndExecute(
        taskId: Int,
        agentId: Int,
        agentName: String,
        output: String,
        mcpSuccessActionType: String,
        mcpFailureActionType: String
    ): ActionOutcome {
        var outcome = ActionOutcome(mcpCallAttempted = false, mcpCallSucceeded = false, mcpResultText = null)
        val writeFilePaths = mutableListOf<String>()
        for (line in output.split("\n")) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("git ") || trimmed.startsWith("$ git ") ->
                    executeAgenticGitCommand(taskId, agentId, agentName, trimmed.removePrefix("$ ").trim())
                trimmed.startsWith("MCP_CALL:") -> {
                    val result = executeAgenticMcpCall(taskId, agentId, agentName, trimmed.removePrefix("MCP_CALL:").trim(), mcpSuccessActionType, mcpFailureActionType)
                    outcome = ActionOutcome(
                        mcpCallAttempted = true,
                        mcpCallSucceeded = result.isSuccess,
                        mcpResultText = result.getOrNull() ?: result.exceptionOrNull()?.message
                    )
                }
                trimmed.startsWith("WRITE_FILE:") -> {
                    val path = trimmed.removePrefix("WRITE_FILE:").trim()
                    if (path.isNotBlank() && path !in writeFilePaths) writeFilePaths.add(path)
                }
            }
        }
        if (writeFilePaths.isNotEmpty()) {
            executeAgenticFileWriteBatch(taskId, agentName, writeFilePaths, output)
        }
        return outcome
    }

    private suspend fun executeAgenticGitCommand(taskId: Int, agentId: Int, agentName: String, command: String) {
        fun setAwaitingApproval(awaiting: Boolean) {
            if (agentId != 0) {
                AgentStateStore.setAgentActive(agentId, awaiting, if (awaiting) "Awaiting Approval" else "Idle")
            }
        }

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

            setAwaitingApproval(true)
            val approved = if (isHeadless) {
                recordHeadlessApprovalSkipped(taskId, agentName, "git push to $remoteUrl")
                false
            } else {
                PendingApprovalStore.requestApproval(
                    taskId, agentName, ApprovalRiskCategory.GIT_PUSH,
                    "Push local commits to remote ($remoteUrl) requested by $agentName"
                )
            }
            setAwaitingApproval(false)
            if (!approved) {
                if (isHeadless) {
                    // Headless skip was already recorded as APPROVAL_SKIPPED_HEADLESS.
                    return
                }
                db.taskStepDao().insertStep(
                    TaskStep(
                        taskId = taskId,
                        agentName = "Git Integration",
                        agentRole = "System",
                        actionType = "ACTION_DECLINED",
                        content = "Push requested by $agentName was declined by user."
                    )
                )
                AntigenicSignalStore.recordSignal(
                    AntigenicSignal(
                        taskId = taskId,
                        severity = AntigenicSeverity.WARNING,
                        category = AntigenicCategory.SAFETY,
                        source = "AgenticActionExecutor",
                        signalType = "APPROVAL_DECLINED",
                        message = "Git push approval was declined",
                        detail = "remote=$remoteUrl agent=$agentName",
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
        agentId: Int,
        agentName: String,
        payload: String,
        successActionType: String = "MCP_TOOL_CALL",
        failureActionType: String = "MCP_CALL_FAILED"
    ): Result<String> {
        fun setAwaitingApproval(awaiting: Boolean) {
            if (agentId != 0) {
                AgentStateStore.setAgentActive(agentId, awaiting, if (awaiting) "Awaiting Approval" else "Idle")
            }
        }
        val parts = payload.split("|", limit = 2)
        val skillName = parts.getOrNull(0)?.trim().orEmpty()
        val argsJson = parts.getOrNull(1)?.trim().orEmpty()

        // Resolve skill by display name or by the bound MCP tool name
        val skill = db.claudeSkillDao().getAllSkillsSync()
            .firstOrNull {
                (it.name.equals(skillName, ignoreCase = true) || it.sourceToolName?.equals(skillName, ignoreCase = true) == true)
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

        // Compute the reason string up front so the approval dialog can show *why* the call
        // was gated (annotation-based vs keyword-based) instead of just the raw argsJson. The
        // string is null when the call isn't risky; otherwise it explains the matched reason.
        val riskReason = isRiskyMcpCallReason(toolEntity, skill)
        if (riskReason != null) {
            // Surface the gating decision to the task timeline before the human approval dialog,
            // so users can see *why* execution paused even if they later dismiss the dialog.
            db.taskStepDao().insertStep(
                TaskStep(
                    taskId = taskId,
                    agentName = "MCP Tool",
                    agentRole = "System",
                    actionType = "MCP_CALL_GATED",
                    content = "$agentName requested '${skill.name}' ($toolName) on ${server.name}: $riskReason"
                )
            )
            AntigenicSignalStore.recordSignal(
                AntigenicSignal(
                    taskId = taskId,
                    severity = AntigenicSeverity.WARNING,
                    category = AntigenicCategory.SAFETY,
                    source = "AgenticActionExecutor",
                    signalType = "MCP_RISKY_CALL",
                    message = "Risky MCP call gated for approval",
                    detail = "skill=${skill.name} tool=$toolName server=${server.name} reason=$riskReason",
                )
            )
            setAwaitingApproval(true)
            val approved = if (isHeadless) {
                recordHeadlessApprovalSkipped(taskId, agentName, "destructive MCP call '${skill.name}'")
                false
            } else {
                PendingApprovalStore.requestApproval(
                    taskId, agentName, ApprovalRiskCategory.MCP_DESTRUCTIVE_CALL,
                    "Call '${skill.name}' ($toolName) on ${server.name}",
                    detail = "$riskReason\n\nArgs: $argsJson"
                )
            }
            setAwaitingApproval(false)
            if (!approved) {
                if (isHeadless) {
                    // Headless skip was already recorded as APPROVAL_SKIPPED_HEADLESS.
                    return Result.failure(
                        IllegalStateException(
                            "$agentName's call to '${skill.name}' ($toolName) was skipped because the task is running headless."
                        )
                    )
                }
                val message = "$agentName's call to '${skill.name}' ($toolName) was declined by user."
                db.taskStepDao().insertStep(
                    TaskStep(taskId = taskId, agentName = "MCP Tool", agentRole = "System", actionType = "ACTION_DECLINED", content = message)
                )
                AntigenicSignalStore.recordSignal(
                    AntigenicSignal(
                        taskId = taskId,
                        severity = AntigenicSeverity.WARNING,
                        category = AntigenicCategory.SAFETY,
                        source = "AgenticActionExecutor",
                        signalType = "APPROVAL_DECLINED",
                        message = "MCP approval was declined",
                        detail = "skill=${skill.name} tool=$toolName agent=$agentName",
                    )
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
     *  keyword matching, since no seeded/example server here has real annotations yet.
     *  Returns a human-readable reason string when the call is risky (so the approval dialog
     *  can show *why* the call was flagged), or null when the call is not risky. */
    private fun isRiskyMcpCallReason(toolEntity: McpToolEntity?, skill: ClaudeSkill): String? {
        val annotations = toolEntity?.annotationsJson?.let { parseJsonArguments(it) }
        (annotations?.get("destructiveHint") as? Boolean)?.let { if (it) return "Flagged by tool: destructiveHint=true" }
        (annotations?.get("readOnlyHint") as? Boolean)?.let { if (it) return null }
        val text = "${skill.name} ${skill.description} ${toolEntity?.name.orEmpty()}".lowercase()
        val matchedKeyword = listOf("delete", "remove", "drop", "push", "deploy", "destroy", "force", "publish", "merge")
            .firstOrNull(text::contains)
            ?: return null
        return "Flagged by keyword: contains '$matchedKeyword'"
    }


    /**
     * Handles all `WRITE_FILE: <path>` directives from a single act step as one batched review.
     * Each path gets its own focused LLM round-trip to generate raw file content, then a single
     * human-approval dialog presents the whole batch. Approved files are applied; rejected files
     * record a `FILE_CHANGE_REJECTED` step. This avoids N sequential dialogs when one step touches
     * many files.
     */
    private suspend fun executeAgenticFileWriteBatch(
        taskId: Int,
        agentName: String,
        filePaths: List<String>,
        context: String
    ) {
        if (filePaths.isEmpty()) return
        val changes = filePaths.map { path ->
            val existing = db.workspaceFileDao().getFileByPath(path)
            val contentPrompt = "Write the complete contents of the file at '$path' based on this " +
                "context:\n$context\n\nRespond with ONLY the raw file content -- no markdown fences, " +
                "no commentary, no explanation."
            val proposedContent = llmRouter.generateFreeform(
                contentPrompt,
                "You are an expert software engineer producing exact file contents for direct use, not a chat response.",
                preferCloud = true
            )
            PendingFileChange(
                taskId = taskId,
                agentName = agentName,
                filePath = path,
                originalContent = existing?.content.orEmpty(),
                proposedContent = proposedContent,
                isNewFile = existing == null
            )
        }

        val batch = PendingFileChangeBatch(
            id = System.currentTimeMillis(),
            taskId = taskId,
            agentName = agentName,
            changes = changes
        )
        val decisions = if (isHeadless) {
            recordHeadlessApprovalSkipped(taskId, agentName, "${changes.size} file-change(s)")
            changes.associate { it.filePath to false }
        } else {
            PendingApprovalStore.requestFileChangeBatchReview(batch)
        }

        changes.forEach { change ->
            val approved = decisions[change.filePath] ?: false
            if (approved) {
                val existing = db.workspaceFileDao().getFileByPath(change.filePath)
                if (existing != null) {
                    db.workspaceFileDao().updateFile(existing.copy(content = change.proposedContent, lastModified = System.currentTimeMillis()))
                } else {
                    db.workspaceFileDao().insertFile(WorkspaceFile(filePath = change.filePath, content = change.proposedContent))
                }
                db.taskStepDao().insertStep(
                    TaskStep(taskId = taskId, agentName = agentName, agentRole = "System", actionType = "FILE_CHANGE_APPLIED", content = "Updated ${change.filePath} (${change.proposedContent.length} chars)")
                )
            } else {
                db.taskStepDao().insertStep(
                    TaskStep(taskId = taskId, agentName = agentName, agentRole = "System", actionType = "FILE_CHANGE_REJECTED", content = "Proposed change to ${change.filePath} was declined by user.")
                )
            }
        }
    }

    /**
     * Engine-driven checkpoint after a todo's verify phase passes cleanly -- deliberately not
     * left to the LLM's discretion to remember to emit a `git commit` line, since that's not a
     * reliable checkpoint story. Mirrors the current WorkspaceFile set and commits; a "no changes"
     * failure (the todo touched no files) is expected/benign and not surfaced as an error step.
     */
    override suspend fun autoCheckpoint(taskId: Int, agentId: Int, agentName: String, todoText: String) {
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

    /**
     * Records a headless-mode skip step when an action that would normally require human
     * approval cannot be shown in a UI. This prevents the background service from deadlocking
     * on an approval dialog and gives the user an auditable trace of what was skipped.
     */
    private suspend fun recordHeadlessApprovalSkipped(taskId: Int, agentName: String, actionDescription: String) {
        db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = agentName,
                agentRole = "System",
                actionType = "APPROVAL_SKIPPED_HEADLESS",
                content = "Skipped approval for $actionDescription because the task is running headless."
            )
        )
        AntigenicSignalStore.recordSignal(
            AntigenicSignal(
                taskId = taskId,
                severity = AntigenicSeverity.CRITICAL,
                category = AntigenicCategory.SAFETY,
                source = "AgenticActionExecutor",
                signalType = "APPROVAL_SKIPPED_HEADLESS",
                message = "Approval skipped because the task is running headless",
                detail = "action=$actionDescription",
            )
        )
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
