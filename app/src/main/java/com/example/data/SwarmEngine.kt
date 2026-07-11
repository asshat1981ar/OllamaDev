package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class SwarmEngine(private val db: AppDatabase) {

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
        try {
            val nodes = db.ollamaNodeDao().getAllNodesSync()
            
            // Find an online node that matches the agent's modelName or serves it
            val onlineNode = nodes.firstOrNull { node ->
                node.status == "Online" && 
                (node.availableModels.split(",").map { it.trim().lowercase() }.any { 
                    it.contains(agent.modelName.lowercase()) || agent.modelName.lowercase().contains(it)
                } || agent.modelName.lowercase().contains(node.name.lowercase()))
            }
            
            if (onlineNode != null) {
                val response = OllamaService.generate(
                    nodeUrl = onlineNode.url,
                    modelName = agent.modelName,
                    prompt = prompt,
                    systemPrompt = agent.systemPrompt
                )
                if (response != null && response.isNotEmpty()) {
                    return response
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SwarmEngine", "Ollama routing error: ${e.localizedMessage}")
        }
        
        // Fallback to Gemini
        return GeminiService.generate(prompt, agent.systemPrompt)
    }

    private suspend fun updateTaskStatus(taskId: Int, status: String) {
        val currentTask = db.swarmTaskDao().getTaskById(taskId)
        if (currentTask != null) {
            db.swarmTaskDao().updateTask(currentTask.copy(status = status))
        }
    }
}
