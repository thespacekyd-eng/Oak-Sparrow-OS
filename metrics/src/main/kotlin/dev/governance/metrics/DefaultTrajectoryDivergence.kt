package dev.governance.metrics

import dev.governance.core.*
import kotlin.math.sqrt

/**
 * Default trajectory divergence: Euclidean distance between a feature vector
 * extracted from recent history and the reference envelope center, normalized
 * by the envelope radius.
 *
 * The feature vector is built from the outcome distribution in recent history:
 * `[passRatio, holdRatio, vetoRatio]`, padded or truncated to match the
 * reference envelope center's dimensionality.
 *
 * **Placeholder; replace with framework formalization before deployment.**
 *
 * Limitations:
 * - Uses only outcome-distribution features; does not embed action kinds,
 *   payloads, or temporal patterns.
 * - Euclidean distance in ratio space may not capture meaningful behavioral
 *   divergence for all use cases.
 * - The normalization by envelope radius is a linear scaling; a Mahalanobis
 *   distance or learned metric would be more principled.
 * - Returns 0.0 if history is empty (assumes no divergence without evidence).
 *
 * @param windowSize maximum number of recent entries to consider (default 50)
 */
class DefaultTrajectoryDivergence(
    private val windowSize: Int = 50,
) : TrajectoryDivergence {

    override fun measure(state: GovernanceState, reference: ReferenceEnvelope): Double {
        val history = state.recentHistory.takeLast(windowSize)
        if (history.isEmpty()) return 0.0
        if (reference.center.isEmpty()) return 0.0

        val features = buildFeatureVector(history, reference.center.size)
        val distance = euclideanDistance(features, reference.center)

        return if (reference.radius > 0.0) {
            (distance / reference.radius).coerceIn(0.0, 1.0)
        } else {
            if (distance > 0.0) 1.0 else 0.0
        }
    }

    private fun buildFeatureVector(history: List<HistoryEntry>, targetSize: Int): List<Double> {
        val n = history.size.toDouble()
        val raw = mutableListOf(
            history.count { it.outcome == Outcome.PASS } / n,
            history.count { it.outcome == Outcome.HOLD } / n,
            history.count { it.outcome == Outcome.VETO } / n,
        )
        // Pad with zeros or truncate to match reference dimensions
        while (raw.size < targetSize) raw.add(0.0)
        return raw.take(targetSize)
    }

    companion object {
        fun euclideanDistance(a: List<Double>, b: List<Double>): Double {
            require(a.size == b.size) { "Vectors must have same dimensionality: ${a.size} vs ${b.size}" }
            val sumSq = a.zip(b).sumOf { (ai, bi) -> (ai - bi) * (ai - bi) }
            return sqrt(sumSq)
        }
    }
}
