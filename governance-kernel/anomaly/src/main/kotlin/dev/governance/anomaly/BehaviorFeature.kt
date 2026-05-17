package dev.governance.anomaly

import dev.governance.core.HistoryEntry
import dev.governance.core.Outcome
import dev.governance.core.ResolvedOutcome
import kotlinx.datetime.Instant

/**
 * Encodes a [HistoryEntry] as a fixed-length numeric feature vector.
 *
 * Adapted from Xenarch Mk14's per-pixel feature extraction. Each entry in the
 * agent's decision history becomes a single row in a behavior window, analogous
 * to a row of pixels in an image chip.
 *
 * Dimensions — all values normalized to [0, 1]:
 *   0  outcomeScore   PASS=0.0, HOLD=0.5, VETO=1.0
 *   1  gamma          governance dilation factor at decision time
 *   2  entropy        predictive entropy at decision time
 *   3  divergence     trajectory divergence at decision time
 *   4  flaggedScore   1.0 if resolvedOutcome is Flagged, else 0.0
 *   5  timeDeltaNorm  inter-decision interval, capped at MAX_INTERVAL_S and normalized
 */
internal object BehaviorFeature {

    const val DIM = 6

    // Inter-decision intervals longer than this are treated as equivalent (idle / new session).
    private const val MAX_INTERVAL_S = 3600.0

    fun encode(entry: HistoryEntry, prevTimestamp: Instant?): DoubleArray {
        val outcomeScore = when (entry.outcome) {
            Outcome.PASS -> 0.0
            Outcome.HOLD -> 0.5
            Outcome.VETO -> 1.0
        }
        val flaggedScore = if (entry.resolvedOutcome is ResolvedOutcome.Flagged) 1.0 else 0.0
        val timeDeltaNorm = if (prevTimestamp == null) 0.0 else {
            val deltaS = (entry.timestamp - prevTimestamp).inWholeSeconds.toDouble()
            (deltaS.coerceAtLeast(0.0) / MAX_INTERVAL_S).coerceIn(0.0, 1.0)
        }
        return doubleArrayOf(
            outcomeScore,
            entry.gamma.coerceIn(0.0, 1.0),
            entry.entropy.coerceIn(0.0, 1.0),
            entry.divergence.coerceIn(0.0, 1.0),
            flaggedScore,
            timeDeltaNorm,
        )
    }
}
