package dev.governance.calibration

import dev.governance.core.*

/**
 * Recursive trust calibrator with a defensive prior and warmup phase.
 *
 * **Warmup mode** (first [warmupThreshold] decisions): update magnitudes
 * are large, allowing rapid convergence to the user's revealed envelope.
 * Defensive starting state: γ = 0.85, reference envelope narrow.
 *
 * **Steady mode** (after warmup): update magnitudes shrink for stability.
 * A single benign success moves γ by at most [steadyBenignStep]; a single
 * flagged outcome moves γ by at least [steadyFlaggedStep].
 *
 * The mode transition is automatic and observable via [mode].
 *
 * ## Placeholder thresholds — review before deployment
 *
 * The γ step sizes below are placeholders chosen for reasonable behavior
 * in the adversarial test suite. Replace with formally derived values
 * before production use.
 *
 * @param warmupThreshold number of decisions before transitioning to steady mode (default 100)
 * @param warmupBenignStep γ decrease per benign success during warmup (default 0.02)
 * @param warmupFlaggedStep γ increase per flagged outcome during warmup (default 0.15)
 * @param steadyBenignStep γ decrease per benign success in steady mode (default 0.005)
 * @param steadyFlaggedStep γ increase per flagged outcome in steady mode (default 0.08)
 * @param harmlessFailureStep γ decrease for harmless failure (default 0.001)
 * @param maxHistorySize maximum number of entries retained in recentHistory (default 100)
 */
class DefensivePriorCalibrator(
    val warmupThreshold: Int = 100,
    val warmupBenignStep: Double = 0.02,
    val warmupFlaggedStep: Double = 0.15,
    val steadyBenignStep: Double = 0.005,
    val steadyFlaggedStep: Double = 0.08,
    val harmlessFailureStep: Double = 0.001,
    val maxHistorySize: Int = 100,
) : Calibrator {

    override fun mode(state: GovernanceState): CalibratorMode {
        val remaining = (warmupThreshold - state.decisionsObserved).coerceAtLeast(0)
        return if (remaining > 0) CalibratorMode.Warmup(remaining.toInt())
        else CalibratorMode.Steady
    }

    override fun update(
        state: GovernanceState,
        decision: GateDecision,
        outcome: ResolvedOutcome,
    ): GovernanceState {
        val newCount = state.decisionsObserved + 1
        val isWarmup = newCount <= warmupThreshold

        val gammaDelta = when (outcome) {
            is ResolvedOutcome.BenignSuccess ->
                -(if (isWarmup) warmupBenignStep else steadyBenignStep)
            is ResolvedOutcome.HarmlessFailure ->
                -harmlessFailureStep
            is ResolvedOutcome.Flagged ->
                if (isWarmup) warmupFlaggedStep else steadyFlaggedStep
        }

        val newGamma = (state.gamma + gammaDelta).coerceIn(0.0, 1.0)

        // Update reference envelope: slowly shift center toward observed distribution
        val newEnvelope = updateReferenceEnvelope(
            current = state.referenceEnvelope,
            history = state.recentHistory,
            isWarmup = isWarmup,
        )

        // Append to history
        val newEntry = HistoryEntry(
            actionId = decision.actionId,
            kind = decision.actionKind,
            outcome = decision.outcome,
            resolvedOutcome = outcome,
            gamma = newGamma,
            entropy = decision.entropy,
            divergence = decision.divergence,
            timestamp = decision.timestamp,
        )
        val newHistory = (state.recentHistory + newEntry).takeLast(maxHistorySize)

        return state.copy(
            gamma = newGamma,
            referenceEnvelope = newEnvelope,
            recentHistory = newHistory,
            decisionsObserved = newCount,
            timestamp = decision.timestamp,
        )
    }

    /**
     * Slowly shift the reference envelope center toward the observed outcome distribution.
     * During warmup, the shift rate is larger for faster convergence.
     */
    private fun updateReferenceEnvelope(
        current: ReferenceEnvelope,
        history: List<HistoryEntry>,
        isWarmup: Boolean,
    ): ReferenceEnvelope {
        if (history.isEmpty()) return current

        val n = history.size.toDouble()
        val observed = listOf(
            history.count { it.outcome == Outcome.PASS } / n,
            history.count { it.outcome == Outcome.HOLD } / n,
            history.count { it.outcome == Outcome.VETO } / n,
        )

        val learningRate = if (isWarmup) 0.05 else 0.01
        val newCenter = current.center.zip(observed) { c, o ->
            c + learningRate * (o - c)
        }

        // During warmup, gradually widen the radius to accommodate the user's actual pattern
        val radiusAdjust = if (isWarmup) 1.002 else 1.0
        val newRadius = (current.radius * radiusAdjust).coerceAtMost(2.0)

        return current.copy(
            center = newCenter,
            radius = newRadius,
        )
    }
}
