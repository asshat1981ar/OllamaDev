package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AntigenicDispatcherTest {

    @Test
    fun `dispatcher maps budget overrun to auto fallback`() {
        val signal = AntigenicSignal(
            severity = AntigenicSeverity.CRITICAL,
            category = AntigenicCategory.BUDGET,
            signalType = "BUDGET_OVERRUN",
            source = "test",
            message = "cap hit",
        )
        val dispatch = AntigenicDispatcher.strategyFor(signal)
        assertEquals(AntigenicStrategy.AUTO_FALLBACK, dispatch.strategy)
        assertEquals(signal.id, dispatch.signalId)
    }

    @Test
    fun `dispatcher maps headless approval to delegate fix subagent`() {
        val signal = AntigenicSignal(
            severity = AntigenicSeverity.CRITICAL,
            category = AntigenicCategory.SAFETY,
            signalType = "APPROVAL_SKIPPED_HEADLESS",
            source = "test",
            message = "headless",
        )
        val dispatch = AntigenicDispatcher.strategyFor(signal)
        assertEquals(AntigenicStrategy.DELEGATE_FIX_SUBAGENT, dispatch.strategy)
    }
}
