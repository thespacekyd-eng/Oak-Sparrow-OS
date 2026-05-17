package dev.governance.android.app.agent

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-decision bridge for communicating authorization results from
 * [AuthorizationActivity] back to the [SpeculativeOrchestrator].
 *
 * Each HOLD decision gets its own [CompletableDeferred], keyed by
 * audit ID. This prevents race conditions when multiple HOLD
 * decisions are pending simultaneously.
 */
object AuthorizationResultBridge {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    // Legacy single-key support for AuthorizationActivity (which
    // doesn't know the audit ID). Stores the most recent key so
    // deliverResult(Boolean) still works.
    @Volatile
    private var activeKey: String? = null

    fun create(auditId: String): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        pending[auditId] = deferred
        activeKey = auditId
        return deferred
    }

    fun deliverResult(approved: Boolean) {
        val key = activeKey ?: return
        pending.remove(key)?.complete(approved)
        activeKey = null
    }

    fun deliverResult(auditId: String, approved: Boolean) {
        pending.remove(auditId)?.complete(approved)
        if (activeKey == auditId) activeKey = null
    }

    suspend fun awaitResult(auditId: String, timeoutMs: Long = 20_000): Boolean {
        val deferred = pending[auditId] ?: return false
        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                deferred.await()
            }
        } catch (_: Exception) {
            pending.remove(auditId)
            false // default to skip on timeout
        }
    }

    // Legacy compatibility
    fun reset() {
        activeKey?.let { pending.remove(it)?.complete(false) }
        activeKey = null
    }

    @Suppress("unused")
    suspend fun awaitResult(timeoutMs: Long = 20_000): Boolean {
        val key = activeKey ?: return false
        return awaitResult(key, timeoutMs)
    }
}
