package com.example.data

import android.content.SharedPreferences

/**
 * Per-task cloud LLM budget guardrail.
 *
 * The agentic loop runs up to 16 iterations with two `preferCloud=true` LLM calls per iteration,
 * so a runaway loop can burn through a paid cloud-gateway quota without any visibility. This
 * tracker keeps a running estimate of tokens consumed *by the current task* and offers a hard
 * halt signal when the configured cap is exceeded.
 *
 * The estimate reuses the same heuristic the rest of the app already uses for metrics:
 * `(prompt.length + output.length) / 2 + 100` per call. The cap is stored in the same
 * `ollama_swarm_prefs` SharedPreferences as other user settings; `0` means unlimited (the
 * legacy behavior before this guardrail existed).
 */
object TaskBudgetTracker {

    /** Key under which the user-configurable per-task token cap is stored. */
    const val PREFS_KEY_CLOUD_TOKEN_CAP = "cloud_token_cap"

    /** Default cap of 0 means the guardrail is disabled. */
    const val DEFAULT_CLOUD_TOKEN_CAP = 0

    /** Read the configured cap from preferences. 0 = unlimited. */
    fun readCloudTokenCap(prefs: SharedPreferences): Int {
        val raw = prefs.getString(PREFS_KEY_CLOUD_TOKEN_CAP, null)
        return raw?.toIntOrNull() ?: DEFAULT_CLOUD_TOKEN_CAP
    }

    /**
     * Creates a mutable budget ledger for a single task. Call [addTokens] after each LLM
     * call and [shouldHalt] before starting the next iteration.
     */
    class Ledger(val cap: Int) {
        private var _tokensUsed: Int = 0
        val tokensUsed: Int get() = _tokensUsed

        /** Whether the cap is enabled (non-zero) and has been exceeded. */
        val isCapExceeded: Boolean
            get() = cap > 0 && _tokensUsed >= cap

        /**
         * Add [approxTokens] to the running total. This should match the estimate recorded into
         * [AgentStateStore] so the budget readout and the metrics readout stay consistent.
         */
        fun addTokens(approxTokens: Int) {
            _tokensUsed += approxTokens.coerceAtLeast(0)
        }

        /**
         * Returns true when the cap is enabled and would be exceeded by starting another LLM
         * call. Work in progress is allowed to finish (we check at iteration boundaries), so
         * this is a best-effort halt rather than mid-stream cancellation.
         */
        fun shouldHalt(): Boolean = isCapExceeded

        /** Human-readable summary for a [TaskStep] or a UI snackbar. */
        fun haltReason(): String =
            "Cloud token budget halted the loop: estimated $tokensUsed tokens used (cap: $cap)."
    }
}
