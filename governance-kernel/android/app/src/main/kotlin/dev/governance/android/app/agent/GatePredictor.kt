package dev.governance.android.app.agent

import dev.governance.core.*

/**
 * Client-side gate prediction from a [GovernanceSnapshot].
 *
 * Mirrors the [CompositeGate] threshold logic using only the read-only
 * snapshot — no IPC, no signing, no audit. The prediction is a latency
 * optimisation hint: the real [GateDecision] always runs asynchronously
 * for attestation and audit, so safety is never compromised even if the
 * prediction is wrong.
 *
 * When [predict] returns [Outcome.PASS] with [Confidence.HIGH], the
 * orchestrator can dispatch instantly (negative latency) and reconcile
 * the signed decision after the fact.
 */
object GatePredictor {

    enum class Confidence { HIGH, LOW }

    data class Prediction(
        val outcome: Outcome,
        val confidence: Confidence,
        val effectiveGamma: Double,
        val marginToHold: Double,
    )

    // Mirror of CompositeGate thresholds
    private const val VETO_THRESHOLD = 0.95
    private const val HOLD_THRESHOLD = 0.5
    private const val ENTROPY_HOLD_THRESHOLD = 0.7
    private const val DIVERGENCE_HOLD_THRESHOLD = 0.7
    private const val SOFT_METRIC_GAMMA_FLOOR = 0.1
    private const val COST_SENSITIVITY = 0.05

    /** Minimum margin-to-HOLD required for HIGH confidence instant dispatch. */
    private const val HIGH_CONFIDENCE_MARGIN = 0.20

    private val REVERSIBILITY_BIAS = mapOf(
        Reversibility.FullyReversible to 0.0,
        Reversibility.PartiallyReversible to 0.1,
        Reversibility.OneShot to 0.25,
        Reversibility.Irreversible to Double.MAX_VALUE,
    )

    /**
     * Predict the gate outcome for [step] given the current [snapshot].
     *
     * Does NOT call the kernel — pure local computation from cached state.
     * The prediction uses the same threshold logic as CompositeGate so
     * the two agree in steady state. During rapid gamma changes the
     * prediction may lag by one decision cycle.
     */
    fun predict(step: PlannedStep, snapshot: GovernanceSnapshot): Prediction {
        val tier = ActionTier.classify(step.kind)

        // Hard rules: RootSystem and Irreversible always HOLD
        if (tier == ActionTier.RootSystem) {
            return Prediction(Outcome.HOLD, Confidence.HIGH, 1.0, -1.0)
        }
        if (step.reversibility == Reversibility.Irreversible) {
            return Prediction(Outcome.HOLD, Confidence.HIGH, 1.0, -1.0)
        }

        val revBias = REVERSIBILITY_BIAS[step.reversibility] ?: 0.0
        val costBias = kotlin.math.ln(ActionCostRegistry.impactWeight(step.kind)) * COST_SENSITIVITY
        val effectiveGamma = (snapshot.gamma + revBias + costBias).coerceIn(0.0, 1.0)

        // VETO prediction
        if (effectiveGamma >= VETO_THRESHOLD) {
            return Prediction(Outcome.VETO, Confidence.HIGH, effectiveGamma, -1.0)
        }

        // HOLD from gamma
        if (effectiveGamma >= HOLD_THRESHOLD) {
            return Prediction(Outcome.HOLD, Confidence.HIGH, effectiveGamma,
                (HOLD_THRESHOLD - effectiveGamma) / HOLD_THRESHOLD)
        }

        // HOLD from entropy
        if (snapshot.recentEntropyAverage > ENTROPY_HOLD_THRESHOLD &&
            effectiveGamma >= SOFT_METRIC_GAMMA_FLOOR) {
            return Prediction(Outcome.HOLD, Confidence.LOW, effectiveGamma, 0.0)
        }

        // HOLD from divergence
        if (snapshot.recentDivergenceAverage > DIVERGENCE_HOLD_THRESHOLD &&
            effectiveGamma >= SOFT_METRIC_GAMMA_FLOOR) {
            return Prediction(Outcome.HOLD, Confidence.LOW, effectiveGamma, 0.0)
        }

        // PASS
        val marginToHold = (HOLD_THRESHOLD - effectiveGamma) / HOLD_THRESHOLD
        val confidence = if (marginToHold >= HIGH_CONFIDENCE_MARGIN)
            Confidence.HIGH else Confidence.LOW

        return Prediction(Outcome.PASS, confidence, effectiveGamma, marginToHold)
    }

    /**
     * Whether the orchestrator should use instant (negative latency) dispatch.
     *
     * Requirements:
     * - Predicted PASS with HIGH confidence
     * - FullyReversible (rollback is cheap if prediction is wrong)
     * - App tier (not privileged)
     * - Warmup complete (prediction unreliable during warmup)
     */
    fun shouldInstantDispatch(step: PlannedStep, snapshot: GovernanceSnapshot): Boolean {
        if (!snapshot.warmupComplete) return false
        if (step.reversibility != Reversibility.FullyReversible) return false
        if (ActionTier.classify(step.kind) != ActionTier.App) return false
        val prediction = predict(step, snapshot)
        return prediction.outcome == Outcome.PASS && prediction.confidence == Confidence.HIGH
    }

    /**
     * Whether the orchestrator should use speculative (zero latency) dispatch
     * even during warmup. This is less aggressive than instant dispatch:
     * the kernel decision runs in parallel, and the action is rolled back
     * on VETO. Safe because FullyReversible + App tier = worst case is
     * "we opened an app and then closed it."
     */
    fun shouldSpeculateDuringWarmup(step: PlannedStep, snapshot: GovernanceSnapshot): Boolean {
        if (step.reversibility != Reversibility.FullyReversible) return false
        if (ActionTier.classify(step.kind) != ActionTier.App) return false
        val prediction = predict(step, snapshot)
        // Speculate even during warmup if gamma is reasonable (below HOLD)
        return prediction.outcome == Outcome.PASS
    }
}
