package com.example.data

import com.example.ui.FakeAppDatabase
import com.example.ui.FakeSecurePrefs
import com.example.ui.FakeOllamaService
import com.example.ui.FakeMcpClient
import java.nio.file.Files
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Unit tests for [SprintOrchestrator]. All DB I/O goes through a delegating
 * [AppDatabaseInterface] wrapper around [FakeAppDatabase] (no real Room/SQLite —
 * Robolectric has no SQLite support on linux-aarch64).
 *
 * The orchestrator's [SprintOrchestrator.launchPhaseTask] only inserts a [SwarmTask] row
 * with status = "Pending" then returns the ID; [SprintOrchestrator.collectPhaseArtifact]
 * polls [SwarmTaskDao.getTaskById] until status is "Completed" or "Failed". To avoid
 * infinite polling we use [AutoCompletingDb] — an [AppDatabaseInterface] that wraps
 * [FakeAppDatabase] and replaces only [swarmTaskDao] with [AutoCompleteSwarmTaskDao]:
 * on every [insertTask] it immediately marks the row "Completed" and pre-writes the
 * expected artifact [WorkspaceFile] so [collectPhaseArtifact] finds real content.
 *
 * [CapturingLlmRouter] records every [routePrompt] call so tests can assert on
 * prior-context injection without a real LLM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SprintOrchestratorTest {

    // -------------------------------------------------------------------------
    // Fakes
    // -------------------------------------------------------------------------

    /** LlmRouter that records every routePrompt call and returns a phase-keyed distillation. */
    private class CapturingLlmRouter : LlmRouterInterface {
        data class RouteCall(val prompt: String, val systemPrompt: String, val preferCloud: Boolean)
        val routeCalls = mutableListOf<RouteCall>()

        override suspend fun routePrompt(prompt: String, systemPrompt: String, preferCloud: Boolean): String {
            routeCalls += RouteCall(prompt, systemPrompt, preferCloud)
            // Encode the phase name in the returned distillation so tests can assert on it
            val phaseName = SprintPhase.values()
                .firstOrNull { ph -> prompt.contains(ph.name, ignoreCase = true) }
                ?.name ?: "UNKNOWN"
            return "DISTIL_$phaseName"
        }

        override suspend fun generateForAgent(
            agent: Agent, prompt: String, preferCloud: Boolean,
            onToken: (suspend (String) -> Unit)?
        ): String = "[FakeGen] ${prompt.take(40)}"

        override suspend fun generateFreeform(
            prompt: String, systemPrompt: String, preferCloud: Boolean
        ): String = "[FakeGen] ${prompt.take(40)}"

        override suspend fun generateFreeformStreaming(
            prompt: String, systemPrompt: String, onToken: suspend (String) -> Unit
        ): String { onToken("[FakeStream]"); return "[FakeStream]" }
    }

    /**
     * SwarmTaskDao that delegates to a [FakeAppDatabase]'s task DAO but intercepts
     * [insertTask]: marks the row "Completed" immediately and pre-writes the artifact
     * [WorkspaceFile] so [collectPhaseArtifact] doesn't poll forever.
     *
     * @param delegate  the task DAO from the underlying [FakeAppDatabase]
     * @param fileDao   the workspace-file DAO to write the artifact into
     * @param scriptedArtifactContent  phase-name → file content overrides; the content
     *   is used only on the *first* call for that phase — subsequent calls use the
     *   default placeholder so a re-queued IMPLEMENTATION/VERIFICATION doesn't loop forever
     * @param phaseCallCounts  mutated in-place to track how often each phase was launched
     * @param capturedPrompts  every [SwarmTask.prompt] value appended here for assertions
     */
    private class AutoCompleteSwarmTaskDao(
        private val delegate: SwarmTaskDao,
        private val fileDao: WorkspaceFileDao,
        private val scriptedArtifactContent: Map<String, String>,
        private val phaseCallCounts: MutableMap<String, Int>,
        private val capturedPrompts: MutableList<String>
    ) : SwarmTaskDao by delegate {

        // Track how many times we've already used each scripted override so we only
        // return the unresolved/scripted content on the first call for that phase.
        private val scriptedUsedCount = mutableMapOf<String, Int>()

        override suspend fun insertTask(task: SwarmTask): Long {
            // swarmName format: "Sprint <id> / <PHASENAME>"
            val phaseName = task.swarmName.substringAfterLast("/").trim()
            phaseCallCounts[phaseName] = (phaseCallCounts[phaseName] ?: 0) + 1
            capturedPrompts += task.prompt

            // Insert immediately as Completed so collectPhaseArtifact's poll loop exits at once
            val id = delegate.insertTask(task.copy(status = "Completed")).toInt()

            // Pre-write the expected artifact WorkspaceFile (cycleId is always 1 in tests)
            val phase = SprintPhase.values().firstOrNull { it.name == phaseName }
            if (phase != null) {
                val artifactPath = phase.artifactPath(cycleId = 1)
                val usedCount = scriptedUsedCount[phaseName] ?: 0
                val content = if (usedCount == 0 && scriptedArtifactContent.containsKey(phaseName)) {
                    // First call for this phase: return the scripted (possibly unresolved) content
                    scriptedArtifactContent[phaseName]!!
                } else {
                    // Subsequent calls (re-queue): return a clean passing artifact
                    "# ${phase.name} Artifact\n\nCompleted successfully."
                }
                scriptedUsedCount[phaseName] = usedCount + 1
                fileDao.insertFile(WorkspaceFile(filePath = artifactPath, content = content))
            }
            return id.toLong()
        }
    }

    /**
     * [AppDatabaseInterface] that wraps [FakeAppDatabase] via delegation but substitutes
     * its [swarmTaskDao] with [AutoCompleteSwarmTaskDao].
     *
     * Kotlin class delegation (`by inner`) lets us override only [swarmTaskDao] while
     * all other DAO accessors forward to the real [FakeAppDatabase] instance — no
     * "class is final" issue since we implement the interface, not extend the class.
     */
    private class AutoCompletingDb(
        private val inner: FakeAppDatabase = FakeAppDatabase(),
        scriptedArtifactContent: Map<String, String> = emptyMap(),
        val phaseCallCounts: MutableMap<String, Int> = mutableMapOf(),
        val capturedPrompts: MutableList<String> = mutableListOf()
    ) : AppDatabaseInterface by inner {

        private val autoTaskDao = AutoCompleteSwarmTaskDao(
            delegate = inner.swarmTaskDao(),
            fileDao = inner.workspaceFileDao(),
            scriptedArtifactContent = scriptedArtifactContent,
            phaseCallCounts = phaseCallCounts,
            capturedPrompts = capturedPrompts
        )

        override fun swarmTaskDao(): SwarmTaskDao = autoTaskDao

        // Expose the inner FakeAppDatabase for direct DAO access in assertions
        fun inner(): FakeAppDatabase = inner
    }

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    @Before
    fun setUp() {
        // PendingApprovalStore is a process-wide singleton; reset to avoid leaking
        // deferred state from a previous test in the same JVM run.
        PendingApprovalStore.reset()
    }

    private fun buildOrchestrator(
        db: AppDatabaseInterface,
        llmRouter: LlmRouterInterface = CapturingLlmRouter()
    ): SprintOrchestrator {
        val appContext = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.app.Application>()
        // SprintOrchestrator takes a real SwarmEngine but launchPhaseTask only calls
        // db.swarmTaskDao().insertTask() — the engine's executeTask is never invoked.
        val engine = SwarmEngine(
            db = db,
            gitService = GitService(Files.createTempDirectory("sprint_test_git").toFile()),
            mcpClient = FakeMcpClient(),
            appContext = appContext,
            securePrefs = FakeSecurePrefs(),
            ollamaService = FakeOllamaService(),
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
    // Test 1 — happy-path: all 6 phases complete, 6 SprintArtifact rows written
    // -------------------------------------------------------------------------

    @Test
    fun fullCycle_sixPhasesComplete_sixArtifactsWritten() =
        runTest(UnconfinedTestDispatcher()) {
            val db = AutoCompletingDb()
            val orchestrator = buildOrchestrator(db)

            orchestrator.runCycle(this, goal = "add feature X")
            // UnconfinedTestDispatcher executes coroutines eagerly, so the cycle is
            // complete synchronously by the time runCycle returns.

            // The first inserted SprintCycle always gets id=1 in a fresh FakeAppDatabase
            val cycleId = 1
            val artifacts = db.inner().sprintArtifactDao().getArtifactsForCycleSync(cycleId)

            assertEquals(
                "Expected 6 SprintArtifact rows (one per phase)",
                6, artifacts.size
            )

            val expectedPhases = SprintPhase.values().map { it.name }
            val actualPhases   = artifacts.map { it.phase }
            assertEquals(
                "Artifact phases must follow SprintPhase declaration order",
                expectedPhases, actualPhases
            )

            val cycle = db.inner().sprintCycleDao().getCycleById(cycleId)
            assertEquals("Cycle status must be COMPLETED", "COMPLETED", cycle?.status)
            assertEquals("reimplCount must be 0 on a clean cycle", 0, cycle?.reimplCount ?: -1)

            artifacts.forEach { artifact ->
                val phase = SprintPhase.valueOf(artifact.phase)
                assertEquals(
                    "Artifact path for $phase",
                    phase.artifactPath(cycleId), artifact.artifactPath
                )
            }
        }

    // -------------------------------------------------------------------------
    // Test 2 — VERIFICATION re-queue: 3 [UNRESOLVED] lines → exactly one re-impl
    // -------------------------------------------------------------------------

    @Test
    fun verificationFailure_triggersSingleImplementationRequeue() =
        runTest(UnconfinedTestDispatcher()) {
            val counts  = mutableMapOf<String, Int>()
            val db = AutoCompletingDb(
                scriptedArtifactContent = mapOf(
                    "VERIFICATION" to
                        "[UNRESOLVED: REQ-1 not met]\n[UNRESOLVED: REQ-2 not met]\n[UNRESOLVED: REQ-3 not met]"
                ),
                phaseCallCounts = counts
            )
            val orchestrator = buildOrchestrator(db)

            orchestrator.runCycle(this, goal = "add feature with unresolved items")

            assertEquals(
                "IMPLEMENTATION must run exactly twice (original + one re-queue)",
                2, counts["IMPLEMENTATION"] ?: 0
            )

            val cycle = db.inner().sprintCycleDao().getCycleById(1)
            assertEquals("reimplCount must be 1 after a single re-queue", 1, cycle?.reimplCount ?: -1)
            assertEquals("Cycle must still complete", "COMPLETED", cycle?.status)

            // With one re-run IMPLEMENTATION produces 2 artifacts, so total > 6
            val artifacts = db.inner().sprintArtifactDao().getArtifactsForCycleSync(1)
            assertTrue(
                "Expected >6 artifacts when IMPLEMENTATION re-ran (got ${artifacts.size})",
                artifacts.size > 6
            )
        }

    // -------------------------------------------------------------------------
    // Test 3 — prior context: each phase prompt embeds distilled summaries of all prior phases
    // -------------------------------------------------------------------------

    @Test
    fun phasePrompts_containPriorDistilledContext() =
        runTest(UnconfinedTestDispatcher()) {
            val prompts = mutableListOf<String>()
            val db = AutoCompletingDb(capturedPrompts = prompts)
            val llmRouter = CapturingLlmRouter()
            val orchestrator = buildOrchestrator(db, llmRouter)

            orchestrator.runCycle(this, goal = "add context-verified feature")

            assertTrue(
                "Must have captured at least 3 phase prompts",
                prompts.size >= 3
            )

            val discoveryPrompt = prompts[0]
            val designPrompt    = prompts[1]
            val implPrompt      = prompts[2]

            // DISCOVERY is first — no prior context block yet
            assertTrue(
                "DISCOVERY prompt must NOT contain prior context header",
                !discoveryPrompt.contains("=== Prior Sprint Context ===")
            )

            // DESIGN gets the DISCOVERY distillation
            assertTrue(
                "DESIGN prompt must contain '=== Prior Sprint Context ===' header",
                designPrompt.contains("=== Prior Sprint Context ===")
            )
            assertTrue("DESIGN prompt must label [DISCOVERY]", designPrompt.contains("[DISCOVERY]"))
            assertTrue(
                "DESIGN prompt must embed DISTIL_DISCOVERY value",
                designPrompt.contains("DISTIL_DISCOVERY")
            )

            // IMPLEMENTATION gets both DISCOVERY + DESIGN distillations
            assertTrue(
                "IMPLEMENTATION prompt must contain prior context header",
                implPrompt.contains("=== Prior Sprint Context ===")
            )
            assertTrue("IMPLEMENTATION prompt must reference [DISCOVERY]", implPrompt.contains("[DISCOVERY]"))
            assertTrue("IMPLEMENTATION prompt must reference [DESIGN]",    implPrompt.contains("[DESIGN]"))
            assertTrue(
                "IMPLEMENTATION prompt must embed DISTIL_DESIGN value",
                implPrompt.contains("DISTIL_DESIGN")
            )
        }
}
