package com.example.data

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AntigenicOrchestratorTest {

    private class RecordingLauncher : SubagentLauncher {
        val calls = mutableListOf<Triple<Long, String, Map<String, String>>>()
        override fun launchFixSubagent(
            signalId: Long,
            briefPath: String,
            context: Map<String, String>,
        ) {
            calls.add(Triple(signalId, briefPath, context))
        }
    }

    @Before
    fun setUp() {
        AntigenicSignalStore.reset()
        AntigenicOrchestrator.reset()
    }

    @Test
    fun `headless skip signal delegates fix subagent`() = runTest(UnconfinedTestDispatcher()) {
        val launcher = RecordingLauncher()
        AntigenicOrchestrator.launcher = launcher
        AntigenicOrchestrator.startObserving(backgroundScope)

        val signal = AntigenicSignal(
            category = AntigenicCategory.SAFETY,
            signalType = "APPROVAL_SKIPPED_HEADLESS",
            severity = AntigenicSeverity.WARNING,
            source = "AgenticActionExecutor",
            taskId = 42,
            message = "Approval skipped in headless mode",
            detail = "risky git push",
        )
        val recorded = AntigenicSignalStore.recordSignal(signal)

        advanceUntilIdle()

        assertEquals(1, launcher.calls.size)
        val call = launcher.calls.first()
        assertEquals(recorded.id, call.first)
        assertTrue(call.second.contains("headless-approval.md"))
        assertEquals("42", call.third["taskId"])
    }

    @Test
    fun `in flight guard prevents duplicate dispatch`() = runTest(UnconfinedTestDispatcher()) {
        val launcher = RecordingLauncher()
        AntigenicOrchestrator.launcher = launcher
        AntigenicOrchestrator.startObserving(backgroundScope)

        val signal = AntigenicSignal(
            id = 99L, // stable id so both store entries are "the same signal"
            category = AntigenicCategory.SAFETY,
            signalType = "APPROVAL_SKIPPED_HEADLESS",
            severity = AntigenicSeverity.WARNING,
            source = "AgenticActionExecutor",
            taskId = 1,
            message = "Approval skipped in headless mode",
            detail = "risky git push",
        )
        AntigenicSignalStore.recordSignal(signal)
        // Store emits a new list; signal is still unresolved but already in-flight.
        AntigenicSignalStore.recordSignal(signal)

        advanceUntilIdle()

        assertEquals(1, launcher.calls.size)
    }

    @Test
    fun `generic signal falls back to generic brief`() = runTest(UnconfinedTestDispatcher()) {
        val launcher = RecordingLauncher()
        AntigenicOrchestrator.launcher = launcher
        AntigenicOrchestrator.startObserving(backgroundScope)

        val signal = AntigenicSignal(
            category = AntigenicCategory.QUALITY,
            signalType = "UNKNOWN_FAILURE",
            severity = AntigenicSeverity.WARNING,
            source = "SwarmEngine",
            taskId = 7,
            message = "Something failed",
            detail = "generic",
        )
        AntigenicSignalStore.recordSignal(signal)

        advanceUntilIdle()

        assertEquals(1, launcher.calls.size)
        assertTrue(launcher.calls.first().second.contains("generic-fix.md"))
    }
}
