package com.example.data

/** Outcome of scanning one agent output for directives -- used by the verify phase to decide
 *  whether a real tool call was attempted and whether it looked like it failed. */
data class ActionOutcome(
    val mcpCallAttempted: Boolean,
    val mcpCallSucceeded: Boolean,
    val mcpResultText: String?
)

/** Seam for parsing agent output and performing the resulting side effects. */
interface AgenticActionExecutorInterface {
    /** Parse [output] for agentic directives (git commands, MCP_CALL, WRITE_FILE) and execute those
     *  that pass the approval gate. Returns an [ActionOutcome] describing any MCP attempt. */
    suspend fun parseAndExecute(
        taskId: Int,
        agentName: String,
        output: String,
        mcpSuccessActionType: String = "MCP_TOOL_CALL",
        mcpFailureActionType: String = "MCP_CALL_FAILED"
    ): ActionOutcome

    /** Engine-driven checkpoint after a todo's verify phase passes cleanly -- deliberately not
     *  left to the LLM's discretion to remember to emit a `git commit` line, since that's not a
     *  reliable checkpoint story. */
    suspend fun autoCheckpoint(taskId: Int, agentName: String, todoText: String)
}
