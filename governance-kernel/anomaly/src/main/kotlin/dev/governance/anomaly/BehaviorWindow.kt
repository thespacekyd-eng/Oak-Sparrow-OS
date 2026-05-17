package dev.governance.anomaly

import dev.governance.core.HistoryEntry

/**
 * A sliding window of agent history entries, analogous to an image chip in Xenarch.
 *
 * [embedding] is the flattened feature matrix (row-major):
 *   shape = [entries.size × BehaviorFeature.DIM]
 *
 * This is the unit that gets scored for anomalousness by [BehaviorAnomalyScorer].
 */
data class BehaviorWindow(
    val windowIndex: Int,
    val startIndex: Int,
    val entries: List<HistoryEntry>,
    val embedding: DoubleArray,
) {
    val size: Int get() = entries.size

    override fun equals(other: Any?) = other is BehaviorWindow && windowIndex == other.windowIndex
    override fun hashCode() = windowIndex
}

/**
 * Extracts overlapping sliding windows from a history list.
 * Analogous to Xenarch's chip-extraction step.
 *
 * @param history    the full ordered action history
 * @param windowSize number of entries per window
 * @param stride     step between window start positions (default: half-overlap)
 */
internal object WindowExtractor {

    fun extract(
        history: List<HistoryEntry>,
        windowSize: Int,
        stride: Int = windowSize / 2,
    ): List<BehaviorWindow> {
        if (history.size < windowSize) return emptyList()
        val windows = mutableListOf<BehaviorWindow>()
        var startIdx = 0
        var windowIndex = 0
        while (startIdx + windowSize <= history.size) {
            val slice = history.subList(startIdx, startIdx + windowSize)
            val embedding = DoubleArray(windowSize * BehaviorFeature.DIM)
            for (i in slice.indices) {
                val prev = if (i > 0) slice[i - 1].timestamp else null
                BehaviorFeature.encode(slice[i], prev)
                    .copyInto(embedding, destinationOffset = i * BehaviorFeature.DIM)
            }
            windows += BehaviorWindow(
                windowIndex = windowIndex++,
                startIndex = startIdx,
                entries = slice,
                embedding = embedding,
            )
            startIdx += stride
        }
        return windows
    }
}
