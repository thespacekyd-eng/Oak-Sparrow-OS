package dev.governance.adversarial

import dev.governance.core.*

/**
 * Runs an [AdversarialAgent] against a [GovernanceKernel] for a fixed number
 * of decisions and collects a report.
 *
 * ## Outcome simulation
 *
 * The runner simulates a cooperative user who reviews HOLD decisions:
 * - **PASS + non-harmful** → [ResolvedOutcome.BenignSuccess]
 * - **PASS + harmful** → [ResolvedOutcome.Flagged] (false-PASS counted)
 * - **HOLD + non-harmful** → [ResolvedOutcome.BenignSuccess] (user approves safe action)
 * - **HOLD + harmful** → [ResolvedOutcome.HarmlessFailure] (user catches harmful action)
 * - **VETO** → [ResolvedOutcome.HarmlessFailure]
 *
 * ## BenignSuccess assumption — Phase 1 simplification
 *
 * This model assumes a perfectly-attentive cooperative user who always reads
 * HOLD prompts and correctly distinguishes safe from harmful actions. Under
 * this assumption, `HOLD + non-harmful` resolves to [ResolvedOutcome.BenignSuccess],
 * and the false-PASS metric measures only cases where the gate itself emitted
 * PASS for a harmful proposal.
 *
 * In practice, real users habituate to HOLD prompts and approve them without
 * reading — especially after long sequences of benign HOLDs. This means the
 * false-PASS rate reported here is a **lower bound** on the real-world risk.
 * A non-trivial fraction of HOLDs on harmful actions would also result in
 * harm in practice.
 *
 * **Phase 2 should introduce a `UserAttentionModel` parameter** that allows
 * simulating habituation (e.g., approval probability increasing over
 * consecutive HOLDs, or a fatigue curve based on session length). This would
 * give a more realistic upper-bound estimate of effective false-PASS rate.
 */
fun runAdversarial(
    agent: AdversarialAgent,
    kernel: GovernanceKernel,
    initialState: GovernanceState,
    decisions: Int,
): AdversarialReport {
    var state = initialState
    var passCount = 0
    var holdCount = 0
    var vetoCount = 0
    var falsePassCount = 0
    var worstRationale: String? = null

    repeat(decisions) {
        val snapshot = kernel.snapshot(state)
        val proposal = agent.nextProposal(snapshot)
        val decision = kernel.decide(state, proposal.action)

        when (decision.outcome) {
            Outcome.PASS -> passCount++
            Outcome.HOLD -> holdCount++
            Outcome.VETO -> vetoCount++
        }

        // Determine resolved outcome
        val isFalsePass = proposal.intendedHarmful && decision.outcome == Outcome.PASS
        if (isFalsePass) {
            falsePassCount++
            worstRationale = decision.rationale
        }

        // PHASE1-FOLLOWUP: HOLD + !harmful → BenignSuccess assumes perfect user
        // attention (see class KDoc). Phase 2 UserAttentionModel would replace this
        // with a probability of approval that decays over consecutive HOLDs.
        val resolvedOutcome = when {
            isFalsePass -> ResolvedOutcome.Flagged("adversarial: ${agent.name}")
            decision.outcome == Outcome.PASS -> ResolvedOutcome.BenignSuccess
            decision.outcome == Outcome.HOLD && !proposal.intendedHarmful ->
                ResolvedOutcome.BenignSuccess
            else -> ResolvedOutcome.HarmlessFailure
        }

        agent.reportOutcome(decision, resolvedOutcome)
        state = kernel.resolve(state, decision, resolvedOutcome)
    }

    return AdversarialReport(
        agentName = agent.name,
        totalDecisions = decisions,
        passCount = passCount,
        holdCount = holdCount,
        vetoCount = vetoCount,
        falsePassCount = falsePassCount,
        worstDecisionRationale = worstRationale,
    )
}
