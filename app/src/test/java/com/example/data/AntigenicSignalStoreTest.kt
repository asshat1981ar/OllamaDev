package com.example.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AntigenicSignalStoreTest {

    @Before
    fun setUp() {
        AntigenicSignalStore.reset()
    }

    @Test
    fun `store records and exposes a signal`() = runTest {
        val signal = AntigenicSignal(
            severity = AntigenicSeverity.WARNING,
            category = AntigenicCategory.BUDGET,
            signalType = "BUDGET_OVERRUN",
            source = "test",
            message = "m",
        )
        val recorded = AntigenicSignalStore.recordSignal(signal)
        assertTrue(recorded.id > 0)
        assertEquals(1, AntigenicSignalStore.unresolvedSignals.value.size)

        AntigenicSignalStore.markResolved(recorded.id, "/tmp/report.md", "fixed")
        assertEquals(0, AntigenicSignalStore.unresolvedSignals.value.filter { it.resolvedAt == null }.size)
    }
}
