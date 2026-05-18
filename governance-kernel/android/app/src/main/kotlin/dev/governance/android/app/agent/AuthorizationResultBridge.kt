package dev.governance.android.app.agent

import android.util.Log
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
        Log.i(TAG, "create: auditId=$auditId, pending.size=${pending.size}")
        return deferred
    }

    fun deliverResult(approved: Boolean) {
        val key = activeKey
        Log.i(TAG, "deliverResult(approved=$approved): activeKey=$key, pending.size=${pending.size}")
        if (key == null) {
            Log.w(TAG, "deliverResult: activeKey is NULL — result lost!")
            return
        }
        val deferred = pending.remove(key)
        Log.i(TAG, "deliverResult: deferred=${if (deferred != null) "found" else "NULL"}")
        deferred?.complete(approved)
        activeKey = null
    }

    fun deliverResult(auditId: String, approved: Boolean) {
        Log.i(TAG, "deliverResult(auditId=$auditId, approved=$approved)")
        pending.remove(auditId)?.complete(approved)
        if (activeKey == auditId) activeKey = null
    }

    suspend fun awaitResult(auditId: String, timeoutMs: Long = 20_000): Boolean {
        val deferred = pending[auditId]
        Log.i(TAG, "awaitResult: auditId=$auditId, deferred=${if (deferred != null) "found" else "NULL"}, pending.size=${pending.size}")
        if (deferred == null) return false
        return try {
            kotlinx.coroutines.withTimeout(timeoutMs) {
                val result = deferred.await()
                Log.i(TAG, "awaitResult: got result=$result for auditId=$auditId")
                result
            }
        } catch (e: Exception) {
            Log.w(TAG, "awaitResult: timeout/error for auditId=$auditId — ${e.message}")
            pending.remove(auditId)
            false // default to skip on timeout
        }
    }

    // Legacy compatibility
    fun reset() {
        Log.i(TAG, "reset: activeKey=$activeKey, pending.size=${pending.size}")
        activeKey?.let { pending.remove(it)?.complete(false) }
        activeKey = null
    }

    private const val TAG = "AuthBridge"

    @Suppress("unused")
    suspend fun awaitResult(timeoutMs: Long = 20_000): Boolean {
        val key = activeKey ?: return false
        return awaitResult(key, timeoutMs)
    }
}
