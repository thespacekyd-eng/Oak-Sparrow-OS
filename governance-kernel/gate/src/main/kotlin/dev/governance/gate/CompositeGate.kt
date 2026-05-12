package dev.governance.gate

import dev.governance.core.*
import java.util.Locale

/**
 * Composite decision gate combining soft-state metrics, reversibility weighting,
 * and hard barriers to produce PASS / HOLD / VETO decisions.
 *
 * ## Reversibility weighting
 *
 * The gate adds a reversibility bias to γ before evaluating thresholds.
 * More irreversible actions face stricter scrutiny:
 *
 * | Tier                | Bias  | Effect                                       |
 * |---------------------|-------|----------------------------------------------|
 * | FullyReversible     | +0.00 | Cheapest to allow — false VETO cost is low   |
 * | PartiallyReversible | +0.10 | Slight bias toward caution                   |
 * | OneShot             | +0.25 | Significant bias — commits can't be undone   |
 * | Irreversible        | +∞    | Always HOLD — needs confirmation (Phase 2)   |
 *
 * ## Decision boundaries (placeholder — review before deployment)
 *
 * 1. If any [HardBarrier] is violated → **VETO** (unconditional)
 * 2. If [Reversibility.Irreversible] → **HOLD** (never PASS without confirmation)
 * 3. If effective γ ≥ 0.95 → **VETO** (extreme scrutiny)
 * 4. If effective γ ≥ 0.5 → **HOLD**
 * 5. If entropy > 0.7 AND effective γ ≥ 0.1 → **HOLD** (high uncertainty)
 * 6. If divergence > 0.7 AND effective γ ≥ 0.1 → **HOLD** (trajectory anomaly)
 * 7. Otherwise → **PASS**
 *
 * ## HOLD stickiness
 *
 * To prevent oscillation on borderline states, HOLD is sticky for at least
 * one cycle: if the previous decision was HOLD, the threshold for PASS
 * is tightened (effective γ threshold drops from 0.5 to 0.45).
 */
object CompositeGate {

    /** Reversibility bias added to γ before threshold evaluation. */
    private val REVERSIBILITY_BIAS = mapOf(
        Reversibility.FullyReversible to 0.0,
        Reversibility.PartiallyReversible to 0.1,
        Reversibility.OneShot to 0.25,
        Reversibility.Irreversible to Double.MAX_VALUE, // forces HOLD
    )

    private const val VETO_THRESHOLD = 0.95
    private const val HOLD_THRESHOLD = 0.5
    private const val HOLD_STICKY_THRESHOLD = 0.45
    private const val ENTROPY_HOLD_THRESHOLD = 0.7
    private const val DIVERGENCE_HOLD_THRESHOLD = 0.7
    private const val SOFT_METRIC_GAMMA_FLOOR = 0.1

    /** How strongly action cost influences the gamma bias. */
    private const val COST_SENSITIVITY = 0.05

    fun evaluate(
        gamma: Double,
        entropy: Double,
        divergence: Double,
        reversibility: Reversibility,
        barriers: List<HardBarrier>,
        action: ProposedAction,
        state: GovernanceState,
    ): GateResult {
        val violatedBarriers = barriers.filter { it.violated(action, state) }
        val riskWt = ActionCostRegistry.impactWeight(action.kind)

        // Compute bias and effective gamma upfront (needed for margins on all paths)
        val reversibilityBias = REVERSIBILITY_BIAS[reversibility] ?: 0.0
        val costBias = ActionCostRegistry.costBias(action.kind, COST_SENSITIVITY)
        val totalBias = reversibilityBias + costBias
        val effectiveGamma = if (reversibility == Reversibility.Irreversible) {
            1.0
        } else {
            (gamma + totalBias).coerceIn(0.0, 1.0)
        }
        val margins = SafetyMargin.compute(
            effectiveGamma = effectiveGamma,
            entropy = entropy,
            divergence = divergence,
            vetoThreshold = VETO_THRESHOLD,
            holdThreshold = HOLD_THRESHOLD,
            entropyThreshold = ENTROPY_HOLD_THRESHOLD,
            divergenceThreshold = DIVERGENCE_HOLD_THRESHOLD,
        )

        // Rule 1: Hard barrier violation → VETO
        if (violatedBarriers.isNotEmpty()) {
            return GateResult(
                outcome = Outcome.VETO,
                violatedBarriers = violatedBarriers.map { it.name },
                rationale = "VETO: hard barrier(s) violated: ${violatedBarriers.joinToString { it.name }}",
                riskWeight = riskWt,
                margins = margins,
            )
        }

        // Rule 2: Irreversible → always HOLD (confirmation seam for Phase 2)
        if (reversibility == Reversibility.Irreversible) {
            return GateResult(
                outcome = Outcome.HOLD,
                violatedBarriers = emptyList(),
                rationale = "HOLD: irreversible action requires explicit confirmation (not available in Phase 1)",
                riskWeight = riskWt,
                margins = margins,
            )
        }

        // Rule 2b: RootSystem tier → always HOLD regardless of γ or warmup.
        val tier = ActionTier.classify(action.kind)
        if (tier == ActionTier.RootSystem) {
            return GateResult(
                outcome = Outcome.HOLD,
                violatedBarriers = emptyList(),
                rationale = "HOLD: root-tier action '${action.kind}' requires explicit user approval",
                riskWeight = riskWt,
                margins = margins,
            )
        }

        // Rule 3: Extreme scrutiny → VETO
        if (effectiveGamma >= VETO_THRESHOLD) {
            return GateResult(
                outcome = Outcome.VETO,
                violatedBarriers = emptyList(),
                rationale = String.format(Locale.ROOT,
                    "VETO: effective gamma %.3f >= %.3f threshold (gamma=%.3f, bias=%.3f [rev=%.2f + cost=%.3f] for %s)",
                    effectiveGamma, VETO_THRESHOLD, gamma, totalBias, reversibilityBias, costBias, reversibility
                ),
                riskWeight = riskWt,
                margins = margins,
            )
        }

        // HOLD stickiness: if previous decision was HOLD, use tighter threshold
        val previousWasHold = state.recentHistory.lastOrNull()?.outcome == Outcome.HOLD
        val holdThreshold = if (previousWasHold) HOLD_STICKY_THRESHOLD else HOLD_THRESHOLD

        // Rule 4: High effective γ → HOLD
        if (effectiveGamma >= holdThreshold) {
            return GateResult(
                outcome = Outcome.HOLD,
                violatedBarriers = emptyList(),
                rationale = String.format(Locale.ROOT,
                    "HOLD: effective gamma %.3f >= %.3f threshold (gamma=%.3f, bias=%.3f [rev=%.2f + cost=%.3f] for %s%s)",
                    effectiveGamma, holdThreshold, gamma, totalBias, reversibilityBias, costBias, reversibility,
                    if (previousWasHold) ", sticky from previous HOLD" else "",
                ),
                riskWeight = riskWt,
                margins = margins,
            )
        }

        // Rule 5: High entropy + non-trivial γ → HOLD
        if (entropy > ENTROPY_HOLD_THRESHOLD && effectiveGamma >= SOFT_METRIC_GAMMA_FLOOR) {
            return GateResult(
                outcome = Outcome.HOLD,
                violatedBarriers = emptyList(),
                rationale = String.format(Locale.ROOT,
                    "HOLD: high entropy %.3f > %.3f with effective gamma %.3f >= %.3f",
                    entropy, ENTROPY_HOLD_THRESHOLD, effectiveGamma, SOFT_METRIC_GAMMA_FLOOR
                ),
                riskWeight = riskWt,
                margins = margins,
            )
        }

        // Rule 6: High divergence + non-trivial γ → HOLD
        if (divergence > DIVERGENCE_HOLD_THRESHOLD && effectiveGamma >= SOFT_METRIC_GAMMA_FLOOR) {
            return GateResult(
                outcome = Outcome.HOLD,
                violatedBarriers = emptyList(),
                rationale = String.format(Locale.ROOT,
                    "HOLD: high divergence %.3f > %.3f with effective gamma %.3f >= %.3f",
                    divergence, DIVERGENCE_HOLD_THRESHOLD, effectiveGamma, SOFT_METRIC_GAMMA_FLOOR
                ),
                riskWeight = riskWt,
                margins = margins,
            )
        }

        // Rule 7: All checks passed → PASS
        return GateResult(
            outcome = Outcome.PASS,
            violatedBarriers = emptyList(),
            rationale = String.format(Locale.ROOT,
                "PASS: effective gamma %.3f below thresholds, entropy=%.3f, divergence=%.3f (%s)",
                effectiveGamma, entropy, divergence, reversibility
            ),
            riskWeight = riskWt,
            margins = margins,
        )
    }
}

/** Intermediate result from the gate logic before attestation. */
data class GateResult(
    val outcome: Outcome,
    val violatedBarriers: List<String>,
    val rationale: String,
    val riskWeight: Double = 1.0,
    val margins: SafetyMargin = SafetyMargin.SAFE,
)
