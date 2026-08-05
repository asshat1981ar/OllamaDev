package com.example.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SprintPhase(
    val coordinationMode: String,
    val leadRoles: List<String>,
    val defaultIterations: Int,
    val distilFocus: String
) {
    DISCOVERY(
        coordinationMode = "AGENTIC_LOOP",
        leadRoles = listOf("Architect"),
        defaultIterations = 8,
        distilFocus = "architecture gaps, requirements list, file inventory"
    ),
    DESIGN(
        coordinationMode = "PEER_TO_PEER",
        leadRoles = listOf("Architect", "Programmer"),
        defaultIterations = 10,
        distilFocus = "component interfaces, data model changes, cross-cutting concerns"
    ),
    IMPLEMENTATION(
        coordinationMode = "AGENTIC_LOOP",
        leadRoles = listOf("Programmer"),
        defaultIterations = 16,
        distilFocus = "files written, interfaces implemented, git checkpoint SHA"
    ),
    VERIFICATION(
        coordinationMode = "AGENTIC_LOOP",
        leadRoles = listOf("QA"),
        defaultIterations = 12,
        distilFocus = "PASS/FAIL/UNRESOLVED per requirement, test coverage gaps"
    ),
    INTEGRATION(
        coordinationMode = "CONSENSUS_VOTE",
        leadRoles = listOf("Architect", "Programmer", "QA"),
        defaultIterations = 6,
        distilFocus = "integration verdict, known regressions, schema conflicts"
    ),
    RETROSPECTIVE(
        coordinationMode = "SEQUENTIAL",
        leadRoles = listOf("Architect"),
        defaultIterations = 6,
        distilFocus = "what was built, unresolved items, next-cycle recommendations"
    );

    fun artifactPath(cycleId: Int): String =
        "sprint-$cycleId-${name.lowercase()}.md"
}

data class SprintProgress(
    val phase: SprintPhase = SprintPhase.DISCOVERY,
    val phaseLabel: String = "Waiting",
    val completedPhases: Int = 0,
    val totalPhases: Int = SprintPhase.values().size,
    val currentTaskId: Int? = null,
    val lastArtifactSummary: String = ""
)

interface SprintOrchestratorInterface {
    val activeCycle: StateFlow<SprintCycle?>
    val cycleProgress: StateFlow<SprintProgress>
    val sprintArtifacts: StateFlow<List<SprintArtifact>>
    fun runCycle(scope: CoroutineScope, goal: String, seedContext: String = "")
    fun pauseCycle()
    fun resumeCycle()
    fun cancelCycle()
}

class SprintOrchestrator(
    private val db: AppDatabaseInterface,
    private val llmRouter: LlmRouterInterface,
    private val engine: SwarmEngine,
    private val pendingApprovalStore: PendingApprovalStore
) : SprintOrchestratorInterface {

    private val _activeCycle = MutableStateFlow<SprintCycle?>(null)
    override val activeCycle: StateFlow<SprintCycle?> = _activeCycle.asStateFlow()

    private val _cycleProgress = MutableStateFlow(SprintProgress())
    override val cycleProgress: StateFlow<SprintProgress> = _cycleProgress.asStateFlow()

    private val _sprintArtifacts = MutableStateFlow<List<SprintArtifact>>(emptyList())
    override val sprintArtifacts: StateFlow<List<SprintArtifact>> = _sprintArtifacts.asStateFlow()

    private var cycleJob: Job? = null
    private var paused = false

    companion object {
        private const val UNRESOLVED_REQUEUE_THRESHOLD = 2
        private const val MAX_REIMPL_COUNT = 2
        private const val DISTIL_MAX_TOKENS = 300
    }

    override fun runCycle(scope: CoroutineScope, goal: String, seedContext: String) {
        cancelCycle()
        cycleJob = scope.launch {
            val cycleRow = SprintCycle(goal = goal, seedContext = seedContext)
            val cycleId = db.sprintCycleDao().insertCycle(cycleRow).toInt()
            val cycle = cycleRow.copy(id = cycleId)
            _activeCycle.value = cycle
            _sprintArtifacts.value = emptyList()

            val phases = SprintPhase.values().toList()
            var phaseIndex = 0

            while (phaseIndex < phases.size) {
                if (paused) {
                    persistStatus(cycleId, phases[phaseIndex], "PAUSED")
                    // caller polls activeCycle.status to detect pause; resumeCycle() clears the flag
                    while (paused) kotlinx.coroutines.delay(500)
                }

                val phase = phases[phaseIndex]
                persistStatus(cycleId, phase, "RUNNING")
                _cycleProgress.value = SprintProgress(
                    phase = phase,
                    phaseLabel = "Running ${phase.name}",
                    completedPhases = phaseIndex,
                    totalPhases = phases.size,
                    currentTaskId = null,
                    lastArtifactSummary = _sprintArtifacts.value.lastOrNull()?.distilledSummary ?: ""
                )

                val priorContext = buildPriorContext(_sprintArtifacts.value)
                val taskPrompt = buildPhasePrompt(phase, cycleId, goal, priorContext)
                val swarmConfig = buildSwarmConfig(phase, cycleId, goal)

                val taskId = launchPhaseTask(swarmConfig, taskPrompt)

                val artifact = collectPhaseArtifact(phase, cycleId, taskId, priorContext)
                val artifacts = _sprintArtifacts.value + artifact
                _sprintArtifacts.value = artifacts

                val currentUnresolved = artifact.unresolvedItems.lines().count { it.isNotBlank() }

                // VERIFICATION failure: re-queue IMPLEMENTATION (bounded by MAX_REIMPL_COUNT)
                if (phase == SprintPhase.VERIFICATION && currentUnresolved > UNRESOLVED_REQUEUE_THRESHOLD) {
                    val currentCycle = db.sprintCycleDao().getCycleById(cycleId) ?: break
                    if (currentCycle.reimplCount < MAX_REIMPL_COUNT) {
                        AntigenicSignalStore.recordSignal(
                            AntigenicSignal(
                                cycleId = cycleId,
                                severity = AntigenicSeverity.WARNING,
                                category = AntigenicCategory.QUALITY,
                                source = "SprintOrchestrator",
                                signalType = "VERIFICATION_UNRESOLVED",
                                message = "Verification phase triggered IMPLEMENTATION re-run",
                                detail = "unresolved=$currentUnresolved reimplCount=${currentCycle.reimplCount}",
                            )
                        )
                        db.sprintCycleDao().updateCycle(
                            currentCycle.copy(reimplCount = currentCycle.reimplCount + 1)
                        )
                        _activeCycle.value = db.sprintCycleDao().getCycleById(cycleId)
                        phaseIndex = SprintPhase.values().indexOf(SprintPhase.IMPLEMENTATION)
                        continue
                    }
                }

                // tally cumulative unresolved onto the cycle row
                val latestCycle = db.sprintCycleDao().getCycleById(cycleId) ?: break
                db.sprintCycleDao().updateCycle(
                    latestCycle.copy(
                        currentPhase = phase.name,
                        unresolvedCount = latestCycle.unresolvedCount + currentUnresolved
                    )
                )
                _activeCycle.value = db.sprintCycleDao().getCycleById(cycleId)

                _cycleProgress.value = _cycleProgress.value.copy(
                    completedPhases = phaseIndex + 1,
                    lastArtifactSummary = artifact.distilledSummary
                )

                phaseIndex++
            }

            val finishedCycle = db.sprintCycleDao().getCycleById(cycleId)
            if (finishedCycle != null) {
                db.sprintCycleDao().updateCycle(
                    finishedCycle.copy(
                        status = "COMPLETED",
                        completedAt = System.currentTimeMillis()
                    )
                )
                _activeCycle.value = db.sprintCycleDao().getCycleById(cycleId)
            }
        }
    }

    override fun pauseCycle() {
        paused = true
    }

    override fun resumeCycle() {
        paused = false
    }

    override fun cancelCycle() {
        cycleJob?.cancel()
        cycleJob = null
        paused = false
        _activeCycle.value?.let { cycle ->
            cycleJob = null
            // mark cancelled synchronously; caller's scope is already gone
        }
    }

    // Builds the "=== Prior Sprint Context ===" block injected into every phase prompt.
    private fun buildPriorContext(artifacts: List<SprintArtifact>): String {
        if (artifacts.isEmpty()) return ""
        val lines = artifacts.joinToString("\n") { a ->
            "[${a.phase}] ${a.distilledSummary.trim()}"
        }
        return "=== Prior Sprint Context ===\n$lines\n==========================="
    }

    // Calls LlmRouter with a distillation prompt to produce a ≤300-token summary.
    private suspend fun distilArtifact(
        rawContent: String,
        phase: SprintPhase
    ): String {
        val prompt = buildString {
            appendLine("Summarize the following sprint artifact in ≤$DISTIL_MAX_TOKENS tokens.")
            appendLine("Focus only on: ${phase.distilFocus}")
            appendLine("Output only the summary, no preamble or trailing commentary.")
            appendLine()
            appendLine(rawContent.take(8000)) // guard against absurdly large content
        }
        return llmRouter.routePrompt(
            prompt = prompt,
            systemPrompt = "You are a concise context distiller for an autonomous SDLC pipeline.",
            preferCloud = false
        ).trim()
    }

    private fun buildPhasePrompt(
        phase: SprintPhase,
        cycleId: Int,
        goal: String,
        priorContext: String
    ): String = buildString {
        if (priorContext.isNotBlank()) {
            appendLine(priorContext)
            appendLine()
        }
        appendLine("Sprint goal: $goal")
        appendLine()
        appendLine("Your task for the ${phase.name} phase:")
        appendLine(phaseInstructions(phase))
        appendLine()
        appendLine("Deliverables:")
        appendLine("- WRITE_FILE: ${phase.artifactPath(cycleId)}")
        phaseExtraDeliverables(phase).forEach { appendLine("- $it") }
        appendLine()
        appendLine("Constraints:")
        appendLine("- Every claim must reference a prior sprint artifact or an existing workspace file.")
        appendLine("- Do not re-derive context already summarised above.")
        appendLine("- Mark any item you cannot resolve as [UNRESOLVED: reason].")
    }

    private fun phaseInstructions(phase: SprintPhase): String = when (phase) {
        SprintPhase.DISCOVERY ->
            "Enumerate existing workspace files relevant to the goal. Identify gaps between " +
            "current state and goal requirements. Produce a prioritised requirement list (REQ-1, REQ-2, …)."
        SprintPhase.DESIGN ->
            "Assign each REQ-N to a component: new file, existing file, or schema change. " +
            "Produce interface signatures and data-model changes. Flag cross-cutting concerns."
        SprintPhase.IMPLEMENTATION ->
            "Emit WRITE_FILE: directives for each component identified in the DESIGN phase. " +
            "Every file must satisfy at least one REQ-N. Verify your own output before emitting."
        SprintPhase.VERIFICATION ->
            "For each REQ-N produce a PASS / FAIL / UNRESOLVED line. Invoke MCP_CALL: to run " +
            "tests where a connected test-runner is available. Re-read written files to confirm they " +
            "satisfy the requirement before marking PASS."
        SprintPhase.INTEGRATION ->
            "Check that all written files integrate without duplicate symbols, schema conflicts, " +
            "or broken imports. Produce a consensus verdict (SAFE / UNSAFE) with justification."
        SprintPhase.RETROSPECTIVE ->
            "Summarise what was built and what remains unresolved. Identify process improvements " +
            "for the next cycle. Update agent-os/backlog.md with new items discovered this cycle."
    }

    private fun phaseExtraDeliverables(phase: SprintPhase): List<String> = when (phase) {
        SprintPhase.IMPLEMENTATION -> listOf(
            "git commit checkpoint after all WRITE_FILE approvals"
        )
        SprintPhase.RETROSPECTIVE -> listOf(
            "WRITE_FILE: agent-os/backlog.md (updated with new items)"
        )
        else -> emptyList()
    }

    private fun buildSwarmConfig(phase: SprintPhase, cycleId: Int, goal: String): SwarmConfig =
        SwarmConfig(
            name = "Sprint $cycleId / ${phase.name}",
            description = "[Sprint $cycleId / ${phase.name}] $goal",
            coordinationMode = phase.coordinationMode,
            agentIds = "" // resolved at launch time by matching phase.leadRoles against DB agents
        )

    // Launches the SwarmTask for this phase; returns the new task ID.
    // In production this delegates to SwarmViewModel.runSwarm(); here we create the task row directly.
    private suspend fun launchPhaseTask(config: SwarmConfig, prompt: String): Int {
        val task = SwarmTask(
            prompt = prompt,
            status = "Pending",
            swarmName = config.name
        )
        return db.swarmTaskDao().insertTask(task).toInt()
        // SwarmViewModel observes swarm_tasks and kicks off the engine for Pending tasks.
        // The engine writes TaskStep rows and updates SwarmTask.status to Completed/Failed.
    }

    // Waits for the SwarmTask to complete then reads the artifact file and distils it.
    private suspend fun collectPhaseArtifact(
        phase: SprintPhase,
        cycleId: Int,
        taskId: Int,
        @Suppress("UNUSED_PARAMETER") priorContext: String
    ): SprintArtifact {
        // Poll for task completion (SwarmEngine sets status = "Completed" or "Failed").
        var task = db.swarmTaskDao().getTaskById(taskId)
        while (task?.status !in listOf("Completed", "Failed")) {
            kotlinx.coroutines.delay(1000)
            task = db.swarmTaskDao().getTaskById(taskId)
        }

        val artifactPath = phase.artifactPath(cycleId)
        val rawContent = db.workspaceFileDao().getFileByPath(artifactPath)?.content ?: task?.result ?: ""

        val unresolved = rawContent.lines()
            .filter { it.trimStart().startsWith("[UNRESOLVED") }
            .joinToString("\n")

        val summary = if (rawContent.isNotBlank()) distilArtifact(rawContent, phase) else ""

        val commitHash = db.gitCommitDao()
            .getAllCommitsSync()
            .firstOrNull { it.taskId == taskId }
            ?.commitHash

        val artifact = SprintArtifact(
            cycleId = cycleId,
            phase = phase.name,
            taskId = taskId,
            artifactPath = artifactPath,
            distilledSummary = summary,
            unresolvedItems = unresolved,
            gitCommitHash = commitHash
        )
        db.sprintArtifactDao().insertArtifact(artifact)
        return artifact
    }

    private suspend fun persistStatus(cycleId: Int, phase: SprintPhase, status: String) {
        val cycle = db.sprintCycleDao().getCycleById(cycleId) ?: return
        db.sprintCycleDao().updateCycle(cycle.copy(status = status, currentPhase = phase.name))
        _activeCycle.value = db.sprintCycleDao().getCycleById(cycleId)
    }
}
