package com.example.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide singleton recording antigenic signals emitted by the harness.
 * Mirrors the pattern used by [PendingApprovalStore] and [AgentStateStore].
 */
object AntigenicSignalStore {
    private val _signals = MutableStateFlow<List<AntigenicSignal>>(emptyList())
    val unresolvedSignals: StateFlow<List<AntigenicSignal>> = _signals.asStateFlow()

    fun recordSignal(signal: AntigenicSignal): AntigenicSignal {
        val withId = if (signal.id == 0L) signal.copy(id = nextId()) else signal
        _signals.value += withId
        return withId
    }

    fun markResolved(id: Long, reportPath: String, resolution: String = "") {
        _signals.value = _signals.value.map {
            if (it.id == id) it.copy(
                resolvedAt = System.currentTimeMillis(),
                resolverReportPath = reportPath,
                resolution = resolution,
            ) else it
        }
    }

    /** Test-only reset to prevent state leaking between tests in the same JVM. */
    fun reset() { _signals.value = emptyList() }

    private var counter = 0L
    private fun nextId(): Long = ++counter
}
