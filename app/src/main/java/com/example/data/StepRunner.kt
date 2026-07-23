package com.example.data

import kotlinx.coroutines.delay

/**
 * Inputs to a single agent step. Encodes the placeholder actionType, the final actionType, the
 * agent, the prompt, and the per-step timing so the coordination-mode workflows in [SwarmEngine]
 * can stay thin policy over this single shared loop.
 *
 * Defaults are the byte-for-byte legacy values (THINKING placeholder, OUTPUT final, "Thinking"
 * active label, 1000ms inter-step delay, 1500ms thinking delay, MCP_TOOL_CALL / MCP_CALL_FAILED
 * for directive parsing). Coordination modes override the per-step labels + actionTypes they emit.
 */
data class StepRequest(
    val agent: Agent,
    val prompt: String,
    val thinkingActionType: String = "THINKING",
    val finalActionType: String = "OUTPUT",
    val activeLabel: String = "Thinking",
    val thinkingContent: String = "Analyzing prompt and building execution context...",
    val preferCloud: Boolean = false,
    val interStepDelayMs: Long = 1000,
    val thinkingDelayMs: Long = 1500,
    val mcpSuccessActionType: String = "MCP_TOOL_CALL",
    val mcpFailureActionType: String = "MCP_CALL_FAILED",
    /**
     * Whether to bump the per-agent [AgentStateStore.recordExecutionMetrics] counter for this
     * step. Defaults to `true` -- every coordination mode except the agentic-loop's QA verify
     * step records metrics, so verify steps pass `false` to preserve the legacy behavior where
     * the QA agent's `tasksExecuted` only counts act steps.
     */
    val recordMetrics: Boolean = true
)

/** Output of a single agent step: the row id, the full LLM text, the step's measured duration,
 *  the per-step token estimate that was recorded into [AgentStateStore], and the directive-parse
 *  outcome from the [AgenticActionExecutor] so workflows that need to inspect it (e.g. the
 *  agentic loop's verify-failure heuristic) can do so without re-parsing. */
data class StepResult(
    val stepId: Int,
    val output: String,
    val durationMs: Long,
    val approxTokens: Int,
    val mcpOutcome: ActionOutcome
)

/**
 * Shared per-step loop for the coordination-mode workflows. Owns the THINKING placeholder write,
 * the throttled in-place streaming reinserts (preserving the [SwarmEngineStreamingTest] monotonicity
 * contract), the directive-parsing side effects (via [AgenticActionExecutorInterface]), the final
 * actionType+content reinsert, and the per-agent metrics/idle/delay. Workflows supply only the
 * per-step [StepRequest] (status label, actionTypes, prompt, optional `preferCloud`) and the
 * agent's role-specific orchestration around these beats.
 */
class StepRunner(
    private val taskStepDao: TaskStepDao,
    private val llmRouter: LlmRouterInterface,
    private val actionExecutor: AgenticActionExecutorInterface
) {
    /**
     * Runs one agent step:
     *  1. Marks the agent active and updates the parent SwarmTask's status.
     *  2. Writes the THINKING placeholder row.
     *  3. Streams the LLM response, reinserting the same row id with the throttled
     *     growing content so a fast token stream doesn't turn into a DB write per chunk.
     *  4. Parses the final output for directives (git / MCP_CALL / WRITE_FILE) and
     *     executes any that pass approval.
     *  5. Reinserts the same row with the final actionType and the full, untruncated content.
     *  6. Records per-agent metrics, marks the agent idle, and waits the inter-step delay.
     */
    suspend fun run(taskId: Int, updateStatus: suspend (String) -> Unit, req: StepRequest): StepResult {
        val start = System.currentTimeMillis()
        AgentStateStore.setAgentActive(req.agent.id, true, req.activeLabel)
        updateStatus(req.activeLabel)

        // 1) THINKING placeholder (same row id is reused for every subsequent reinsert in this step)
        val stepId = taskStepDao.insertStep(
            TaskStep(
                taskId = taskId,
                agentName = req.agent.name,
                agentRole = req.agent.role,
                actionType = req.thinkingActionType,
                content = req.thinkingContent
            )
        ).toInt()
        delay(req.thinkingDelayMs)

        // 2) Throttled mid-stream reinsert (preserves SwarmEngineStreamingTest monotonicity)
        val throttle = StreamThrottle()
        val output = llmRouter.generateForAgent(req.agent, req.prompt, req.preferCloud) { partial ->
            if (throttle.shouldEmit(partial)) {
                taskStepDao.insertStep(
                    TaskStep(
                        id = stepId,
                        taskId = taskId,
                        agentName = req.agent.name,
                        agentRole = req.agent.role,
                        actionType = req.thinkingActionType,
                        content = partial
                    )
                )
            }
        }

        // 3) Run directives against the full output
        val mcpOutcome = actionExecutor.parseAndExecute(
            taskId, req.agent.name, output, req.mcpSuccessActionType, req.mcpFailureActionType
        )

        // 4) Final reinsert with the final actionType + full content (replaces the placeholder row)
        taskStepDao.insertStep(
            TaskStep(
                id = stepId,
                taskId = taskId,
                agentName = req.agent.name,
                agentRole = req.agent.role,
                actionType = req.finalActionType,
                content = output
            )
        )

        // 5) Metrics + idle + inter-step delay
        val durationMs = System.currentTimeMillis() - start
        if (req.recordMetrics) {
            val approxTokens = (req.prompt.length + output.length) / 2 + 100
            AgentStateStore.recordExecutionMetrics(req.agent.id, durationMs, approxTokens)
        }
        AgentStateStore.setAgentActive(req.agent.id, false, "Idle")
        delay(req.interStepDelayMs)
        val approxTokens = if (req.recordMetrics) (req.prompt.length + output.length) / 2 + 100 else 0
        return StepResult(stepId, output, durationMs, approxTokens, mcpOutcome)
    }
}
