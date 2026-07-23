package com.example.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class SwarmEngine(
    private val db: AppDatabaseInterface,
    private val gitService: GitService,
    private val mcpClient: McpClientInterface,
    private val appContext: android.content.Context,
    private val securePrefs: SecurePrefsInterface,
    private val ollamaService: OllamaService = OllamaServiceDefault,
    private val dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) {

    private val llmRouter: LlmRouterInterface = LlmRouter(ollamaService, db.ollamaNodeDao(), db.claudeSkillDao(), securePrefs, dispatcher)

    suspend fun executeTask(
        config: SwarmConfig,
        userPrompt: String,
        contextPrefix: String = "",
        onTaskCreated: (Int) -> Unit = {}
    ): Int = withContext(dispatcher) {
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

        // 3. Create initial pending SwarmTask. The task's own prompt field stays the raw
        // userPrompt (so task history stays readable) -- contextPrefix is only woven into the
        // prompt text actually handed to the coordination-mode workflow below.
        val task = SwarmTask(
            prompt = userPrompt,
            status = "Thinking",
            swarmName = config.name,
            timestamp = System.currentTimeMillis()
        )
        val taskId = db.swarmTaskDao().insertTask(task).toInt()
        onTaskCreated(taskId)

        val effectivePrompt = if (contextPrefix.isBlank()) userPrompt else "$contextPrefix\n\n$userPrompt"

        try {
            when (config.coordinationMode) {
                "SEQUENTIAL" -> runSequentialWorkflow(taskId, swarmAgents, effectivePrompt)
                "PEER_TO_PEER" -> runPeerToPeerWorkflow(taskId, swarmAgents, effectivePrompt)
                "CONSENSUS_VOTE" -> runConsensusVoteWorkflow(taskId, swarmAgents, effectivePrompt)
                "DYNAMIC_ROUTING" -> runDynamicRoutingWorkflow(taskId, swarmAgents, effectivePrompt)
                "AGENTIC_LOOP" -> runAgenticLoopWorkflow(taskId, swarmAgents, effectivePrompt)
                else -> runSequentialWorkflow(taskId, swarmAgents, effectivePrompt)
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
            val agentOutput = streamIntoStep(taskId, stepId, agent.name, agent.role, "THINKING") { onToken ->
                generateOutputForAgentStreaming(agent, promptForAgent, onToken)
            }

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
        
        val researchStepId = db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = researcher.name,
                agentRole = researcher.role,
                actionType = "THINKING",
                content = "Conducting architectural discovery of decentralized elements..."
            )
        ).toInt()
        delay(1500)

        val researchPrompt = "Analyze and research requirements for: $userPrompt"
        val researchResult = streamIntoStep(taskId, researchStepId, researcher.name, researcher.role, "THINKING") { onToken ->
            generateOutputForAgentStreaming(researcher, researchPrompt, onToken)
        }
        parseAndExecuteAgenticActions(taskId, researcher.name, researchResult)

        db.taskStepDao().insertStep(
            TaskStep(
                id = researchStepId,
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
        
        val codeStepId = db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = programmer.name,
                agentRole = programmer.role,
                actionType = "THINKING",
                content = "Designing system structure and writing functional implementation based on Apex research..."
            )
        ).toInt()
        delay(1500)

        val codePrompt = "Write the technical system or code matching: $userPrompt\nResearch context:\n$researchResult"
        val codeResult = streamIntoStep(taskId, codeStepId, programmer.name, programmer.role, "THINKING") { onToken ->
            generateOutputForAgentStreaming(programmer, codePrompt, onToken)
        }
        parseAndExecuteAgenticActions(taskId, programmer.name, codeResult)

        db.taskStepDao().insertStep(
            TaskStep(
                id = codeStepId,
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
        
        val auditStepId = db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = critic.name,
                agentRole = critic.role,
                actionType = "CRITIQUING",
                content = "Auditing researcher's specifications and programmer's implementation..."
            )
        ).toInt()
        delay(1500)

        val criticPrompt = "Audit this solution. Find issues, security leaks or logic flaws:\nResearch: $researchResult\nCode: $codeResult"
        val auditResult = streamIntoStep(taskId, auditStepId, critic.name, critic.role, "CRITIQUING") { onToken ->
            generateOutputForAgentStreaming(critic, criticPrompt, onToken)
        }
        parseAndExecuteAgenticActions(taskId, critic.name, auditResult)

        db.taskStepDao().insertStep(
            TaskStep(
                id = auditStepId,
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
        
        val synthesisStepId = db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = executive.name,
                agentRole = executive.role,
                actionType = "THINKING",
                content = "Consolidating swarm artifacts into final unified deliverable..."
            )
        ).toInt()
        delay(1500)

        val execPrompt = "Synthesize the research, code, and critic audit into a final premium response to: $userPrompt\n\nFull workspace history:\n$context"
        val finalResult = streamIntoStep(taskId, synthesisStepId, executive.name, executive.role, "THINKING") { onToken ->
            generateOutputForAgentStreaming(executive, execPrompt, onToken)
        }
        parseAndExecuteAgenticActions(taskId, executive.name, finalResult)

        db.taskStepDao().insertStep(
            TaskStep(
                id = synthesisStepId,
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
            
            val proposalStepId = db.taskStepDao().insertStep(
                TaskStep(
                    taskId = taskId,
                    agentName = agent.name,
                    agentRole = agent.role,
                    actionType = "THINKING",
                    content = "Drafting unique decentralized proposal from the perspective of ${agent.role}..."
                )
            ).toInt()
            delay(1200)

            val proposalPrompt = "Propose an answers to: $userPrompt"
            val output = streamIntoStep(taskId, proposalStepId, agent.name, agent.role, "THINKING") { onToken ->
                generateOutputForAgentStreaming(agent, proposalPrompt, onToken)
            }
            parseAndExecuteAgenticActions(taskId, agent.name, output)

            db.taskStepDao().insertStep(
                TaskStep(
                    id = proposalStepId,
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
        val votingStepId = db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = "Swarm Router",
                agentRole = "Consensus Router",
                actionType = "VOTING",
                content = "Evaluating proposals. Running scoring algorithms across active nodes..."
            )
        ).toInt()
        delay(2000)

        // Construct a voting report
        val votePrompt = """
            We have 3 proposals for the task: "$userPrompt".

            ${agentOutputs.joinToString("\n\n") { "Proposal by ${it.first.name} (${it.first.role}):\n${it.second}" }}

            Analyze these proposals, give each a score out of 10, explain why, and synthesize the best components of each into a final consensus decision.
        """.trimIndent()

        val consensusReport = streamIntoStep(taskId, votingStepId, "Swarm Router", "Consensus Router", "VOTING") { onToken ->
            generateFreeformStreaming(votePrompt, "You are a decentralized consensus moderator. Your job is to score agent proposals and combine them into a flawless unified outcome.", onToken)
        }

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

    private data class Todo(val text: String, val done: Boolean, val role: String? = null, val retries: Int = 0)

    private val roleTagRegex = Regex("""^\[(\w+)]\s*(.*)$""")

    /** Keyword fallback for checklist lines the plan prompt didn't tag with a role. */
    private fun inferRoleFromText(text: String): String {
        val t = text.lowercase()
        return when {
            listOf("test", "verify", "qa", "bug").any { t.contains(it) } -> "QA"
            listOf("design", "architecture", "schema", "contract").any { t.contains(it) } -> "Architect"
            else -> "Programmer"
        }
    }

    /** Matches a checklist item's tagged/inferred role against the swarm roster, falling back
     *  to [fallback] (the planning agent) when no agent has a matching role. */
    private fun pickAgentForRole(agents: List<Agent>, role: String?, fallback: Agent): Agent =
        role?.let { r ->
            agents.firstOrNull { it.role.equals(r, ignoreCase = true) }
                ?: agents.firstOrNull { it.role.contains(r, ignoreCase = true) }
        } ?: fallback

    /** Verification always routes to a QA/Bug-Hunter-style agent when the roster has one. */
    private fun pickQaAgent(agents: List<Agent>, fallback: Agent): Agent =
        agents.firstOrNull { it.role.lowercase().let { r -> r.contains("qa") || r.contains("bug") } }
            ?: agents.firstOrNull { it.name.lowercase().contains("bug hunter") }
            ?: fallback

    /**
     * Manus-AI-style autonomous plan -> act -> verify loop. A single Architect-role agent (falling
     * back to `agents.first()` if no Architect is present) owns planning and final synthesis; each
     * checklist item is routed to the agent matching its tagged/inferred role
     * (Architect/Programmer/QA) via [pickAgentForRole], and a QA-role agent (via [pickQaAgent])
     * always owns the verify phase that follows each act step -- this is what gives a
     * Bug-Hunter-style agent ownership of verification rather than the acting agent grading its
     * own work. The checklist itself is agent-authored, then the loop iterates: pick the next
     * unfinished item, act on it (optionally emitting a git/MCP_CALL/WRITE_FILE directive via the
     * same [parseAndExecuteAgenticActions] parser the other coordination modes use), verify it
     * (invoking real MCP tooling when available rather than fabricating a result), retry on
     * failure up to a small cap, and repeat until the checklist is clear or [maxIterations] is hit
     * -- a guardrail against a checklist that never resolves.
     */
    private suspend fun runAgenticLoopWorkflow(taskId: Int, agents: List<Agent>, userPrompt: String) {
        val planningAgent = agents.firstOrNull { it.role.equals("Architect", ignoreCase = true) }
            ?: agents.firstOrNull() ?: return
        val maxIterations = 16
        val maxRetriesPerTodo = 2

        updateTaskStatus(taskId, "Planning (${planningAgent.name})")
        AgentStateStore.setAgentActive(planningAgent.id, true, "Planning")

        val planStepId = db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = planningAgent.name,
                agentRole = planningAgent.role,
                actionType = "PLAN",
                content = "Drafting plan..."
            )
        ).toInt()

        val planPrompt = "Break the following request into a short checklist of concrete steps, one per " +
            "line, in the form '- [Role] step text' where Role is one of: Architect, Programmer, QA. " +
            "Request: $userPrompt"
        val planText = streamIntoStep(taskId, planStepId, planningAgent.name, planningAgent.role, "PLAN") { onToken ->
            generateOutputForAgentStreaming(planningAgent, planPrompt, onToken, preferCloud = true)
        }

        var todos = planText.lines()
            .map { it.trim() }
            .filter { it.startsWith("-") }
            .map { line ->
                val body = line.removePrefix("-").trim()
                val match = roleTagRegex.find(body)
                if (match != null) {
                    Todo(text = match.groupValues[2].trim(), done = false, role = match.groupValues[1])
                } else {
                    Todo(text = body, done = false, role = inferRoleFromText(body))
                }
            }
        if (todos.isEmpty()) {
            todos = listOf(Todo(text = userPrompt, done = false, role = inferRoleFromText(userPrompt)))
        }

        fun renderChecklist() = todos.joinToString("\n") { "- [${if (it.done) "x" else " "}] ${it.text}" }
        db.taskStepDao().insertStep(
            TaskStep(id = planStepId, taskId = taskId, agentName = planningAgent.name, agentRole = planningAgent.role, actionType = "PLAN", content = renderChecklist())
        )

        val transcript = StringBuilder(userPrompt)
        var iteration = 0
        while (todos.any { !it.done } && iteration < maxIterations) {
            iteration++
            val next = todos.first { !it.done }
            val actor = pickAgentForRole(agents, next.role, planningAgent)

            updateTaskStatus(taskId, "Working (${actor.name})")
            AgentStateStore.setAgentActive(actor.id, true, "Working: ${next.text}")

            val thinkStepId = db.taskStepDao().insertStep(
                TaskStep(
                    taskId = taskId,
                    agentName = actor.name,
                    agentRole = actor.role,
                    actionType = "THINKING",
                    content = "Working on: ${next.text}"
                )
            ).toInt()

            val actPrompt = "Context so far:\n$transcript\n\nCurrent step: ${next.text}\n" +
                "Respond with your reasoning, and if a tool is needed use the existing " +
                "'MCP_CALL: <tool> | <json args>' or git-command directive format. If you need to " +
                "create or modify a workspace file, emit a line 'WRITE_FILE: <relative/path.ext>' " +
                "-- the actual file content is generated in a separate follow-up step, so do not " +
                "inline file contents here."
            val decision = streamIntoStep(taskId, thinkStepId, actor.name, actor.role, "THINKING") { onToken ->
                generateOutputForAgentStreaming(actor, actPrompt, onToken, preferCloud = true)
            }

            // Reuse the same directive parser every other coordination mode uses -- no new
            // tool-execution plumbing needed for the agentic loop's "act" step.
            parseAndExecuteAgenticActions(taskId, actor.name, decision)

            db.taskStepDao().insertStep(
                TaskStep(id = thinkStepId, taskId = taskId, agentName = actor.name, agentRole = actor.role, actionType = "OUTPUT", content = decision)
            )
            transcript.append("\n${next.text} -> $decision")
            AgentStateStore.setAgentActive(actor.id, false, "Idle")

            // Verify phase: a QA-role agent checks the step was actually done correctly, invoking
            // real MCP tooling when available rather than fabricating a result.
            val qaAgent = pickQaAgent(agents, planningAgent)
            updateTaskStatus(taskId, "Verifying (${qaAgent.name})")
            AgentStateStore.setAgentActive(qaAgent.id, true, "Verifying: ${next.text}")

            val verifyStepId = db.taskStepDao().insertStep(
                TaskStep(taskId = taskId, agentName = qaAgent.name, agentRole = qaAgent.role, actionType = "VERIFYING", content = "Verifying: ${next.text}")
            ).toInt()
            val verifyPrompt = "The following step was just attempted:\n${next.text}\n\nAgent output:\n$decision\n\n" +
                "As QA, verify this was actually done correctly. If real test/execution tooling is " +
                "available, invoke it via 'MCP_CALL: <tool> | <json args>' and report the real " +
                "result -- do not fabricate output. If no tooling is available, say so plainly."
            val verifyDecision = streamIntoStep(taskId, verifyStepId, qaAgent.name, qaAgent.role, "VERIFYING") { onToken ->
                generateOutputForAgentStreaming(qaAgent, verifyPrompt, onToken, preferCloud = true)
            }
            val verifyOutcome = parseAndExecuteAgenticActions(taskId, qaAgent.name, verifyDecision, "EXEC_RESULT", "EXEC_RESULT_FAILED")
            db.taskStepDao().insertStep(
                TaskStep(id = verifyStepId, taskId = taskId, agentName = qaAgent.name, agentRole = qaAgent.role, actionType = "VERIFYING", content = verifyDecision)
            )
            AgentStateStore.setAgentActive(qaAgent.id, false, "Idle")

            // Only treat this as a real failure when a tool call was actually attempted and either
            // failed outright or its own result text reads as a failure -- MCP tools have no
            // standardized structured pass/fail schema in this app, so this is a text heuristic
            // (same class as the existing sandbox self-healing check), not a new protocol.
            val looksFailed = verifyOutcome.mcpCallAttempted && (
                !verifyOutcome.mcpCallSucceeded ||
                verifyOutcome.mcpResultText?.lowercase()?.let { t -> listOf("fail", "error", "exception").any(t::contains) } == true
            )

            if (looksFailed && next.retries < maxRetriesPerTodo) {
                // Leave `done = false` so the loop re-picks this same todo; the failure text is
                // already in `transcript` (appended above) so the next attempt's prompt sees it.
                todos = todos.map { if (it === next) it.copy(retries = it.retries + 1) else it }
            } else {
                val finalText = if (looksFailed) "${next.text} [UNRESOLVED after $maxRetriesPerTodo attempts]" else next.text
                todos = todos.map { if (it === next) it.copy(done = true, text = finalText) else it }
                if (!looksFailed) {
                    autoCheckpoint(taskId, agentName = qaAgent.name, todoText = next.text)
                }
            }

            db.taskStepDao().insertStep(
                TaskStep(id = planStepId, taskId = taskId, agentName = planningAgent.name, agentRole = planningAgent.role, actionType = "PLAN", content = renderChecklist())
            )
        }

        updateTaskStatus(taskId, "Synthesis (${planningAgent.name})")
        AgentStateStore.setAgentActive(planningAgent.id, true, "Synthesizing")

        val finalStepId = db.taskStepDao().insertStep(
            TaskStep(
                taskId = taskId,
                agentName = planningAgent.name,
                agentRole = planningAgent.role,
                actionType = "FINAL_RESPONSE",
                content = "Summarizing..."
            )
        ).toInt()
        val finalPrompt = "Summarize the outcome for the user based on:\n$transcript"
        val finalContent = streamIntoStep(taskId, finalStepId, planningAgent.name, planningAgent.role, "FINAL_RESPONSE") { onToken ->
            generateOutputForAgentStreaming(planningAgent, finalPrompt, onToken, preferCloud = true)
        }
        // Throttling inside streamIntoStep may have skipped the very last partial write, so
        // write the complete, untruncated text unconditionally -- matching every other
        // coordination mode's final-replace pattern.
        db.taskStepDao().insertStep(
            TaskStep(id = finalStepId, taskId = taskId, agentName = planningAgent.name, agentRole = planningAgent.role, actionType = "FINAL_RESPONSE", content = finalContent)
        )

        AgentStateStore.setAgentActive(planningAgent.id, false, "Idle")
    }

    private suspend fun generateOutputForAgentStreaming(
        agent: Agent,
        prompt: String,
        onToken: suspend (String) -> Unit,
        preferCloud: Boolean = false
    ): String = llmRouter.generateForAgent(agent, prompt, preferCloud, onToken)

    /**
     * Single entry point for reaching an LLM outside of a specific agent's persona (consensus
     * moderator scoring, voice-command routing, chat replies, sandbox simulation, self-healing --
     * anything that previously fell back to Gemini). Always resolves against the Ollama node pool.
     */
    suspend fun generateFreeform(prompt: String, systemPrompt: String, preferCloud: Boolean = false): String =
        llmRouter.generateFreeform(prompt, systemPrompt, preferCloud)

    private suspend fun generateFreeformStreaming(
        prompt: String,
        systemPrompt: String,
        onToken: suspend (String) -> Unit
    ): String = llmRouter.generateFreeformStreaming(prompt, systemPrompt, onToken)

    /**
     * Streams [generate]'s result into TaskStep [stepId] in place (via the existing REPLACE-on-id
     * insert), throttled so a fast token stream doesn't turn into a DB write per NDJSON line. The
     * final, untruncated content is still written unconditionally by the caller once [generate]
     * returns, so throttling here only affects how smoothly the in-progress text grows.
     */
    private suspend fun streamIntoStep(
        taskId: Int,
        stepId: Int,
        agentName: String,
        agentRole: String,
        actionType: String,
        generate: suspend (onToken: suspend (String) -> Unit) -> String
    ): String {
        val throttle = StreamThrottle()
        return generate { partial ->
            if (throttle.shouldEmit(partial)) {
                db.taskStepDao().insertStep(
                    TaskStep(id = stepId, taskId = taskId, agentName = agentName, agentRole = agentRole, actionType = actionType, content = partial)
                )
            }
        }
    }

    /** Result of scanning one agent output for directives -- used by the verify phase to decide
     *  whether a real tool call was attempted and whether it looked like it failed. */
    private data class ActionOutcome(val mcpCallAttempted: Boolean, val mcpCallSucceeded: Boolean, val mcpResultText: String?)

    private suspend fun parseAndExecuteAgenticActions(
        taskId: Int,
        agentName: String,
        output: String,
        mcpSuccessActionType: String = "MCP_TOOL_CALL",
        mcpFailureActionType: String = "MCP_CALL_FAILED"
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
            val (result, status) = withContext(Dispatchers.IO) {
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
            val prefs = appContext.getSharedPreferences("ollama_swarm_prefs", android.content.Context.MODE_PRIVATE)
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
        val proposedContent = generateFreeform(
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
    private suspend fun autoCheckpoint(taskId: Int, agentName: String, todoText: String) {
        val files = db.workspaceFileDao().getAllFiles().first()
        val (result, status) = withContext(Dispatchers.IO) {
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

        val routerResponse = streamIntoStep(taskId, routingStepId, routerAgent.name, routerAgent.role, "THINKING") { onToken ->
            generateOutputForAgentStreaming(routerAgent, routingPrompt, onToken)
        }
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
            val agentOutput = streamIntoStep(taskId, stepId, agent.name, agent.role, "THINKING") { onToken ->
                generateOutputForAgentStreaming(agent, promptForAgent, onToken)
            }

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
        val finalResult = streamIntoStep(taskId, synthesisStepId, routerAgent.name, routerAgent.role, "THINKING") { onToken ->
            generateOutputForAgentStreaming(routerAgent, synthesisPrompt, onToken)
        }

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
