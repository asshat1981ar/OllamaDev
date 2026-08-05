package com.example.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Observes [AntigenicSignalStore] and dispatches an appropriate response for each signal.
 * The heavy lifting (subagent dispatch, notifications) is delegated to platform wrappers; this
 * object decides *which* strategy applies and records resolutions.
 *
 * A simple in-flight set prevents the same unresolved signal from being dispatched repeatedly
 * every time the store emits a new value.
 */
object AntigenicOrchestrator {
    private val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlight = mutableSetOf<Long>()

    /** Injectable for tests. Defaults to a logging stub. */
    var launcher: SubagentLauncher = LoggingSubagentLauncher

    /**
     * Starts observing [AntigenicSignalStore]. The collector runs on [scope] so tests can pass
     * their own test-controlled scope (`TestScope.backgroundScope`) instead of the production
     * [Dispatchers.Default] scope, which the test scheduler cannot drive.
     */
    fun startObserving(scope: CoroutineScope = defaultScope) {
        AntigenicSignalStore.unresolvedSignals
            .onEach { signals ->
                signals.filter { it.resolvedAt == null && inFlight.add(it.id) }
                    .forEach { dispatch(it) }
            }
            .launchIn(scope)
    }

    /** Removes [signalId] from the in-flight set so it can be re-dispatched if it re-occurs. */
    fun clearInFlight(signalId: Long) {
        inFlight.remove(signalId)
    }

    /** Resets internal state for tests. */
    fun reset() {
        inFlight.clear()
    }

    private fun dispatch(signal: AntigenicSignal) {
        val dispatch = AntigenicDispatcher.strategyFor(signal)
        when (dispatch.strategy) {
            AntigenicStrategy.SURFACE_TO_USER -> {
                // TODO: emit notification/Toast via a notification helper (Task 6).
            }
            AntigenicStrategy.PAUSE_CYCLE -> {
                // TODO: pause SprintOrchestrator when integrated.
            }
            AntigenicStrategy.AUTO_FALLBACK -> {
                // TODO: flip preferCloud flag for the task (Task 5 budget cycle).
            }
            AntigenicStrategy.RECORD_AND_CONTINUE -> {
                // Signal already recorded; no-op.
            }
            AntigenicStrategy.DELEGATE_FIX_SUBAGENT -> {
                launcher.launchFixSubagent(
                    signalId = signal.id,
                    briefPath = dispatch.briefPath,
                    context = dispatch.context,
                )
            }
        }
    }
}
