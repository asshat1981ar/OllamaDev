package com.example.data

import com.example.ui.FakeAppDatabase
import com.example.ui.FakeSecurePrefs
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SprintOrchestrator]. All DB I/O goes through [FakeAppDatabase] (no real
 * Room/SQLite — Robolectric has no SQLite support on linux-aarch64).
 *
 * The orchestrator's [SprintOrchestrator.launchPhaseTask] only inserts a [SwarmTask] row with
 * status = "Pending" and returns the ID; [SprintOrchestrator.collectPhaseArtifact] then polls
 * [SwarmTaskDao.getTaskById] until status is "Completed" or "Failed". To make the cycle finish
 * without a real [SwarmEngine], we use [AutoCompleteTaskDatabase] — a thin wrapper over
 * [FakeAppDatabase] whose [SwarmTaskDao] overrides [SwarmTaskDao.insertTask] to immediately
 * flip the inserted task to "Completed" and pre-populate the expected artifact [WorkspaceFile].
 *
 * The [LlmRouterInterface] fake ([CapturingLlmRouter]) records every [routePrompt] call so
 * tests can assert on prior-context injection without a real LLM.
 */
class SprintOrchestratorTest {

    // -------------------------------------------------------------------------
    // Fakes
    // -------------------------------------------------------------------------

    /**
     * A [LlmRouterInterface] that records every [routePrompt] call and returns a
     * deterministic, phase-keyed distillation string so tests can verify context injection.
     */
    private class CapturingLlmRouter : LlmRouterInterface {
        data class RouteCall(val prompt: String, val systemPrompt: String, val preferCloud: Boolean)

        val routeCalls = mutableListOf<RouteCall>()
        // Prompts passed to any engine-like caller that captures taskPrompts
        val taskPrompts = mutableListOf<String>()

        override suspend fun routePrompt(
            prompt: String,
            systemPrompt: String,
            preferCloud: Boolean
        ): String {
            routeCalls += RouteCall(prompt, systemPrompt, preferCloud)
            // Embed the phase name from the prompt body so callers can assert on it
            val phaseName = SprintPhase.values()
                .firstOrNull { ph -> prompt.contains(ph.name, ignoreCase = true) }
                ?.name ?: "UNKNOWN"
            return "DISTIL_$phaseName"
        }

        override suspend fun generateForAgent(
            agent: Agent,
            prompt: String,
            preferCloud: Boolean,
            onToken: (suspend (String) -> Unit)?
        ): String = "[FakeGen] ${prompt.take(40)}"

        override suspend fun generateFreeform(
            prompt: String,
            systemPrompt: String,
            preferCloud: Boolean
        ): String = "[FakeGen] ${prompt.take(40)}"

        override suspend fun generateFreeformStreaming(
            prompt: String,
            systemPrompt: String,
            onToken: suspend (String) -> Unit
        ): String {
            onToken("[FakeStream]")
            return "[FakeStream]"
        }
    }

    /**
     * A [FakeAppDatabase] whose task DAO auto-completes any inserted task, and also pre-writes
     * a [WorkspaceFile] at the expected artifact path so [collectPhaseArtifact] finds non-empty
     * content to distil.
     *
     * @param scriptedArtifactContent  If set for a phase name (e.g. "VERIFICATION"), the
     *   pre-written WorkspaceFile uses that scripted content instead of the default placeholder.
     *   This lets tests simulate unresolved-item counts.
     * @param phaseCallCounts  Mutable map incremented each time a task is inserted for a phase.
     *   Key is the phase name extracted from the task's [SwarmTask.swarmName].
     * @param capturedPrompts  All [SwarmTask.prompt] strings from [insertTask] are appended here.
     */
    private class AutoCompleteTaskDatabase(
        val scriptedArtifactContent: Map<String, String> = emptyMap(),
        val phaseCallCounts: MutableMap<String, Int> = mutableMapOf(),
        val capturedPrompts: MutableList<String> = mutableListOf()
    ) : FakeAppDatabase() {

        override fun swarmTaskDao(): SwarmTaskDao = AutoCompleteSwarmTaskDao(
            delegate = super.swarmTaskDao(),
            db = this,
            scriptedArtifactContent = scriptedArtifactContent,
            phaseCallCounts = phaseCallCounts,
            capturedPrompts = capturedPrompts
        )
    }

    /**
     * SwarmTaskDao wrapper that, on [insertTask], immediately marks the row "Completed" and
     * writes a [WorkspaceFile] for the expected artifact path.
     */
    private class AutoCompleteSwarmTaskDao(
        private val delegate: SwarmTaskDao,
        private val db: FakeAppDatabase,
        private val scriptedArtifactContent: Map<String, String>,
        private val phaseCallCounts: MutableMap<String, Int>,
        private val capturedPrompts: MutableList<String>
    ) : SwarmTaskDao by delegate {

        override suspend fun insertTask(task: SwarmTask): Long {
            // Extract phase name from swarmName ("Sprint N / PHASENAME")
            val phaseName = task.swarmName.substringAfterLast("/").trim()
            phaseCallCounts[phaseName] = (phaseCallCounts[phaseName] ?: 0) + 1
            capturedPrompts += task.prompt

            // Insert as Completed immediately so collectPhaseArtifact doesn't poll forever
            val id = delegate.insertTask(task.copy(status = "Completed")).toInt()

            // Pre-write the artifact WorkspaceFile so distilArtifact finds content
            val phase = SprintPhase.values().firstOrNull { it.name == phaseName }
            if (phase != null) {
                // cycleId is always 1 in tests (first inserted SprintCycle)
                val artifactPath = phase.artifactPath(1)
                val content = scriptedArtifactContent[phaseName]
                    ?: "# ${phase.name} Artifact\n\nCompleted successfully."
                db.workspaceFileDao().insertFile(
                    WorkspaceFile(filePath = artifactPath, content = content)
                )
            }

            return id.toLong()
        }
    }

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    @Before
    fun setUp() {
        // PendingApprovalStore is a process-wide singleton; reset between tests to avoid leaking
        // any dangling deferred from a previous test in the same JVM run.
        PendingApprovalStore.reset()
    }

    private fun buildOrchestrator(
        db: FakeAppDatabase,
        llmRouter: LlmRouterInterface = CapturingLlmRouter()
    ): SprintOrchestrator {
        // SprintOrchestrator takes a real SwarmEngine, but launchPhaseTask only calls
        // db.swarmTaskDao().insertTask() — it does NOT invoke engine.executeTask(). So we can
        // pass a minimally constructed SwarmEngine that is never actually called.
        val fakeOllama = com.example.ui.FakeOllamaService()
        val fakePrefs = FakeSecurePrefs()
        val fakeGitDir = createTempDir("sprint_test_git")
        val gitService = GitService(fakeGitDir)
        val mcpClient = com.example.ui.FakeMcpClient()
        // SwarmEngine requires a Context for SharedPreferences; use a minimal fake application.
        val appContext = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        val engine = SwarmEngine(
            db = db,
            gitService = gitService,
            mcpClient = mcpClient,
            appContext = appContext,
            securePrefs = fakePrefs,
            ollamaService = fakeOllama,
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined
        )
        return SprintOrchestrator(
            db = db,
            llmRouter = llmRouter,
            engine = engine,
            pendingApprovalStore = PendingApprovalStore
        )
    }

    // -------------------------------------------------------------------------
    // Test 1 — happy-path: 6 phases complete in order, 6 SprintArtifact rows written
    // -------------------------------------------------------------------------

    @Test
    fun fullCycle_sixPhasesComplete_sixArtifactsWritten() =
        runTest(UnconfinedTestDispatcher()) {
            val db = AutoCompleteTaskDatabase()
            val orchestrator = buildOrchestrator(db)

            orchestrator.runCycle(this, goal = "add feature X")
            // runCycle launches a coroutine in the provided scope; UnconfinedTestDispatcher
            // runs it synchronously, so the cycle is complete by the time runCycle returns.

            val cycleId = db.sprintCycleDao()
                .getAllCycles().let {
                    // getAllCycles() is a Flow; snapshot it via the first emitted value.
                    var result: List<SprintCycle> = emptyList()
                    val job = kotlinx.coroutines.launch {
                        it.collect { list -> result = list }
                    }
                    job.cancel()
                    result
                }
                .firstOrNull()?.id ?: run {
                    // Fallback: the orchestrator always inserts cycle id=1 in a fresh DB.
                    1
                }

            val artifacts = db.sprintArtifactDao().getArtifactsForCycleSync(cycleId)

            // 6 phases → 6 artifacts
            assertEquals(
                "Expected 6 SprintArtifact rows (one per phase)",
                6,
                artifacts.size
            )

            // Phases must appear in canonical order
            val expectedPhases = SprintPhase.values().map { it.name }
            val actualPhases = artifacts.map { it.phase }
            assertEquals(
                "Artifact phases must follow SprintPhase declaration order",
                expectedPhases,
                actualPhases
            )

            // Cycle must be COMPLETED
            val cycle = db.sprintCycleDao().getCycleById(cycleId)
            assertEquals("COMPLETED", cycle?.status)

            // No re-queue on the happy path
            assertEquals(
                "reimplCount must be 0 on a clean cycle",
                0,
                cycle?.reimplCount ?: -1
            )

            // Each artifact path follows the naming convention sprint-<cycleId>-<phase>.md
            artifacts.forEach { artifact ->
                val phase = SprintPhase.valueOf(artifact.phase)
                assertEquals(phase.artifactPath(cycleId), artifact.artifactPath)
            }
        }

    // -------------------------------------------------------------------------
    // Test 2 — VERIFICATION re-queue: 3 [UNRESOLVED] items → exactly one re-impl
    // -------------------------------------------------------------------------

    @Test
    fun verificationFailure_triggersSingleImplementationRequeue() =
        runTest(UnconfinedTestDispatcher()) {
            val phaseCounts: MutableMap<String, Int> = mutableMapOf()
            val scriptedContent = mapOf(
                "VERIFICATION" to
                    "[UNRESOLVED: REQ-1 not met]\n[UNRESOLVED: REQ-2 not met]\n[UNRESOLVED: REQ-3 not met]"
            )
            val db = AutoCompleteTaskDatabase(
                scriptedArtifactContent = scriptedContent,
                phaseCallCounts = phaseCounts
            )
            val orchestrator = buildOrchestrator(db)

            orchestrator.runCycle(this, goal = "add feature with unresolved items")

            val cycleId = 1 // first cycle inserted into a fresh DB
            val cycle = db.sprintCycleDao().getCycleById(cycleId)

            // IMPLEMENTATION must have run exactly twice (once originally + one re-queue)
            assertEquals(
                "IMPLEMENTATION should be called twice when VERIFICATION has >2 unresolved items",
                2,
                phaseCounts["IMPLEMENTATION"] ?: 0
            )

            // reimplCount on the DB row must be exactly 1
            assertEquals(
                "reimplCount must be 1 after a single re-queue",
                1,
                cycle?.reimplCount ?: -1
            )

            // Cycle must still complete (not stuck)
            assertEquals("COMPLETED", cycle?.status)

            // We should have more than 6 artifacts (IMPLEMENTATION runs twice)
            val artifacts = db.sprintArtifactDao().getArtifactsForCycleSync(cycleId)
            assertTrue(
                "Expected >6 artifacts when IMPLEMENTATION re-ran (got ${artifacts.size})",
                artifacts.size > 6
            )
        }

    // -------------------------------------------------------------------------
    // Test 3 — prior context: each phase prompt includes distilled summaries of all prior phases
    // -------------------------------------------------------------------------

    @Test
    fun phasePrompts_containPriorDistilledContext() =
        runTest(UnconfinedTestDispatcher()) {
            val capturedPrompts: MutableList<String> = mutableListOf()
            val db = AutoCompleteTaskDatabase(capturedPrompts = capturedPrompts)

            // Use a CapturingLlmRouter that returns phase-keyed distillation strings
            val llmRouter = CapturingLlmRouter()
            val orchestrator = buildOrchestrator(db, llmRouter)

            orchestrator.runCycle(this, goal = "add context-verified feature")

            // capturedPrompts[0] = DISCOVERY prompt (no prior context)
            // capturedPrompts[1] = DESIGN prompt (should include DISCOVERY distillation)
            // capturedPrompts[2] = IMPLEMENTATION prompt (should include DISCOVERY + DESIGN)
            // etc.

            assertTrue(
                "Must have captured at least 3 phase prompts (DISCOVERY, DESIGN, IMPLEMENTATION)",
                capturedPrompts.size >= 3
            )

            val discoveryPrompt = capturedPrompts[0]
            val designPrompt = capturedPrompts[1]
            val implPrompt = capturedPrompts[2]

            // DISCOVERY is the first phase — no prior context block
            assertTrue(
                "DISCOVERY prompt should NOT contain prior context header",
                !discoveryPrompt.contains("=== Prior Sprint Context ===")
            )

            // DESIGN prompt must include DISCOVERY distilled summary
            assertTrue(
                "DESIGN prompt must contain '=== Prior Sprint Context ===' header",
                designPrompt.contains("=== Prior Sprint Context ===")
            )
            assertTrue(
                "DESIGN prompt must contain [DISCOVERY] distillation label",
                designPrompt.contains("[DISCOVERY]")
            )

            // IMPLEMENTATION prompt must include both DISCOVERY and DESIGN distillations
            assertTrue(
                "IMPLEMENTATION prompt must contain '=== Prior Sprint Context ===' header",
                implPrompt.contains("=== Prior Sprint Context ===")
            )
            assertTrue(
                "IMPLEMENTATION prompt must reference [DISCOVERY]",
                implPrompt.contains("[DISCOVERY]")
            )
            assertTrue(
                "IMPLEMENTATION prompt must reference [DESIGN]",
                implPrompt.contains("[DESIGN]")
            )

            // Verify the actual distilled strings from CapturingLlmRouter are present
            // CapturingLlmRouter returns "DISTIL_DISCOVERY" for the discovery distillation
            assertTrue(
                "DESIGN prompt should embed the DISCOVERY distillation value",
                designPrompt.contains("DISTIL_DISCOVERY")
            )
            assertTrue(
                "IMPLEMENTATION prompt should embed the DESIGN distillation value",
                implPrompt.contains("DISTIL_DESIGN")
            )
        }
}
