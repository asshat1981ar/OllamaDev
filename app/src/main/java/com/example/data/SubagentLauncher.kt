package com.example.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Abstraction over the agentic-harness subagent launcher. Production implementations
 * call the harness's real `delegate` mechanism; tests can install a no-op recorder.
 */
interface SubagentLauncher {
    /**
     * Launches a fix subagent for the given [briefPath] and [context].
     *
     * The launcher is fire-and-forget from the orchestrator's perspective; the subagent
     * is expected to write its report and, if successful, the orchestrator will later
     * resolve the signal. For now this is best-effort: failures are logged as signals.
     */
    fun launchFixSubagent(
        signalId: Long,
        briefPath: String,
        context: Map<String, String>,
    )
}

/**
 * Production stub: records that a subagent *would* be launched. In a harness that has
 * access to the runtime `delegate` tool this would build the subagent prompt and call it.
 */
object LoggingSubagentLauncher : SubagentLauncher {
    override fun launchFixSubagent(
        signalId: Long,
        briefPath: String,
        context: Map<String, String>,
    ) {
        android.util.Log.i(
            "AntigenicOrchestrator",
            "Would dispatch subagent for signal $signalId: brief=$briefPath, context=$context"
        )
    }
}
