package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AntigenicSignalTest {

    @Test
    fun `signal holds required fields`() {
        val signal = AntigenicSignal(
            id = 1,
            taskId = 42,
            cycleId = 7,
            severity = AntigenicSeverity.WARNING,
            category = AntigenicCategory.BUDGET,
            source = "TaskBudgetTracker",
            signalType = "BUDGET_OVERRUN",
            message = "Cloud token cap exceeded",
            detail = "estimated=5100 cap=5000",
        )
        assertEquals(42, signal.taskId)
        assertEquals(AntigenicCategory.BUDGET, signal.category)
        assertEquals("BUDGET_OVERRUN", signal.signalType)
        assertNull(signal.resolvedAt)
    }
}
