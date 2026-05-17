package dev.governance.anomaly

import dev.governance.core.GovernanceState
import dev.governance.core.HistoryEntry

/**
 * Top-level anomaly detection layer for identifying unexpected agent behavior
 * from embedding-space analysis of decision history.
 *
 * Adapted from Xenarch Mk14's planetary technosignature pipeline:
 * image chips → behavioral windows, VAE reconstruction → statistical scorer,
 * pixel metrics → soft-state metrics (gamma, entropy, divergence, outcomes).
 *
 * Pipeline (mirrors Xenarch's run_analysis steps 1-5):
 *
 *   1. Extract sliding windows from [GovernanceState.recentHistory]
 *      (analogous to chip extraction)
 *   2. Build a reference distribution from the earliest [referenceRatio]
 *      of windows, representing learned normal behavior
 *      (analogous to VAE training on nominal chips)
 *   3. Score every window with [BehaviorAnomalyScorer]'s five metrics
 *      (MSE, density, contextual, gradient, pattern regularity)
 *   4. Normalize scores min-max and combine into a confidence ranking
 *   5. Flag windows above [anomalyPercentile] as anomalous; return [AnomalyReport]
 *
 * Usage:
 * ```kotlin
 * val layer = BehaviorAnomalyLayer()
 * val report = layer.analyze(state)
 * if (report.isAnomalous) { /* escalate or emit HOLD */ }
 * ```
 *
 * The layer is stateless and thread-safe. Construct once; call [analyze] repeatedly.
 *
 * @param windowSize            Number of history entries per window. Larger values
 *                              capture longer-range behavioral patterns but require
 *                              more history before analysis starts.
 * @param stride                Step between window start positions.
 *                              Default: half-overlap (windowSize / 2).
 * @param anomalyPercentile     Combined-score percentile above which a window is
 *                              flagged as anomalous (Xenarch: percentile param).
 * @param referenceRatio        Fraction of extracted windows used as the reference
 *                              (normal) distribution. Earliest windows are used,
 *                              assuming the agent starts well-behaved.
 * @param minHistorySize        Minimum history length required; returns [AnomalyReport.EMPTY]
 *                              if history is shorter.
 * @param highConfidenceThreshold Confidence threshold for counting "high confidence"
 *                              anomalies and for the [AnomalyReport.isAnomalous] flag.
 */
class BehaviorAnomalyLayer(
    val windowSize: Int = DEFAULT_WINDOW_SIZE,
    val stride: Int = DEFAULT_WINDOW_SIZE / 2,
    val anomalyPercentile: Double = 92.0,
    val referenceRatio: Double = 0.5,
    val minHistorySize: Int = windowSize,
    val highConfidenceThreshold: Double = 0.8,
) {
    init {
        require(windowSize >= 2) { "windowSize must be at least 2" }
        require(stride >= 1) { "stride must be at least 1" }
        require(anomalyPercentile in 50.0..99.0) { "anomalyPercentile must be in [50, 99]" }
        require(referenceRatio in 0.1..0.9) { "referenceRatio must be in [0.1, 0.9]" }
    }

    /**
     * Analyze agent behavior encoded in [state.recentHistory].
     * Returns [AnomalyReport.EMPTY] when history is too short to produce any windows.
     */
    fun analyze(state: GovernanceState): AnomalyReport =
        analyzeHistory(state.recentHistory)

    /**
     * Analyze a raw history list directly (no full [GovernanceState] required).
     * Useful for testing and offline analysis.
     */
    fun analyze(history: List<HistoryEntry>): AnomalyReport =
        analyzeHistory(history)

    private fun analyzeHistory(history: List<HistoryEntry>): AnomalyReport {
        if (history.size < minHistorySize) return AnomalyReport.EMPTY

        // Step 1: Extract sliding windows — analogous to chip extraction
        val windows = WindowExtractor.extract(history, windowSize, stride)
        if (windows.isEmpty()) return AnomalyReport.EMPTY

        // Step 2: Reference distribution from earliest windows (normal baseline)
        val refCount   = (windows.size * referenceRatio).toInt().coerceAtLeast(1)
        val refWindows = windows.take(refCount)

        // Step 3: Score all windows with five metrics — analogous to NumpyAnomalyScorer
        val scorer    = BehaviorAnomalyScorer(refWindows)
        val rawScores = windows.map { scorer.score(it) }

        // Step 4: Normalize + combine into confidence — analogous to normalize_scores + compute_confidence
        val scored = scorer.normalizeAndRank(rawScores)

        // Step 5: Apply percentile threshold and package the report
        val sortedCombined = scored.map { it.combined }.sorted()
        val threshIdx  = ((anomalyPercentile / 100.0) * sortedCombined.size)
            .toInt().coerceIn(0, sortedCombined.size - 1)
        val threshold  = sortedCombined[threshIdx]

        val ranked     = scored.sortedByDescending { it.confidence }
        val anomalies  = scored.count { it.combined >= threshold }
        val highConf   = scored.count { it.confidence >= highConfidenceThreshold }
        val topConf    = ranked.firstOrNull()?.confidence ?: 0.0

        return AnomalyReport(
            totalWindows        = windows.size,
            anomalyCount        = anomalies,
            highConfidenceCount = highConf,
            topConfidence       = topConf,
            anomalyThreshold    = threshold,
            scoredWindows       = ranked,
            isAnomalous         = anomalies > 0 && topConf >= highConfidenceThreshold,
        )
    }

    companion object {
        const val DEFAULT_WINDOW_SIZE = 20
    }
}
