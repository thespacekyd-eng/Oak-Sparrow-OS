package dev.governance.core

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * Normalized distance from each decision threshold. Positive = safely below
 * the threshold, zero = at the boundary, negative = past it.
 *
 * [overall] is min(all margins) — the weakest safety dimension.
 *
 * These are informational: the gate computes them alongside its decision
 * so the agent, UI, and audit log can see how close the state is to each
 * boundary without re-deriving thresholds.
 *
 * Ref: Keeney & Raiffa, Decisions with Multiple Objectives (1976)
 */
@Serializable
data class SafetyMargin(
    /** (vetoThreshold - effectiveGamma) / vetoThreshold */
    val gammaToVeto: Double,
    /** (holdThreshold - effectiveGamma) / holdThreshold */
    val gammaToHold: Double,
    /** (entropyThreshold - entropy) / entropyThreshold */
    val entropyToHold: Double,
    /** (divergenceThreshold - divergence) / divergenceThreshold */
    val divergenceToHold: Double,
    /** min of all margins — the weakest link */
    val overall: Double,
) {
    companion object {
        /** All margins maximally safe. Used when decision is based on non-metric rules. */
        val SAFE = SafetyMargin(1.0, 1.0, 1.0, 1.0, 1.0)

        fun compute(
            effectiveGamma: Double,
            entropy: Double,
            divergence: Double,
            vetoThreshold: Double = 0.95,
            holdThreshold: Double = 0.5,
            entropyThreshold: Double = 0.7,
            divergenceThreshold: Double = 0.7,
        ): SafetyMargin {
            val gammaVeto = round3((vetoThreshold - effectiveGamma) / vetoThreshold)
            val gammaHold = round3((holdThreshold - effectiveGamma) / holdThreshold)
            val entropyM = round3((entropyThreshold - entropy) / entropyThreshold)
            val divergenceM = round3((divergenceThreshold - divergence) / divergenceThreshold)
            val overall = round3(minOf(gammaVeto, gammaHold, entropyM, divergenceM))
            return SafetyMargin(gammaVeto, gammaHold, entropyM, divergenceM, overall)
        }

        private fun round3(v: Double): Double = (v * 1000.0).roundToInt() / 1000.0
    }
}
