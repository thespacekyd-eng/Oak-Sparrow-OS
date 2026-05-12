package dev.governance.android.app.agent

import kotlinx.coroutines.delay

/**
 * Simple bridge for communicating the result of
 * [AuthorizationActivity] back to the [AgentOrchestrator].
 *
 * The orchestrator calls [awaitResult] which polls until
 * [deliverResult] is called from the activity's approve/skip
 * callback.
 */
object AuthorizationResultBridge {
    @Volatile
    private var result: Boolean? = null
    @Volatile
    private var delivered = false

    fun reset() {
        result = null
        delivered = false
    }

    fun deliverResult(approved: Boolean) {
        result = approved
        delivered = true
    }

    suspend fun awaitResult(timeoutMs: Long = 20_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!delivered && System.currentTimeMillis() < deadline) {
            delay(200)
        }
        return result ?: false // default to skip on timeout
    }
}
