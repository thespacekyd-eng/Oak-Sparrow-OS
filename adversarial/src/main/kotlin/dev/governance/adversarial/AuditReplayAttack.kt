package dev.governance.adversarial

import dev.governance.core.*

/**
 * Simulates an adversary capturing a prior PASS [GateDecision] (with its
 * valid Ed25519 attestation) and re-submitting it at a later point when
 * governance state has advanced.
 *
 * ## Attack vector
 *
 * An attacker who can observe the audit log or intercept kernel output can
 * obtain a legitimately signed PASS decision. If the kernel accepted that
 * decision's attestation without freshness checks, the attacker could bypass
 * the current (potentially higher) gamma by replaying the old decision.
 *
 * ## Defense
 *
 * Each [GateDecision] includes a [GateDecision.sequenceNumber] derived from
 * [GovernanceState.decisionsObserved] at decision time. The sequence number
 * is included in the [DecisionAttestation] content hash, so forging a
 * different sequence number would invalidate the signature.
 * [GovernanceKernel.resolve] rejects decisions whose sequence number does
 * not match the current state, detecting replays.
 *
 * ## Test structure
 *
 * Unlike the other adversarial agents, this scenario does not implement
 * [AdversarialAgent] because the attack targets the decision-verification
 * path rather than the proposal path. It exercises the kernel directly
 * via [run].
 */
class AuditReplayAttack {
    val name = "AuditReplayAttack"

    /**
     * Run the replay attack scenario. Returns an [AdversarialReport] with
     * [AdversarialReport.falsePassCount] = 0 if all replays were detected.
     */
    fun run(
        kernel: GovernanceKernel,
        initialState: GovernanceState,
        decisions: Int,
    ): AdversarialReport {
        var state = initialState
        var passCount = 0
        var holdCount = 0
        var vetoCount = 0
        var falsePassCount = 0
        val capturedPasses = mutableListOf<GateDecision>()

        // Phase 1: normal operation — capture PASS decisions
        val setupRounds = decisions / 2
        repeat(setupRounds) { i ->
            val action = ProposedAction(
                id = ActionId("replay-setup-$i"),
                kind = "read_file",
                reversibility = Reversibility.FullyReversible,
            )
            val decision = kernel.decide(state, action)
            when (decision.outcome) {
                Outcome.PASS -> { passCount++; capturedPasses.add(decision) }
                Outcome.HOLD -> holdCount++
                Outcome.VETO -> vetoCount++
            }
            state = kernel.resolve(state, decision, ResolvedOutcome.BenignSuccess)
        }

        // Phase 2: attempt to replay captured PASS decisions at current state
        val replayRounds = minOf(decisions - setupRounds, capturedPasses.size)
        repeat(replayRounds) { i ->
            val staleDecision = capturedPasses[i]
            val replayDetected = try {
                kernel.resolve(state, staleDecision, ResolvedOutcome.BenignSuccess)
                false // replay was NOT detected — this is a false-PASS
            } catch (_: IllegalArgumentException) {
                true // replay was detected
            }

            if (!replayDetected) {
                falsePassCount++
            }
        }

        return AdversarialReport(
            agentName = name,
            totalDecisions = decisions,
            passCount = passCount,
            holdCount = holdCount,
            vetoCount = vetoCount,
            falsePassCount = falsePassCount,
            worstDecisionRationale = if (falsePassCount > 0)
                "Replay of stale decision was not detected" else null,
        )
    }
}
