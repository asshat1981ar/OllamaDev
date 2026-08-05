package com.example.data

enum class AntigenicStrategy {
    SURFACE_TO_USER,
    PAUSE_CYCLE,
    DELEGATE_FIX_SUBAGENT,
    AUTO_FALLBACK,
    RECORD_AND_CONTINUE,
}

data class AntigenicDispatch(
    val signalId: Long,
    val strategy: AntigenicStrategy,
    val briefPath: String,
    val context: Map<String, String>,
)
