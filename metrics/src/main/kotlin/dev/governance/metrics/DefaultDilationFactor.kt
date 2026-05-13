package dev.governance.metrics

import dev.governance.core.*

/**
 * Default γ(t) dilation factor: a clamped moving-window weighted ratio
 * of flagged-to-total recent outcomes.
 *
 * Computes γ from [GovernanceState.recentHistory] by counting resolved
 * outcomes and weighting recent entries more heavily (exponential decay).
 * If history is empty, returns the current γ from state unchanged.
 *
 * **Placeholder; replace with framework formalization before deployment.**
 *
 * Limitations:
 * - Uses a simple exponential-decay weighting; does not account for
 *   temporal gaps between decisions.
 * - Does not distinguish between severity levels of flagged outcomes.
 * - The decay factor (0.95) and base rate (0.1) are arbitrary placeholders.
 *
 * @param decayFactor per-entry exponential decay weight (default 0.95)
 * @param baseRate minimum γ floor from the ratio computation (default 0.1)
 * @param windowSize maximum number of recent entries to consider (default 50)
 */
class DefaultDilationFactor(
    private val decayFactor: Double = 0.95,
    private val baseRate: Double = 0.1,
    private val windowSize: Int = 50,
) : DilationFactor {

    override fun compute(state: GovernanceState): Double {
        val history = state.recentHistory.takeLast(windowSize)
        if (history.isEmpty()) return state.gamma

        var weightedFlagged = 0.0
        var weightedTotal = 0.0
        var weight = 1.0

        // Most recent entries first (reversed)
        for (entry in history.asReversed()) {
            val isFlagged = entry.resolvedOutcome is ResolvedOutcome.Flagged
            if (isFlagged) weightedFlagged += weight
            weightedTotal += weight
            weight *= decayFactor
        }

        val ratio = if (weightedTotal > 0.0) weightedFlagged / weightedTotal else 0.0
        val historyGamma = (baseRate + ratio * (1.0 - baseRate)).coerceIn(0.0, 1.0)

        // Blend: use the higher of the calibrator's gamma (state.gamma) and the
        // history-based gamma. This ensures the DilationFactor respects the
        // calibrator's gradual trust descent while also reacting to recent flagged events.
        return maxOf(historyGamma, state.gamma).coerceIn(0.0, 1.0)
    }
}
