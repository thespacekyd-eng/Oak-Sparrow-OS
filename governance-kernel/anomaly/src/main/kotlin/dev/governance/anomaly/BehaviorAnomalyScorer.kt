package dev.governance.anomaly

import dev.governance.core.Outcome
import dev.governance.core.ResolvedOutcome
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Pure statistical anomaly scorer for agent behavior windows.
 *
 * Direct adaptation of Xenarch Mk14's NumpyAnomalyScorer to 1-D behavior
 * embeddings. Trained on [referenceWindows] (normal behavior), then used to
 * score arbitrary windows by deviation from that reference distribution.
 *
 * Five metrics — all adapted from Xenarch's image-anomaly pipeline:
 *
 *  mse              Reconstruction error: MSE between window embedding and
 *                   reference mean. Xenarch: mse_score(). High when the
 *                   raw feature values differ from learned norms.
 *
 *  density          Normalized L2 / Mahalanobis-approximation distance from
 *                   reference center in embedding space.
 *                   Xenarch: density_score(). High when the window lies far
 *                   from the reference cloud.
 *
 *  contextual       Burst detector: elevated veto-rate, hold-rate, flagged-rate,
 *                   and spikes in entropy / divergence variance.
 *                   Xenarch: contextual_score() bright-region analysis.
 *
 *  gradient         Rate-of-change of gamma, entropy, and divergence over the
 *                   window — rapid shifts signal behavioral discontinuities.
 *                   Xenarch: gradient_score().
 *
 *  patternRegularity Detects suspiciously mechanical / low-variance behavior
 *                   (scripted or replay-style attacks). Low variance in outcomes,
 *                   gamma, and entropy → high score.
 *                   Xenarch: edge_regularity_score().
 */
class BehaviorAnomalyScorer(referenceWindows: List<BehaviorWindow>) {

    private val refMean: DoubleArray
    private val refStd: DoubleArray

    init {
        val dim = referenceWindows.firstOrNull()?.embedding?.size ?: 0
        if (referenceWindows.isNotEmpty() && dim > 0) {
            val mean = DoubleArray(dim)
            val std  = DoubleArray(dim)
            referenceWindows.forEach { w ->
                w.embedding.forEachIndexed { i, v -> mean[i] += v }
            }
            mean.forEachIndexed { i, _ -> mean[i] /= referenceWindows.size }
            referenceWindows.forEach { w ->
                w.embedding.forEachIndexed { i, v -> std[i] += (v - mean[i]).pow(2) }
            }
            std.forEachIndexed { i, _ ->
                std[i] = sqrt(std[i] / referenceWindows.size) + 1e-8
            }
            refMean = mean
            refStd  = std
        } else {
            refMean = DoubleArray(0)
            refStd  = DoubleArray(0)
        }
    }

    // ── Individual metric functions ────────────────────────────────────────────

    /** Xenarch: mse_score() — MSE between window embedding and reference mean. */
    fun mseScore(w: BehaviorWindow): Double {
        if (refMean.isEmpty()) {
            return w.embedding.sumOf { it * it } / w.embedding.size.toDouble()
        }
        return w.embedding.indices.sumOf { i ->
            (w.embedding[i] - refMean[i]).pow(2)
        } / w.embedding.size.toDouble()
    }

    /**
     * Xenarch: density_score() — normalized L2 distance from reference center,
     * scaled by per-dimension standard deviation (Mahalanobis approximation).
     */
    fun densityScore(w: BehaviorWindow): Double {
        if (refMean.isEmpty()) return 0.0
        val dist = sqrt(w.embedding.indices.sumOf { i ->
            ((w.embedding[i] - refMean[i]) / refStd[i]).pow(2)
        })
        return dist / w.embedding.size.toDouble()
    }

    /**
     * Xenarch: contextual_score() — burst detection.
     *
     * Measures elevated veto/hold/flagged rates (analogous to bright-pixel clusters)
     * and variance spikes in entropy and divergence (analogous to texture anomalies).
     */
    fun contextualScore(w: BehaviorWindow): Double {
        val entries = w.entries
        if (entries.isEmpty()) return 0.0
        val n = entries.size.toDouble()

        val vetoRate  = entries.count { it.outcome == Outcome.VETO }.toDouble()  / n
        val holdRate  = entries.count { it.outcome == Outcome.HOLD }.toDouble()  / n
        val flagRate  = entries.count { it.resolvedOutcome is ResolvedOutcome.Flagged }.toDouble() / n

        fun varianceSpike(values: List<Double>): Double {
            val mean = values.average()
            val std  = sqrt(values.sumOf { (it - mean).pow(2) } / values.size)
            return (std / (mean + 1e-8)).coerceIn(0.0, 1.0)
        }

        val entropySpike   = varianceSpike(entries.map { it.entropy })
        val divergenceSpike = varianceSpike(entries.map { it.divergence })

        return (0.30 * vetoRate +
                0.20 * holdRate +
                0.20 * flagRate +
                0.15 * entropySpike +
                0.15 * divergenceSpike).coerceIn(0.0, 1.0)
    }

    /**
     * Xenarch: gradient_score() — rate-of-change of key soft-state metrics.
     *
     * Rapid behavioral shifts (sudden gamma jumps, entropy spikes) indicate
     * the agent may be switching attack modes or recovering from a HOLD.
     */
    fun gradientScore(w: BehaviorWindow): Double {
        val entries = w.entries
        if (entries.size < 2) return 0.0

        fun seriesGradient(values: List<Double>): Double {
            val diffs = values.zipWithNext { a, b -> abs(b - a) }
            val mean  = diffs.average()
            return diffs.sumOf { abs(it - mean) } / diffs.size
        }

        val gammaGrad = seriesGradient(entries.map { it.gamma })
        val entropyGrad = seriesGradient(entries.map { it.entropy })
        val divergenceGrad = seriesGradient(entries.map { it.divergence })

        return ((gammaGrad + entropyGrad + divergenceGrad) / 3.0).coerceIn(0.0, 1.0)
    }

    /**
     * Xenarch: edge_regularity_score() — detects suspiciously mechanical behavior.
     *
     * Very low variance in outcomes, gamma, and entropy suggests a scripted or
     * replay-style attack sequence that probes the gate with calibrated inputs.
     * Low variance → high regularity score → flagged as anomalous.
     */
    fun patternRegularityScore(w: BehaviorWindow): Double {
        val entries = w.entries
        if (entries.size < 3) return 0.0

        fun normalizedStd(values: List<Double>): Double {
            val mean = values.average()
            return sqrt(values.sumOf { (it - mean).pow(2) } / values.size)
        }

        val outcomeStd = normalizedStd(entries.map { it.outcome.ordinal.toDouble() / 2.0 })
        val gammaStd   = normalizedStd(entries.map { it.gamma })
        val entropyStd = normalizedStd(entries.map { it.entropy })

        // Low std → high regularity → high anomaly score
        return (1.0 - ((outcomeStd + gammaStd + entropyStd) / 3.0)).coerceIn(0.0, 1.0)
    }

    /** Score a single window across all five metrics. Xenarch: NumpyAnomalyScorer.score(). */
    fun score(w: BehaviorWindow): WindowScore = WindowScore(
        windowIndex       = w.windowIndex,
        mse               = mseScore(w),
        density           = densityScore(w),
        contextual        = contextualScore(w),
        gradient          = gradientScore(w),
        patternRegularity = patternRegularityScore(w),
    )

    /**
     * Normalize raw scores min-max across all windows, combine into a single
     * confidence score, and return ranked results.
     *
     * Xenarch: normalize_scores() + compute_confidence().
     *
     * Combined weights (matching Xenarch):
     *   MSE 30% · density 20% · contextual 30% · gradient 15% · pattern 5%
     *
     * Confidence (matching Xenarch):
     *   50% normalized combined + 30% contextual norm + 20% MSE norm
     */
    fun normalizeAndRank(rawScores: List<WindowScore>): List<ScoredWindow> {
        if (rawScores.isEmpty()) return emptyList()

        fun minMaxNorm(values: List<Double>): List<Double> {
            val lo = values.min()
            val hi = values.max()
            val range = hi - lo + 1e-8
            return values.map { (it - lo) / range }
        }

        val mseN  = minMaxNorm(rawScores.map { it.mse })
        val denN  = minMaxNorm(rawScores.map { it.density })
        val ctxN  = minMaxNorm(rawScores.map { it.contextual })
        val gradN = minMaxNorm(rawScores.map { it.gradient })
        val patN  = minMaxNorm(rawScores.map { it.patternRegularity })

        val combined = rawScores.indices.map { i ->
            0.30 * mseN[i] + 0.20 * denN[i] + 0.30 * ctxN[i] + 0.15 * gradN[i] + 0.05 * patN[i]
        }
        val combN = minMaxNorm(combined)

        val confidence = rawScores.indices.map { i ->
            (0.50 * combN[i] + 0.30 * ctxN[i] + 0.20 * mseN[i]).coerceIn(0.0, 1.0)
        }

        return rawScores.mapIndexed { i, raw ->
            ScoredWindow(
                windowIndex    = raw.windowIndex,
                combined       = combined[i],
                confidence     = confidence[i],
                mseNorm        = mseN[i],
                densityNorm    = denN[i],
                contextualNorm = ctxN[i],
                gradientNorm   = gradN[i],
                patternNorm    = patN[i],
            )
        }
    }
}
