package com.example.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

enum class ApprovalRiskCategory { GIT_PUSH, MCP_DESTRUCTIVE_CALL }

data class PendingApproval(
    val id: Long,
    val taskId: Int,
    val agentName: String,
    val riskCategory: ApprovalRiskCategory,
    val description: String,
    val detail: String = ""
)

data class PendingFileChange(
    val taskId: Int,
    val agentName: String,
    val filePath: String,
    val originalContent: String,
    val proposedContent: String,
    val isNewFile: Boolean
)

/**
 * Singleton bridge letting [SwarmEngine] (a plain class instantiated inside
 * SwarmViewModel, with no reference back into ViewModel-owned state) publish a
 * human-approval-gate request and suspend until SwarmViewModel/UI resolves it --
 * same rationale as [AgentStateStore]. Two independent gates (risky-action approval,
 * file-change diff review) since their payload shapes differ meaningfully.
 */
object PendingApprovalStore {
    private val idCounter = AtomicLong(0)

    private val _pendingApproval = MutableStateFlow<PendingApproval?>(null)
    val pendingApproval: StateFlow<PendingApproval?> = _pendingApproval.asStateFlow()
    private var approvalDeferred: CompletableDeferred<Boolean>? = null

    suspend fun requestApproval(
        taskId: Int,
        agentName: String,
        riskCategory: ApprovalRiskCategory,
        description: String,
        detail: String = ""
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        approvalDeferred = deferred
        _pendingApproval.value = PendingApproval(idCounter.incrementAndGet(), taskId, agentName, riskCategory, description, detail)
        val result = deferred.await()
        _pendingApproval.value = null
        approvalDeferred = null
        return result
    }

    fun approve() {
        approvalDeferred?.complete(true)
    }

    fun reject() {
        approvalDeferred?.complete(false)
    }

    private val _pendingFileChange = MutableStateFlow<PendingFileChange?>(null)
    val pendingFileChange: StateFlow<PendingFileChange?> = _pendingFileChange.asStateFlow()
    private var fileChangeDeferred: CompletableDeferred<Boolean>? = null

    suspend fun requestFileChangeReview(change: PendingFileChange): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        fileChangeDeferred = deferred
        _pendingFileChange.value = change
        val result = deferred.await()
        _pendingFileChange.value = null
        fileChangeDeferred = null
        return result
    }

    fun acceptFileChange() {
        fileChangeDeferred?.complete(true)
    }

    fun rejectFileChange() {
        fileChangeDeferred?.complete(false)
    }

    /**
     * Resets both gates. This is a process-wide singleton (unlike per-instance
     * ViewModel state), so tests must call this to avoid leaking pending state or a
     * dangling deferred into the next test in the same JVM.
     */
    fun reset() {
        approvalDeferred = null
        _pendingApproval.value = null
        fileChangeDeferred = null
        _pendingFileChange.value = null
    }
}
