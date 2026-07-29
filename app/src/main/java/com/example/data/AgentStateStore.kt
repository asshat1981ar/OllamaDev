package com.example.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Performance metrics and status for a decentralized agent.
 */
data class AgentMetrics(
    val agentId: Int,
    val name: String,
    val role: String,
    val isActive: Boolean = false,
    val status: String = "Idle", // Idle, Thinking, Writing, Critiquing, Offline
    val tasksExecuted: Int = 0,
    val averageDurationMs: Long = 0,
    val totalTokensUsed: Int = 0,
    val lastActiveTime: Long = 0
)

/**
 * Centralized State Management Store (React Context / Redux equivalent in Android)
 * Exposes a reactive state of all registered agent metrics.
 */
object AgentStateStore {
    private val _agentStates = MutableStateFlow<Map<Int, AgentMetrics>>(emptyMap())
    
    /**
     * Observable state of all decentralized agents and their dynamic performance metrics.
     */
    val agentStates: StateFlow<Map<Int, AgentMetrics>> = _agentStates.asStateFlow()

    /**
     * Initializes or syncs the agent store with the registered list of agents.
     */
    fun initializeAgents(agents: List<Agent>) {
        _agentStates.update { current ->
            val updated = current.toMutableMap()
            agents.forEach { agent ->
                val existing = updated[agent.id]
                if (existing == null) {
                    updated[agent.id] = AgentMetrics(
                        agentId = agent.id,
                        name = agent.name,
                        role = agent.role,
                        status = "Idle"
                    )
                } else if (existing.name != agent.name || existing.role != agent.role) {
                    updated[agent.id] = existing.copy(
                        name = agent.name,
                        role = agent.role
                    )
                }
            }
            // Remove agents that are no longer in the list
            val validIds = agents.map { it.id }.toSet()
            val keysToRemove = updated.keys.filter { it !in validIds }
            keysToRemove.forEach { updated.remove(it) }
            
            updated
        }
    }

    /**
     * Updates an agent's active/inactive status.
     */
    fun setAgentActive(agentId: Int, isActive: Boolean, status: String = "Idle") {
        _agentStates.update { current ->
            val updated = current.toMutableMap()
            val metrics = updated[agentId]
            if (metrics != null) {
                updated[agentId] = metrics.copy(
                    isActive = isActive,
                    status = status,
                    lastActiveTime = if (isActive) System.currentTimeMillis() else metrics.lastActiveTime
                )
            }
            updated
        }
    }

    /**
     * Sets all agents back to idle.
     */
    fun resetAllActiveStates() {
        _agentStates.update { current ->
            current.mapValues { (_, metrics) ->
                metrics.copy(isActive = false, status = "Idle")
            }
        }
    }

    /**
     * Increments task execution count, tracks accumulated duration and estimated token usage.
     */
    fun recordExecutionMetrics(agentId: Int, durationMs: Long, tokensEstimate: Int) {
        _agentStates.update { current ->
            val updated = current.toMutableMap()
            val metrics = updated[agentId]
            if (metrics != null) {
                val newCount = metrics.tasksExecuted + 1
                val newAvg = ((metrics.averageDurationMs * metrics.tasksExecuted) + durationMs) / newCount
                updated[agentId] = metrics.copy(
                    tasksExecuted = newCount,
                    averageDurationMs = newAvg,
                    totalTokensUsed = metrics.totalTokensUsed + tokensEstimate,
                    lastActiveTime = System.currentTimeMillis()
                )
            }
            updated
        }
    }

    /**
     * Hard-reset all tracking statistics.
     */
    fun clearMetrics() {
        _agentStates.update { current ->
            current.mapValues { (_, metrics) ->
                metrics.copy(
                    tasksExecuted = 0,
                    averageDurationMs = 0,
                    totalTokensUsed = 0,
                    isActive = false,
                    status = "Idle"
                )
            }
        }
    }
}
