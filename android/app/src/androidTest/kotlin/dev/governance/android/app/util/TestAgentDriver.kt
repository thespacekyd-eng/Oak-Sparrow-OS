package dev.governance.android.app.util

import dev.governance.core.*

/**
 * Convenience wrapper for proposing actions through
 * [KernelClientRule] without AIDL ceremony.
 *
 * Methods mirror the test-agent's button labels but run
 * inline in the test process.
 */
class TestAgentDriver(private val rule: KernelClientRule) {

    /** Proposes a fully-reversible action (typically auto-PASS after warmup). */
    fun proposeReversible(kind: String = "read_file"): GateDecision {
        val action = ProposedAction(
            id = ActionId("test-$kind-${System.nanoTime()}"),
            kind = kind,
            reversibility = Reversibility.FullyReversible,
        )
        return rule.decide(action)
    }

    /** Proposes an irreversible action (always HOLD per CompositeGate Rule 2). */
    fun proposeIrreversible(kind: String = "post_social"): GateDecision {
        val action = ProposedAction(
            id = ActionId("test-$kind-${System.nanoTime()}"),
            kind = kind,
            reversibility = Reversibility.Irreversible,
        )
        return rule.decide(action)
    }

    /**
     * Returns a decision with a corrupted attestation signature.
     * Used to test the verification-failure route — the verifier
     * should reject this and route to VerificationFailureScreen.
     */
    fun proposeTampered(): GateDecision {
        val decision = proposeReversible()
        val sig = decision.attestation.signature
        val tampered = sig.toCharArray().also {
            val mid = it.size / 2
            it[mid] = if (it[mid] == 'a') 'b' else 'a'
        }.concatToString()
        return decision.copy(
            attestation = decision.attestation.copy(signature = tampered),
        )
    }
}
