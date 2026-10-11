package com.example.data

object AntigenicDispatcher {
    fun strategyFor(signal: AntigenicSignal): AntigenicDispatch = when (signal.signalType) {
        "BUDGET_OVERRUN" -> AntigenicDispatch(
            signalId = signal.id,
            strategy = AntigenicStrategy.AUTO_FALLBACK,
            briefPath = "",
            context = mapOf("taskId" to "${signal.taskId}", "detail" to signal.detail),
        )
        "MCP_TIMEOUT" -> AntigenicDispatch(
            signalId = signal.id,
            strategy = AntigenicStrategy.RECORD_AND_CONTINUE,
            briefPath = "",
            context = mapOf("taskId" to "${signal.taskId}"),
        )
        "VERIFICATION_UNRESOLVED" -> AntigenicDispatch(
            signalId = signal.id,
            strategy = AntigenicStrategy.PAUSE_CYCLE,
            briefPath = "",
            context = emptyMap(),
        )
        "MCP_RISKY_CALL",
        "APPROVAL_DECLINED" -> AntigenicDispatch(
            signalId = signal.id,
            strategy = AntigenicStrategy.SURFACE_TO_USER,
            briefPath = "",
            context = emptyMap(),
        )
        "APPROVAL_SKIPPED_HEADLESS",
        "TEST_FAILURE",
        "GIT_ERROR" -> AntigenicDispatch(
            signalId = signal.id,
            strategy = AntigenicStrategy.DELEGATE_FIX_SUBAGENT,
            briefPath = ".superpowers/sdd/antigenic-tier6-workflow/briefs/headless-approval.md",
            context = mapOf("taskId" to "${signal.taskId}"),
        )
        else -> AntigenicDispatch(
            signalId = signal.id,
            strategy = AntigenicStrategy.DELEGATE_FIX_SUBAGENT,
            briefPath = ".superpowers/sdd/antigenic-tier6-workflow/briefs/generic-fix.md",
            context = mapOf(
                "taskId" to "${signal.taskId}",
                "message" to signal.message,
                "signalType" to signal.signalType,
                "source" to signal.source,
            ),
        )
    }
}
