package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class SwarmEngine(
    private val db: AppDatabase,
    private val gitService: GitService,
    private val mcpClient: McpClient,
    private val appContext: android.content.Context
) {

    suspend fun executeTask(config: SwarmConfig, userPrompt: String): Int = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        // 1. Parse Agent IDs
        val ids = config.agentIds.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
        
        // 2. Fetch Agents from DB
        var swarmAgents = db.agentDao().getAgentsByIds(ids)
        if (swarmAgents.isEmpty()) {
            // Fallback to all templates
            db.agentDao().getAllAgents()
            swarmAgents = db.agentDao().getAgentsByIds(listOf(1, 2, 3, 4))
        }

        // 3. Create initial pending SwarmTask
        val task = SwarmTask(
            prompt = userPrompt,
            status = "Thinking",
            swarmName = config.name,
            timestamp = System.currentTimeMillis()
        )
        val taskId = db.swarmTaskDao().insertTask(task).toInt()

        try {
            when (config.coordinationMode) {
                "SEQUENTIAL" -> runSequentialWorkflow(taskId, swarmAgents, userPrompt)
                "PEER_TO_PEER" -> runPeerToPeerWorkflow(taskId, swarmAgents, userPrompt)
                "CONSENSUS_VOTE" -> runConsensusVoteWorkflow(taskId, swarmAgents, userPrompt)
                "DYNAMIC_ROUTING" -> runDynamicRoutingWorkflow(taskId, swarmAgents, userPrompt)
                else -> runSequentialWorkflow(taskId, swarmAgents, userPrompt)
            }

            // Calculate final statistics
            val steps = db.taskStepDao().getStepsForTaskSync(taskId)
            val finalResult = steps.lastOrNull { it.actionType == "FINAL_RESPONSE" || it.actionType == "OUTPUT" }?.content 
                ?: "Swarm finished execution with no final output."
            
            val duration = System.currentTimeMillis() - startTime
            val completedTask = db.swarmTaskDao().getTaskById(taskId)?.copy(
                status = "Completed",
                result = finalResult,
                executionTimeMs = duration,
                tokenUsage = (userPrompt.length + finalResult.length) / 2 + 350 // Approximating tokens
            )
            if (completedTask != null) {
                db.swarmTaskDao().updateTask(completedTask)
            }
        } catch (e: Exception) {
            val failedTask = db.swarmTaskDao().getTaskById(taskId)?.copy(
                status = "Failed",
                result = "Execution failed: ${e.localizedMessage}"
            )
            if (failedTask != null) {
                db.swarmTaskDao().updateTask(failedTask)
            }
            
            // Add error step
            db.taskStepDao().insertStep(
                TaskStep(
                    taskId = taskId,
                    agentName = "Swarm Engine",
                    agentRole = "System",
                    actionType = "FINAL_RESPONSE",
                    content = "Error during swarm execution: ${e.localizedMessage}"
                )
            )
        } finally {
            AgentStateStore.resetAllActiveStates()
        }

        return@withContext taskId
    }

    private suspend fun runSequentialWorkflow(taskId: Int, agents: List<Agent>, userPrompt: String) {
        var context = "Initial Task Request:\n\"$userPrompt\"\n\n"
        
        for (index in agents.indices) {
            val agent = agents[index]
            val stepStartTime = System.currentTimeMillis()
            AgentStateStore.setAgentActive(agent.id, true, if (index == agents.size - 1) "Synthesizing" else "Thinking")
            
            // Update Task Status
            updateTaskStatus(taskId, "Thinking (${agent.name})")
            
            // Add Thinking step
            val stepId = db.taskStepDao().insertStep(
                TaskStep(
                    taskId = taskId,
                    agentName = agent.name,
                    agentRole = agent.role,
                    actionType = "THINKING",
                    content = "Analyzing prompt and building execution context..."
                )
            ).toInt()
            
            // Simulate agent analysis delay
            delay(1500)
            
            // Run actual generation
            val promptForAgent = if (index == 0) {
                "Complete the following task: $userPrompt"
            } else {
                "Previous swarm analysis:\n$context\n\nYour task is to review, add your specialty expertise (${agent.role}), and build upon this. Respond to: $userPrompt"
            }
            
            AgentStateStore.setAgentActive(agent.id, true, "Generating")
            val agentOutput = generateOutputForAgent(agent, promptForAgent)
            
            // Intercept and run any Git commands proposed by the agent
            parseAndExecuteAgenticActions(taskId, agent.name, agentOutput)

            // Update Step with finished output
            db.taskStepDao().insertStep(
                TaskStep(
                    id = stepId, // Replaces thinking step with finished execution or we add new one
                    taskId = taskId,
                    agentName = agent.name,
                    agentRole = agent.role,
                    actionType = if (index == agents.size - 1) "FINAL_RESPONSE" else "OUTPUT",
                    content = agentOutput
                )
            )
            
            val stepDuration = System.currentTimeMillis() - stepStartTime
            val approxTokens = (promptForAgent.length + agentOutput.length) / 2 + 100
            AgentStateStore.recordExecutionMetrics(agent.id, stepDuration, approxTokens)
            AgentStateStore.setAgentActive(agent.id, false, "Idle")
            
            context += "--- ${agent.name} (${agent.role}) Output ---\n$agentOutput\n\n"
            delay(1000)
        }
    }

    private suspend fun runPeerToPeerWorkflow(taskId: Int, agents: List<Agent>, userPrompt: String) {
        var context = "Initial Task Request:\n\"$userPrompt\"\n\n"
        
        // Simulating collaborative back-and-forth
        // Step 1: Researcher does initial research
        val researcher = agents.firstOrNull { it.role == "Researcher" } ?: agents[0]
        updateTaskStatus(taskId, "Peer Task: researching (${researcher.name})")
        
        val step1StartTime = System.currentTimeMillis()
        AgentStateStore.setAgentActive(researcher.id, true, "Researching")
        
        db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = researcher.name,
                agentRole = researcher.role,
                actionType = "THINKING",
                content = "Conducting architectural discovery of decentralized elements..."
            )
        )
        delay(1500)
        
        val researchPrompt = "Analyze and research requirements for: $userPrompt"
        val researchResult = generateOutputForAgent(researcher, researchPrompt)
        parseAndExecuteAgenticActions(taskId, researcher.name, researchResult)
        
        db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = researcher.name,
                agentRole = researcher.role,
                actionType = "OUTPUT",
                content = researchResult
            )
        )
        
        val step1Duration = System.currentTimeMillis() - step1StartTime
        val step1Tokens = (researchPrompt.length + researchResult.length) / 2 + 100
        AgentStateStore.recordExecutionMetrics(researcher.id, step1Duration, step1Tokens)
        AgentStateStore.setAgentActive(researcher.id, false, "Idle")
        
        context += "Research: $researchResult\n\n"
        delay(1000)

        // Step 2: Coder/Programmer implements
        val programmer = agents.firstOrNull { it.role == "Programmer" } ?: agents.getOrNull(1) ?: researcher
        updateTaskStatus(taskId, "Peer Task: implementing (${programmer.name})")
        
        val step2StartTime = System.currentTimeMillis()
        AgentStateStore.setAgentActive(programmer.id, true, "Writing Code")
        
        db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = programmer.name,
                agentRole = programmer.role,
                actionType = "THINKING",
                content = "Designing system structure and writing functional implementation based on Apex research..."
            )
        )
        delay(1500)
        
        val codePrompt = "Write the technical system or code matching: $userPrompt\nResearch context:\n$researchResult"
        val codeResult = generateOutputForAgent(programmer, codePrompt)
        parseAndExecuteAgenticActions(taskId, programmer.name, codeResult)
        
        db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = programmer.name,
                agentRole = programmer.role,
                actionType = "OUTPUT",
                content = codeResult
            )
        )
        
        val step2Duration = System.currentTimeMillis() - step2StartTime
        val step2Tokens = (codePrompt.length + codeResult.length) / 2 + 100
        AgentStateStore.recordExecutionMetrics(programmer.id, step2Duration, step2Tokens)
        AgentStateStore.setAgentActive(programmer.id, false, "Idle")
        
        context += "Code: $codeResult\n\n"
        delay(1000)

        // Step 3: Critic audits
        val critic = agents.firstOrNull { it.role == "Critic" } ?: agents.getOrNull(2) ?: researcher
        updateTaskStatus(taskId, "Peer Task: auditing (${critic.name})")
        
        val step3StartTime = System.currentTimeMillis()
        AgentStateStore.setAgentActive(critic.id, true, "Auditing")
        
        db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = critic.name,
                agentRole = critic.role,
                actionType = "CRITIQUING",
                content = "Auditing researcher's specifications and programmer's implementation..."
            )
        )
        delay(1500)
        
        val criticPrompt = "Audit this solution. Find issues, security leaks or logic flaws:\nResearch: $researchResult\nCode: $codeResult"
        val auditResult = generateOutputForAgent(critic, criticPrompt)
        parseAndExecuteAgenticActions(taskId, critic.name, auditResult)
        
        db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = critic.name,
                agentRole = critic.role,
                actionType = "OUTPUT",
                content = auditResult
            )
        )
        
        val step3Duration = System.currentTimeMillis() - step3StartTime
        val step3Tokens = (criticPrompt.length + auditResult.length) / 2 + 100
        AgentStateStore.recordExecutionMetrics(critic.id, step3Duration, step3Tokens)
        AgentStateStore.setAgentActive(critic.id, false, "Idle")
        
        context += "Critique: $auditResult\n\n"
        delay(1000)

        // Step 4: Executive Coordinator synthesizes final solution
        val executive = agents.firstOrNull { it.role == "Executive" } ?: agents.lastOrNull() ?: researcher
        updateTaskStatus(taskId, "Peer Task: synthesis (${executive.name})")
        
        val step4StartTime = System.currentTimeMillis()
        AgentStateStore.setAgentActive(executive.id, true, "Synthesizing")
        
        db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = executive.name,
                agentRole = executive.role,
                actionType = "THINKING",
                content = "Consolidating swarm artifacts into final unified deliverable..."
            )
        )
        delay(1500)
        
        val execPrompt = "Synthesize the research, code, and critic audit into a final premium response to: $userPrompt\n\nFull workspace history:\n$context"
        val finalResult = generateOutputForAgent(executive, execPrompt)
        parseAndExecuteAgenticActions(taskId, executive.name, finalResult)
        
        db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = executive.name,
                agentRole = executive.role,
                actionType = "FINAL_RESPONSE",
                content = finalResult
            )
        )
        
        val step4Duration = System.currentTimeMillis() - step4StartTime
        val step4Tokens = (execPrompt.length + finalResult.length) / 2 + 100
        AgentStateStore.recordExecutionMetrics(executive.id, step4Duration, step4Tokens)
        AgentStateStore.setAgentActive(executive.id, false, "Idle")
    }

    private suspend fun runConsensusVoteWorkflow(taskId: Int, agents: List<Agent>, userPrompt: String) {
        val agentOutputs = mutableListOf<Pair<Agent, String>>()
        
        // 1. Run all agents in parallel (simulated sequentially for beautiful staggered logs, but acting as independent proposals)
        for (agent in agents) {
            updateTaskStatus(taskId, "Consensus Proposal: (${agent.name})")
            val agentStartTime = System.currentTimeMillis()
            AgentStateStore.setAgentActive(agent.id, true, "Proposing")
            
            db.taskStepDao().insertStep(
                TaskStep(
                    taskId = taskId,
                    agentName = agent.name,
                    agentRole = agent.role,
                    actionType = "THINKING",
                    content = "Drafting unique decentralized proposal from the perspective of ${agent.role}..."
                )
            )
            delay(1200)
            
            val proposalPrompt = "Propose an answers to: $userPrompt"
            val output = generateOutputForAgent(agent, proposalPrompt)
            parseAndExecuteAgenticActions(taskId, agent.name, output)
            
            db.taskStepDao().insertStep(
                TaskStep(
                    taskId = taskId,
                    agentName = agent.name,
                    agentRole = agent.role,
                    actionType = "OUTPUT",
                    content = output
                )
            )
            
            val agentDuration = System.currentTimeMillis() - agentStartTime
            val agentTokens = (proposalPrompt.length + output.length) / 2 + 100
            AgentStateStore.recordExecutionMetrics(agent.id, agentDuration, agentTokens)
            AgentStateStore.setAgentActive(agent.id, false, "Idle")
            
            agentOutputs.add(agent to output)
            delay(800)
        }

        // 2. Voting phase
        updateTaskStatus(taskId, "Consensus: running audit votes")
        db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = "Swarm Router",
                agentRole = "Consensus Router",
                actionType = "VOTING",
                content = "Evaluating proposals. Running scoring algorithms across active nodes..."
            )
        )
        delay(2000)

        // Construct a voting report
        val votePrompt = """
            We have 3 proposals for the task: "$userPrompt".
            
            ${agentOutputs.joinToString("\n\n") { "Proposal by ${it.first.name} (${it.first.role}):\n${it.second}" }}
            
            Analyze these proposals, give each a score out of 10, explain why, and synthesize the best components of each into a final consensus decision.
        """.trimIndent()

        val consensusReport = GeminiService.generate(votePrompt, "You are a decentralized consensus moderator. Your job is to score agent proposals and combine them into a flawless unified outcome.")
        
        db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = "Consensus Router",
                agentRole = "Consensus Router",
                actionType = "FINAL_RESPONSE",
                content = consensusReport
            )
        )
    }

    private suspend fun generateOutputForAgent(agent: Agent, prompt: String): String {
        val activeSkills = db.claudeSkillDao().getAllSkillsSync().filter { it.isEnabled }
        val skillsContext = if (activeSkills.isNotEmpty()) {
            "\n[AVAILABLE SYSTEM TOOLS & MCP SKILLS]\n" +
            activeSkills.joinToString("\n") { skill ->
                "- [${skill.category}] Tool: ${skill.name} (Requires MCP Link: ${skill.requiredMcpServerType}). Description: ${skill.description}. Example Usage: ${skill.usageExample}"
            } + "\nTo actually invoke one of these tools, emit a line in this exact format: " +
            "MCP_CALL: <tool name> | <json arguments object>. " +
            "The call only succeeds if the tool's MCP server is currently Connected; otherwise it will fail for real. " +
            "Do not fabricate or narrate tool output yourself -- only the MCP_CALL directive produces real results.\n"
        } else {
            ""
        }
        val systemPromptWithSkills = agent.systemPrompt + skillsContext

        try {
            val nodes = db.ollamaNodeDao().getAllNodesSync()
            
            // Find all matching online nodes and select the one with the lowest latency (load balancer)
            val onlineNode = nodes.filter { node ->
                node.status == "Online" && 
                (node.availableModels.split(",").map { it.trim().lowercase() }.any { 
                    it.contains(agent.modelName.lowercase()) || agent.modelName.lowercase().contains(it)
                } || agent.modelName.lowercase().contains(node.name.lowercase()))
            }.minByOrNull { node ->
                if (node.latencyMs > 0) node.latencyMs else 999999
            }
            
            if (onlineNode != null) {
                val response = OllamaService.generate(
                    nodeUrl = onlineNode.url,
                    modelName = agent.modelName,
                    prompt = prompt,
                    systemPrompt = systemPromptWithSkills,
                    apiKey = resolveApiKeyForNode(onlineNode)
                )
                if (response != null && response.isNotEmpty()) {
                    return response
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SwarmEngine", "Ollama routing error: ${e.localizedMessage}")
        }
        
        // Fallback to Gemini
        return GeminiService.generate(prompt, systemPromptWithSkills)
    }

    private suspend fun parseAndExecuteAgenticActions(taskId: Int, agentName: String, output: String) {
        for (line in output.split("\n")) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("git ") || trimmed.startsWith("$ git ") ->
                    executeAgenticGitCommand(taskId, agentName, trimmed.removePrefix("$ ").trim())
                trimmed.startsWith("MCP_CALL:") ->
                    executeAgenticMcpCall(taskId, agentName, trimmed.removePrefix("MCP_CALL:").trim())
            }
        }
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
            val (result, status) = withContext(Dispatchers.IO) {
                gitService.mirrorFiles(files)
                gitService.commitAll(agentName, "${agentName.lowercase().replace(" ", "-")}@swarm.local", message)
            }

            if (result != null && status is GitOpResult.Success) {
                db.gitCommitDao().insertCommit(
                    GitCommit(commitHash = result.hash, author = result.author, message = result.message, timestamp = result.timestamp)
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
            val prefs = appContext.getSharedPreferences("ollama_swarm_prefs", android.content.Context.MODE_PRIVATE)
            val remoteUrl = prefs.getString("git_remote_url", null)
            val token = SecurePrefs.getGitToken(appContext)

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

            val status = withContext(Dispatchers.IO) { gitService.push(remoteUrl, token) }
            when (status) {
                is GitOpResult.Success -> {
                    val headHash = withContext(Dispatchers.IO) { gitService.localHeadHash() }
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

    private suspend fun executeAgenticMcpCall(taskId: Int, agentName: String, payload: String) {
        val parts = payload.split("|", limit = 2)
        val skillName = parts.getOrNull(0)?.trim().orEmpty()
        val argsJson = parts.getOrNull(1)?.trim().orEmpty()

        val skill = db.claudeSkillDao().getAllSkillsSync()
            .firstOrNull { it.name.equals(skillName, ignoreCase = true) && it.isEnabled }
        if (skill == null) {
            db.taskStepDao().insertStep(
                TaskStep(
                    taskId = taskId,
                    agentName = "MCP Tool",
                    agentRole = "System",
                    actionType = "MCP_CALL_FAILED",
                    content = "$agentName tried to call unknown or disabled skill '$skillName'."
                )
            )
            return
        }

        val server = db.mcpServerDao().getAllServersSync()
            .firstOrNull { it.type == skill.requiredMcpServerType && it.status == "Connected" }
        if (server == null) {
            db.taskStepDao().insertStep(
                TaskStep(
                    taskId = taskId,
                    agentName = "MCP Tool",
                    agentRole = "System",
                    actionType = "MCP_CALL_FAILED",
                    content = "$agentName tried to call '$skillName' but no Connected MCP server of type '${skill.requiredMcpServerType}' is available."
                )
            )
            return
        }

        val arguments = parseJsonArguments(argsJson)
        val authToken = SecurePrefs.getMcpToken(appContext, server.id)

        val outcome = mcpClient.initialize(server.sourceUrl, authToken).mapCatching { session ->
            mcpClient.callTool(server.sourceUrl, session, authToken, skill.name, arguments).getOrThrow()
        }

        outcome.fold(
            onSuccess = { toolResult ->
                db.taskStepDao().insertStep(
                    TaskStep(
                        taskId = taskId,
                        agentName = "MCP Tool",
                        agentRole = "System",
                        actionType = "MCP_TOOL_CALL",
                        content = "$agentName called '$skillName' on ${server.name}. Result: $toolResult"
                    )
                )
            },
            onFailure = { error ->
                db.taskStepDao().insertStep(
                    TaskStep(
                        taskId = taskId,
                        agentName = "MCP Tool",
                        agentRole = "System",
                        actionType = "MCP_CALL_FAILED",
                        content = "$agentName's call to '$skillName' on ${server.name} failed: ${error.message}"
                    )
                )
            }
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

    private suspend fun runDynamicRoutingWorkflow(taskId: Int, agents: List<Agent>, userPrompt: String) {
        val routerAgent = agents.find { it.role.lowercase().contains("executive") } 
            ?: agents.find { it.name.lowercase().contains("orchestrator") } 
            ?: agents.first()

        updateTaskStatus(taskId, "Routing (${routerAgent.name})")

        val routingStepId = db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = routerAgent.name,
                agentRole = routerAgent.role,
                actionType = "THINKING",
                content = "Analyzing task requirements to design dynamic execution routing path..."
            )
        ).toInt()
        delay(1500)

        val routingPrompt = """
            You are the Dynamic Routing Orchestrator. We have a swarm with the following available agents:
            ${agents.joinToString("\n") { "- Agent ID: ${it.id}, Name: ${it.name}, Role: ${it.role}, Specialty: ${it.systemPrompt.take(100)}..." }}
            
            The user's task is: "$userPrompt"
            
            Analyze the task requirements. Decide which of the available agents should be involved in executing this task, and in what order. 
            Respond with a valid, raw, unformatted JSON list of Agent IDs representing the routing path. Do NOT include markdown code blocks.
            Format example:
            [2, 4, 3]
        """.trimIndent()

        val routerResponse = generateOutputForAgent(routerAgent, routingPrompt)
        val routedIds = Regex("""\d+""").findAll(routerResponse).map { it.value.toInt() }.toList()
        
        val routedAgents = routedIds.mapNotNull { id -> agents.find { it.id == id } }
        val finalRoutedAgents = if (routedAgents.isEmpty()) agents.filter { it.id != routerAgent.id } else routedAgents

        val routedNames = finalRoutedAgents.joinToString(" -> ") { it.name }
        
        db.taskStepDao().insertStep(
            TaskStep(
                id = routingStepId,
                taskId = taskId,
                agentName = routerAgent.name,
                agentRole = routerAgent.role,
                actionType = "ROUTING",
                content = "Dynamic Swarm Routing path calculated: ${routerAgent.name} -> $routedNames -> Synthesis"
            )
        )
        delay(1000)

        var context = "Initial Task Request:\n\"$userPrompt\"\n\n"
        for (index in finalRoutedAgents.indices) {
            val agent = finalRoutedAgents[index]
            val stepStartTime = System.currentTimeMillis()
            AgentStateStore.setAgentActive(agent.id, true, "Running Routed Task")
            
            updateTaskStatus(taskId, "Routed: ${agent.name}")
            
            val stepId = db.taskStepDao().insertStep(
                TaskStep(
                    taskId = taskId,
                    agentName = agent.name,
                    agentRole = agent.role,
                    actionType = "THINKING",
                    content = "Executing routed task slice..."
                )
            ).toInt()
            delay(1500)

            val promptForAgent = "Previous context:\n$context\n\nPerform your tasks on: $userPrompt"
            val agentOutput = generateOutputForAgent(agent, promptForAgent)
            
            parseAndExecuteAgenticActions(taskId, agent.name, agentOutput)

            db.taskStepDao().insertStep(
                TaskStep(
                    id = stepId,
                    taskId = taskId,
                    agentName = agent.name,
                    agentRole = agent.role,
                    actionType = "OUTPUT",
                    content = agentOutput
                )
            )

            val stepDuration = System.currentTimeMillis() - stepStartTime
            val approxTokens = (promptForAgent.length + agentOutput.length) / 2 + 100
            AgentStateStore.recordExecutionMetrics(agent.id, stepDuration, approxTokens)
            AgentStateStore.setAgentActive(agent.id, false, "Idle")
            
            context += "--- ${agent.name} (${agent.role}) Output ---\n$agentOutput\n\n"
            delay(1000)
        }

        updateTaskStatus(taskId, "Synthesis (${routerAgent.name})")
        val synthesisStartTime = System.currentTimeMillis()
        AgentStateStore.setAgentActive(routerAgent.id, true, "Synthesizing")
        
        val synthesisStepId = db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = routerAgent.name,
                agentRole = routerAgent.role,
                actionType = "THINKING",
                content = "Synthesizing dynamic routing results..."
            )
        ).toInt()
        delay(1500)

        val synthesisPrompt = "Synthesize all results into a final premium response to: $userPrompt\n\nFull workspace history:\n$context"
        val finalResult = generateOutputForAgent(routerAgent, synthesisPrompt)
        
        db.taskStepDao().insertStep(
            TaskStep(
                id = synthesisStepId,
                taskId = taskId,
                agentName = routerAgent.name,
                agentRole = routerAgent.role,
                actionType = "FINAL_RESPONSE",
                content = finalResult
            )
        )

        val synthesisDuration = System.currentTimeMillis() - synthesisStartTime
        val synthesisTokens = (synthesisPrompt.length + finalResult.length) / 2 + 100
        AgentStateStore.recordExecutionMetrics(routerAgent.id, synthesisDuration, synthesisTokens)
        AgentStateStore.setAgentActive(routerAgent.id, false, "Idle")
    }

    private suspend fun updateTaskStatus(taskId: Int, status: String) {
        val currentTask = db.swarmTaskDao().getTaskById(taskId)
        if (currentTask != null) {
            db.swarmTaskDao().updateTask(currentTask.copy(status = status))
        }
    }
}
