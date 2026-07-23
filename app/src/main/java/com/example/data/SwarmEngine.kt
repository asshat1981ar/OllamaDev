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

    // ORDER MATTERS: actionExecutor depends on llmRouter, stepRunner depends on both.
    // Property initializers in Kotlin run top-to-bottom in declaration order; reordering breaks init.
    private val llmRouter: LlmRouterInterface = LlmRouter(ollamaService, db.ollamaNodeDao(), db.claudeSkillDao(), securePrefs, dispatcher)
    private val actionExecutor: AgenticActionExecutorInterface = AgenticActionExecutor(db, gitService, mcpClient, appContext, securePrefs, llmRouter, dispatcher)
    private val stepRunner: StepRunner = StepRunner(db.taskStepDao(), llmRouter, actionExecutor)

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
                    agentRole = "system",
                    actionType = "ERROR",
                    content = e.localizedMessage ?: e.javaClass.simpleName
                )
            )
        }

        return@withContext taskId
    }

    // ============================================================================
    // Coordination-mode workflows. Each one composes StepRunner.run() calls
    // (which own the 8-beat step loop: placeholder -> stream -> parse directives
    // -> final-reinsert -> metrics -> idle -> inter-step delay) with workflow-
    // specific prompt building and agent routing. The byte-for-byte per-step
    // strings (updateTaskStatus, activeLabel, actionType, timing) match the
    // pre-refactor engine so the 8 existing SwarmEngine*Test.kt files stay green
    // without modification.
    // ============================================================================

    private suspend fun runSequentialWorkflow(taskId: Int, agents: List<Agent>, userPrompt: String) {
        var context = "Initial Task Request:\n\"$userPrompt\"\n\n"
        for (index in agents.indices) {
            val agent = agents[index]
            val isLast = index == agents.size - 1
            AgentStateStore.setAgentActive(agent.id, true, if (isLast) "Synthesizing" else "Thinking")
            updateTaskStatus(taskId, "Thinking (${agent.name})")
            val prompt = if (index == 0) {
                "Complete the following task: $userPrompt"
            } else {
                "Previous swarm analysis:\n$context\n\nYour task is to review, add your specialty expertise (${agent.role}), and build upon this. Respond to: $userPrompt"
            }
            val result = stepRunner.run(taskId, { updateTaskStatus(taskId, it) }, StepRequest(
                agent = agent,
                prompt = prompt,
                finalActionType = if (isLast) "FINAL_RESPONSE" else "OUTPUT",
                activeLabel = if (isLast) "Synthesizing" else "Thinking"
            ))
            context += "--- ${agent.name} (${agent.role}) Output ---\n${result.output}\n\n"
        }
    }

    private suspend fun runPeerToPeerWorkflow(taskId: Int, agents: List<Agent>, userPrompt: String) {
        var context = "Initial Task Request:\n\"$userPrompt\"\n\n"

        // Step 1: Researcher
        val researcher = agents.firstOrNull { it.role == "Researcher" } ?: agents[0]
        updateTaskStatus(taskId, "Peer Task: researching (${researcher.name})")
        val researchPrompt = "Analyze and research requirements for: $userPrompt"
        val researchResult = stepRunner.run(taskId, { updateTaskStatus(taskId, it) }, StepRequest(
            agent = researcher,
            prompt = researchPrompt,
            thinkingContent = "Conducting architectural discovery of decentralized elements...",
            activeLabel = "Researching"
        )).output
        context += "Research: $researchResult\n\n"

        // Step 2: Coder/Programmer
        val programmer = agents.firstOrNull { it.role == "Programmer" } ?: agents.getOrNull(1) ?: researcher
        updateTaskStatus(taskId, "Peer Task: implementing (${programmer.name})")
        val codePrompt = "Write the technical system or code matching: $userPrompt\nResearch context:\n$researchResult"
        val codeResult = stepRunner.run(taskId, { updateTaskStatus(taskId, it) }, StepRequest(
            agent = programmer,
            prompt = codePrompt,
            thinkingContent = "Designing system structure and writing functional implementation based on Apex research...",
            activeLabel = "Writing Code"
        )).output
        context += "Code: $codeResult\n\n"

        // Step 3: Critic
        val critic = agents.firstOrNull { it.role == "Critic" } ?: agents.getOrNull(2) ?: researcher
        updateTaskStatus(taskId, "Peer Task: auditing (${critic.name})")
        val criticPrompt = "Audit this solution. Find issues, security leaks or logic flaws:\nResearch: $researchResult\nCode: $codeResult"
        val auditResult = stepRunner.run(taskId, { updateTaskStatus(taskId, it) }, StepRequest(
            agent = critic,
            prompt = criticPrompt,
            thinkingActionType = "CRITIQUING",
            finalActionType = "OUTPUT",
            activeLabel = "Auditing",
            thinkingContent = "Auditing researcher's specifications and programmer's implementation..."
        )).output
        context += "Critique: $auditResult\n\n"

        // Step 4: Executive Coordinator
        val executive = agents.firstOrNull { it.role == "Executive" } ?: agents.lastOrNull() ?: researcher
        updateTaskStatus(taskId, "Peer Task: synthesis (${executive.name})")
        val execPrompt = "Synthesize the research, code, and critic audit into a final premium response to: $userPrompt\n\nFull workspace history:\n$context"
        stepRunner.run(taskId, { updateTaskStatus(taskId, it) }, StepRequest(
            agent = executive,
            prompt = execPrompt,
            finalActionType = "FINAL_RESPONSE",
            activeLabel = "Synthesizing",
            thinkingContent = "Consolidating swarm artifacts into final unified deliverable..."
        ))
    }

    private suspend fun runConsensusVoteWorkflow(taskId: Int, agents: List<Agent>, userPrompt: String) {
        val agentOutputs = mutableListOf<Pair<Agent, String>>()

        // 1. Each agent independently proposes (StepRunner beats).
        for (agent in agents) {
            updateTaskStatus(taskId, "Consensus Proposal: (${agent.name})")
            AgentStateStore.setAgentActive(agent.id, true, "Proposing")
            val proposalPrompt = "Propose an answers to: $userPrompt"
            val output = stepRunner.run(taskId, { updateTaskStatus(taskId, it) }, StepRequest(
                agent = agent,
                prompt = proposalPrompt,
                thinkingDelayMs = 1200,
                interStepDelayMs = 800,
                activeLabel = "Proposing",
                thinkingContent = "Drafting unique decentralized proposal from the perspective of ${agent.role}..."
            )).output
            agentOutputs.add(agent to output)
        }

        // 2. Voting phase: moderator streams a single VOTING row -> FINAL_RESPONSE row.
        // This is a freeform consensus step (no placeholder/metrics pattern), so it stays
        // as a direct llmRouter call rather than going through StepRunner.
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

        val votePrompt = """
            We have 3 proposals for the task: "$userPrompt".

            ${agentOutputs.joinToString("\n\n") { "Proposal by ${it.first.name} (${it.first.role}):\n${it.second}" }}

            Analyze these proposals, give each a score out of 10, explain why, and synthesize the best components of each into a final consensus decision.
        """.trimIndent()

        val consensusReport = llmRouter.generateFreeformStreaming(
            votePrompt,
            "You are a decentralized consensus moderator. Your job is to score agent proposals and combine them into a flawless unified outcome."
        ) { partial ->
            // Throttled streaming write so the VOTING row grows as tokens arrive
            db.taskStepDao().insertStep(
                TaskStep(
                    id = votingStepId,
                    taskId = taskId,
                    agentName = "Swarm Router",
                    agentRole = "Consensus Router",
                    actionType = "VOTING",
                    content = partial
                )
            )
        }

        db.taskStepDao().insertStep(
            TaskStep(
                id = votingStepId,
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
        val planText = llmRouter.generateForAgent(planningAgent, planPrompt, preferCloud = true) { partial ->
            db.taskStepDao().insertStep(
                TaskStep(
                    id = planStepId,
                    taskId = taskId,
                    agentName = planningAgent.name,
                    agentRole = planningAgent.role,
                    actionType = "PLAN",
                    content = partial
                )
            )
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

            // Act step: short delays, preferCloud=true, final actionType=OUTPUT.
            val actPrompt = "Context so far:\n$transcript\n\nCurrent step: ${next.text}\n" +
                "Respond with your reasoning, and if a tool is needed use the existing " +
                "'MCP_CALL: <tool> | <json args>' or git-command directive format. If you need to " +
                "create or modify a workspace file, emit a line 'WRITE_FILE: <relative/path.ext>' " +
                "-- the actual file content is generated in a separate follow-up step, so do not " +
                "inline file contents here."
            val decision = stepRunner.run(taskId, { updateTaskStatus(taskId, it) }, StepRequest(
                agent = actor,
                prompt = actPrompt,
                finalActionType = "OUTPUT",
                activeLabel = "Working: ${next.text}",
                thinkingContent = "Working on: ${next.text}",
                thinkingDelayMs = 0,
                interStepDelayMs = 0,
                preferCloud = true
            )).output
            transcript.append("\n${next.text} -> $decision")

            // Verify step: short delays, preferCloud=true, final actionType=VERIFYING
            // (matches the placeholder -- the verify row is REPLACED with the same VERIFYING
            // actionType and the verify decision content), mcp action types override defaults,
            // and we DON'T bump per-agent metrics for verify (the QA agent's tasksExecuted
            // only counts act steps, matching the pre-refactor engine).
            val qaAgent = pickQaAgent(agents, planningAgent)
            val verifyPrompt = "The following step was just attempted:\n${next.text}\n\nAgent output:\n$decision\n\n" +
                "As QA, verify this was actually done correctly. If real test/execution tooling is " +
                "available, invoke it via 'MCP_CALL: <tool> | <json args>' and report the real " +
                "result -- do not fabricate output. If no tooling is available, say so plainly."
            val verifyResult = stepRunner.run(taskId, { updateTaskStatus(taskId, it) }, StepRequest(
                agent = qaAgent,
                prompt = verifyPrompt,
                thinkingActionType = "VERIFYING",
                finalActionType = "VERIFYING",
                activeLabel = "Verifying: ${next.text}",
                thinkingContent = "Verifying: ${next.text}",
                thinkingDelayMs = 0,
                interStepDelayMs = 0,
                preferCloud = true,
                mcpSuccessActionType = "EXEC_RESULT",
                mcpFailureActionType = "EXEC_RESULT_FAILED",
                recordMetrics = false
            ))
            val verifyDecision = verifyResult.output
            val verifyOutcome = verifyResult.mcpOutcome

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
        val finalContent = llmRouter.generateForAgent(planningAgent, finalPrompt, preferCloud = true) { partial ->
            db.taskStepDao().insertStep(
                TaskStep(
                    id = finalStepId,
                    taskId = taskId,
                    agentName = planningAgent.name,
                    agentRole = planningAgent.role,
                    actionType = "FINAL_RESPONSE",
                    content = partial
                )
            )
        }
        db.taskStepDao().insertStep(
            TaskStep(
                id = finalStepId,
                taskId = taskId,
                agentName = planningAgent.name,
                agentRole = planningAgent.role,
                actionType = "FINAL_RESPONSE",
                content = finalContent
            )
        )
        AgentStateStore.setAgentActive(planningAgent.id, false, "Idle")
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

        val routerResponse = llmRouter.generateForAgent(routerAgent, routingPrompt) { partial ->
            db.taskStepDao().insertStep(
                TaskStep(
                    id = routingStepId,
                    taskId = taskId,
                    agentName = routerAgent.name,
                    agentRole = routerAgent.role,
                    actionType = "THINKING",
                    content = partial
                )
            )
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
            updateTaskStatus(taskId, "Routed: ${agent.name}")
            val promptForAgent = "Previous context:\n$context\n\nPerform your tasks on: $userPrompt"
            stepRunner.run(taskId, { updateTaskStatus(taskId, it) }, StepRequest(
                agent = agent,
                prompt = promptForAgent,
                activeLabel = "Running Routed Task",
                thinkingContent = "Executing routed task slice..."
            ))
            val agentOutput = db.taskStepDao().getStepsForTaskSync(taskId)
                .lastOrNull { it.agentName == agent.name && it.actionType == "OUTPUT" }?.content
                ?: ""
            context += "--- ${agent.name} (${agent.role}) Output ---\n$agentOutput\n\n"
        }

        updateTaskStatus(taskId, "Synthesis (${routerAgent.name})")
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
        val finalResult = stepRunner.run(taskId, { updateTaskStatus(taskId, it) }, StepRequest(
            agent = routerAgent,
            prompt = synthesisPrompt,
            finalActionType = "FINAL_RESPONSE",
            activeLabel = "Synthesizing",
            thinkingContent = "Synthesizing dynamic routing results..."
        )).output

        AgentStateStore.setAgentActive(routerAgent.id, false, "Idle")
    }

    /**
     * Single entry point for reaching an LLM outside of a specific agent's persona (consensus
     * moderator scoring, voice-command routing, chat replies, sandbox simulation, self-healing --
     * anything that previously fell back to Gemini). Always resolves against the Ollama node pool.
     */
    suspend fun generateFreeform(prompt: String, systemPrompt: String, preferCloud: Boolean = false): String =
        llmRouter.generateFreeform(prompt, systemPrompt, preferCloud)

    private suspend fun parseAndExecuteAgenticActions(
        taskId: Int,
        agentName: String,
        output: String,
        mcpSuccessActionType: String = "MCP_TOOL_CALL",
        mcpFailureActionType: String = "MCP_CALL_FAILED"
    ): ActionOutcome =
        actionExecutor.parseAndExecute(taskId, agentName, output, mcpSuccessActionType, mcpFailureActionType)

    private suspend fun autoCheckpoint(taskId: Int, agentName: String, todoText: String) {
        actionExecutor.autoCheckpoint(taskId, agentName, todoText)
    }

    private suspend fun updateTaskStatus(taskId: Int, status: String) {
        val currentTask = db.swarmTaskDao().getTaskById(taskId)
        if (currentTask != null) {
            db.swarmTaskDao().updateTask(currentTask.copy(status = status))
        }
    }
}
