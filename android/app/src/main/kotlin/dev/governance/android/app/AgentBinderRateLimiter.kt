package dev.governance.android.app

import android.os.Binder
import dev.governance.android.platform.parcel.GateDecisionParcel
import dev.governance.android.platform.parcel.GovernanceSnapshotParcel
import dev.governance.android.platform.parcel.ProposedActionParcel
import dev.governance.android.platform.parcel.ResolvedOutcomeParcel
import dev.governance.core.*
import kotlinx.serialization.json.Json
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Rate-limiting wrapper around the AIDL [AgentKernelInterface].
 *
 * Limits [decide] calls per calling UID using a token-bucket algorithm.
 * Default: [sustainedRate] decisions/second sustained, [burstCapacity] burst.
 *
 * Excess requests receive a HOLD decision with a `RateLimitExceeded`
 * violated barrier rather than reaching the kernel. This protects against
 * an agent process trying to exhaust the kernel by spamming proposals.
 *
 * [resolve] and [snapshot] are not rate-limited — they don't change
 * the governance state in a way that benefits an attacker.
 */
class AgentBinderRateLimiter(
    private val delegate: AgentKernelInterface.Stub,
    private val sustainedRate: Double = 50.0,
    private val burstCapacity: Int = 200,
) : AgentKernelInterface.Stub() {

    private val buckets = ConcurrentHashMap<Int, TokenBucket>()

    override fun decide(action: ProposedActionParcel): GateDecisionParcel {
        val uid = Binder.getCallingUid()
        val bucket = buckets.getOrPut(uid) { TokenBucket(sustainedRate, burstCapacity) }

        if (!bucket.tryConsume()) {
            return buildRateLimitHold(action)
        }

        return delegate.decide(action)
    }

    override fun resolve(decisionAuditId: String, outcome: ResolvedOutcomeParcel) {
        delegate.resolve(decisionAuditId, outcome)
    }

    override fun snapshot(): GovernanceSnapshotParcel {
        return delegate.snapshot()
    }

    private fun buildRateLimitHold(action: ProposedActionParcel): GateDecisionParcel {
        val proposed = action.toKernel()
        val json = Json { encodeDefaults = true }

        // Build a synthetic HOLD decision without going through the kernel.
        // This does NOT have a valid attestation (no signing key access here),
        // but includes the RateLimitExceeded barrier so the agent knows why.
        val decision = GateDecision(
            outcome = Outcome.HOLD,
            actionId = proposed.id,
            actionKind = proposed.kind,
            gamma = 1.0,
            entropy = 0.0,
            divergence = 0.0,
            reversibility = proposed.reversibility,
            violatedBarriers = listOf("RateLimitExceeded"),
            auditId = AuditId("rate-limited-${System.nanoTime()}"),
            // Locale.ROOT: decimal in rationale
            rationale = String.format(
                Locale.ROOT,
                "HOLD: rate limit exceeded for UID %d (sustained=%.0f/s, burst=%d)",
                Binder.getCallingUid(), sustainedRate, burstCapacity,
            ),
            attestation = DecisionAttestation(
                contentHash = "rate-limited",
                signature = "none",
                publicKey = "none",
            ),
            timestamp = kotlinx.datetime.Clock.System.now(),
            sequenceNumber = -1L, // Not a real kernel decision
        )
        return GateDecisionParcel.from(decision)
    }

    /**
     * Token-bucket rate limiter. Thread-safe via synchronized.
     */
    internal class TokenBucket(
        private val refillRate: Double,
        private val capacity: Int,
    ) {
        private var tokens: Double = capacity.toDouble()
        private var lastRefillNanos: Long = System.nanoTime()

        @Synchronized
        fun tryConsume(): Boolean {
            refill()
            return if (tokens >= 1.0) {
                tokens -= 1.0
                true
            } else {
                false
            }
        }

        private fun refill() {
            val now = System.nanoTime()
            val elapsed = (now - lastRefillNanos) / 1_000_000_000.0
            tokens = (tokens + elapsed * refillRate).coerceAtMost(capacity.toDouble())
            lastRefillNanos = now
        }
    }
}
