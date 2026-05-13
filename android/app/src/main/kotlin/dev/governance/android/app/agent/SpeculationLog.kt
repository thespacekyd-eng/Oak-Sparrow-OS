package dev.governance.android.app.agent

import androidx.compose.runtime.mutableStateListOf
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory log of speculative dispatch decisions: which steps were
 * speculatively launched, which the kernel later confirmed (PASS),
 * and which were rolled back (HOLD-denied or VETO).
 *
 * Observable by the chat surface so the user can see "speculation
 * confirmed" or "speculation overruled" feedback when the kernel
 * decision arrives.
 *
 * This is a UX log, not a security log. The authoritative audit
 * record still lives in [dev.governance.audit.AuditWriter] and is
 * produced from the real signed [dev.governance.core.GateDecision]
 * that arrives from the kernel.
 */
class SpeculationLog {
    private val nextId = AtomicLong(0)

    val entries = mutableStateListOf<SpeculationEntry>()

    fun recordStarted(step: PlannedStep): Long {
        val id = nextId.incrementAndGet()
        entries.add(
            SpeculationEntry(
                id = id,
                kind = step.kind,
                target = step.target,
                state = State.Speculating,
                reason = null,
                auditId = null,
                startedAt = Clock.System.now(),
                resolvedAt = null,
            )
        )
        return id
    }

    fun recordCommitted(id: Long, auditId: String) = updateState(id) {
        it.copy(state = State.Committed, auditId = auditId, resolvedAt = Clock.System.now())
    }

    fun recordRolledBack(id: Long, reason: String) = updateState(id) {
        it.copy(state = State.RolledBack, reason = reason, resolvedAt = Clock.System.now())
    }

    fun count(state: State): Int = entries.count { it.state == state }

    private fun updateState(id: Long, update: (SpeculationEntry) -> SpeculationEntry) {
        val index = entries.indexOfFirst { it.id == id }
        if (index >= 0) entries[index] = update(entries[index])
    }

    enum class State { Speculating, Committed, RolledBack }

    data class SpeculationEntry(
        val id: Long,
        val kind: String,
        val target: String?,
        val state: State,
        val reason: String?,
        val auditId: String?,
        val startedAt: Instant,
        val resolvedAt: Instant?,
    )
}
