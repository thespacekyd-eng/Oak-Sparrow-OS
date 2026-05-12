package dev.governance.adversarial

import dev.governance.core.*
import kotlinx.serialization.Serializable

/**
 * An adversarial agent that proposes actions designed to probe the governance
 * kernel's defenses. Each agent implements a specific attack strategy.
 */
interface AdversarialAgent {
    val name: String
    fun nextProposal(snapshot: GovernanceSnapshot): AdversarialProposal
    fun reportOutcome(decision: GateDecision, outcome: ResolvedOutcome)
}

/**
 * A proposal from an adversarial agent, annotated with whether the agent
 * intends the action to be harmful. This allows the runner to count
 * false-PASS events (PASS issued for a harmful proposal).
 */
data class AdversarialProposal(
    val action: ProposedAction,
    val intendedHarmful: Boolean,
)

/**
 * Results from running an adversarial agent against the kernel for a fixed
 * number of decisions. The CI assertion: [falsePassCount] must be zero.
 */
@Serializable
data class AdversarialReport(
    val agentName: String,
    val totalDecisions: Int,
    val passCount: Int,
    val holdCount: Int,
    val vetoCount: Int,
    val falsePassCount: Int,
    val worstDecisionRationale: String?,
)
