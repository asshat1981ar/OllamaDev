package com.example.data

enum class AntigenicSeverity { INFO, WARNING, CRITICAL }

enum class AntigenicCategory {
    BUDGET,         // cost/token overruns
    SAFETY,         // approval gates, risky operations
    QUALITY,        // verification failures, test failures
    TOOL,           // MCP, git, sandbox errors
    AGENT,          // parse errors, invalid directives
    INTEGRATION,    // DB, network, service errors
}

data class AntigenicSignal(
    val id: Long = 0,
    val taskId: Int? = null,
    val cycleId: Int? = null,
    val severity: AntigenicSeverity,
    val category: AntigenicCategory,
    val source: String,
    val signalType: String,
    val message: String,
    val detail: String = "",
    val detectedAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null,
    val resolverReportPath: String? = null,
    val resolution: String? = null,
)
