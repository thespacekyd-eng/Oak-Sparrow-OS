package dev.governance.metrics

import dev.governance.core.*
import kotlin.math.ln
import kotlin.math.max

/**
 * Default predictive entropy: Shannon entropy of the action-kind distribution
 * observed in recent history, used as a proxy for the agent's next-action
 * uncertainty.
 *
 * **Placeholder; replace with framework formalization before deployment.**
 *
 * Limitations:
 * - Uses backward-looking action-kind frequency as a proxy for forward-looking
 *   entropy. A real implementation would use the agent's actual next-action
 *   probability distribution if available.
 * - Does not weight recent actions more heavily than older ones.
 * - Normalizes to [0, 1] by dividing by log(K) where K is the number of
 *   distinct action kinds; this normalization is fragile with few kinds.
 * - Returns 0.0 if history is empty (assumes low uncertainty without evidence).
 *
 * @param windowSize maximum number of recent entries to consider (default 50)
 */
class DefaultPredictiveEntropy(
    private val windowSize: Int = 50,
) : PredictiveEntropy {

    override fun estimate(state: GovernanceState): Double {
        val history = state.recentHistory.takeLast(windowSize)
        if (history.isEmpty()) return 0.0

        // Count frequency of each action kind
        val counts = history.groupingBy { it.kind }.eachCount()
        val total = history.size.toDouble()
        val numKinds = counts.size

        if (numKinds <= 1) return 0.0

        // Shannon entropy: H = -Σ p(k) * ln(p(k))
        val entropy = counts.values.sumOf { count ->
            val p = count / total
            if (p > 0.0) -p * ln(p) else 0.0
        }

        // Normalize by maximum entropy (uniform distribution over K kinds)
        val maxEntropy = ln(numKinds.toDouble())
        return if (maxEntropy > 0.0) {
            (entropy / maxEntropy).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
    }
}
