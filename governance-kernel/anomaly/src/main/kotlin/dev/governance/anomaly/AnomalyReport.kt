package dev.governance.anomaly

/**
 * Raw per-window scores produced by [BehaviorAnomalyScorer.score].
 * Analogous to Xenarch's per-chip metric tuple before normalization.
 */
data class WindowScore(
    val windowIndex: Int,
    val mse: Double,
    val density: Double,
    val contextual: Double,
    val gradient: Double,
    val patternRegularity: Double,
)

/**
 * Normalized and ranked window after scoring.
 * Analogous to Xenarch's detection card (confidence + normalized metric breakdown).
 */
data class ScoredWindow(
    val windowIndex: Int,
    /** Weighted combination of normalized metric scores. */
    val combined: Double,
    /** Anomaly confidence in [0, 1]: 50% combined + 30% contextual + 20% MSE. */
    val confidence: Double,
    val mseNorm: Double,
    val densityNorm: Double,
    val contextualNorm: Double,
    val gradientNorm: Double,
    val patternNorm: Double,
)

/**
 * Top-level output of [BehaviorAnomalyLayer.analyze].
 * Analogous to Xenarch's detection results payload (summary + ranked detections).
 */
data class AnomalyReport(
    val totalWindows: Int,
    val anomalyCount: Int,
    val highConfidenceCount: Int,
    val topConfidence: Double,
    /** Combined-score threshold above which a window is flagged. */
    val anomalyThreshold: Double,
    /** All windows ranked by descending confidence. */
    val scoredWindows: List<ScoredWindow>,
    /**
     * True when at least one window exceeds the percentile threshold AND
     * the top-ranked window's confidence is at or above [BehaviorAnomalyLayer.highConfidenceThreshold].
     */
    val isAnomalous: Boolean,
) {
    companion object {
        val EMPTY = AnomalyReport(
            totalWindows = 0,
            anomalyCount = 0,
            highConfidenceCount = 0,
            topConfidence = 0.0,
            anomalyThreshold = 0.0,
            scoredWindows = emptyList(),
            isAnomalous = false,
        )
    }
}
