package dev.governance.gate

import dev.governance.core.*
import dev.governance.attestation.DecisionSigner
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Default [GovernanceKernel] implementation. Composes metrics, the composite gate,
 * attestation signing, calibration, and audit writing into the kernel's three
 * top-level operations: [decide], [resolve], and [snapshot].
 */
class DefaultGovernanceKernel(
    private val metrics: GateMetrics,
    private val barriers: List<HardBarrier>,
    private val calibrator: Calibrator,
    private val keyProvider: KeyProvider,
    private val auditWriter: AuditWriter,
    private val clock: Clock = Clock.System,
    private val snapshotWindowSize: Int = 50,
) : GovernanceKernel {

    private val signer = DecisionSigner(keyProvider)

    override fun decide(state: GovernanceState, proposed: ProposedAction): GateDecision {
        val now = clock.now()
        val seq = state.decisionsObserved

        // Time monotonicity: backward clock triggers hard VETO
        if (now < state.timestamp) {
            val decision = signer.sign(
                outcome = Outcome.VETO,
                actionId = proposed.id,
                actionKind = proposed.kind,
                gamma = state.gamma,
                entropy = 0.0,
                divergence = 0.0,
                reversibility = proposed.reversibility,
                violatedBarriers = listOf("MonotonicityViolation"),
                rationale = "VETO: system clock moved backward (now=$now < state=${state.timestamp})",
                timestamp = now,
                sequenceNumber = seq,
            )
            auditWriter.write(AuditRecord(
                auditId = decision.auditId, proposedAction = proposed,
                stateBefore = state, decision = decision, timestamp = now,
            ))
            return decision
        }

        // Time anomaly: implausible forward jump (> 365 days) triggers HOLD
        val elapsed = now - state.timestamp
        if (elapsed > 365.days) {
            val decision = signer.sign(
                outcome = Outcome.HOLD,
                actionId = proposed.id,
                actionKind = proposed.kind,
                gamma = state.gamma,
                entropy = 0.0,
                divergence = 0.0,
                reversibility = proposed.reversibility,
                violatedBarriers = listOf("TimeAnomalyWarning"),
                rationale = "HOLD: implausible time jump detected (${elapsed.inWholeDays} days)",
                timestamp = now,
                sequenceNumber = seq,
            )
            auditWriter.write(AuditRecord(
                auditId = decision.auditId, proposedAction = proposed,
                stateBefore = state, decision = decision, timestamp = now,
            ))
            return decision
        }

        val gamma = metrics.dilationFactor.compute(state)
        val entropy = metrics.predictiveEntropy.estimate(state)
        val divergence = metrics.trajectoryDivergence.measure(state, state.referenceEnvelope)

        val result = CompositeGate.evaluate(
            gamma = gamma,
            entropy = entropy,
            divergence = divergence,
            reversibility = proposed.reversibility,
            barriers = barriers,
            action = proposed,
            state = state,
        )

        // Warmup suppression: threshold-derived VETO downgrades to HOLD
        // while the calibrator hasn't observed enough decisions to be
        // confident. Hard violations (barrier, clock) are unaffected —
        // barriers produce violatedBarriers entries, and clock anomalies
        // are handled above before reaching this point. The semantic
        // split: VETO = "system says no regardless", HOLD = "ask the
        // user". During warmup, uncertainty should escalate to the user,
        // not refuse on their behalf.
        val outcome: Outcome
        val rationale: String
        if (result.outcome == Outcome.VETO
            && calibrator.mode(state) is CalibratorMode.Warmup
            && result.violatedBarriers.isEmpty()
        ) {
            outcome = Outcome.HOLD
            rationale = result.rationale.replaceFirst("VETO:", "HOLD (warmup):")
        } else {
            outcome = result.outcome
            rationale = result.rationale
        }

        val decision = signer.sign(
            outcome = outcome,
            actionId = proposed.id,
            actionKind = proposed.kind,
            gamma = gamma,
            entropy = entropy,
            divergence = divergence,
            reversibility = proposed.reversibility,
            violatedBarriers = result.violatedBarriers,
            rationale = rationale,
            timestamp = now,
            sequenceNumber = seq,
            riskWeight = result.riskWeight,
            margins = result.margins,
        )

        // Write audit record
        val auditRecord = AuditRecord(
            auditId = decision.auditId,
            proposedAction = proposed,
            stateBefore = state,
            decision = decision,
            timestamp = decision.timestamp,
        )
        auditWriter.write(auditRecord)

        return decision
    }

    override fun resolve(
        state: GovernanceState,
        decision: GateDecision,
        outcome: ResolvedOutcome,
    ): GovernanceState {
        require(decision.sequenceNumber == state.decisionsObserved) {
            "Replay detected: decision sequenceNumber=${decision.sequenceNumber} " +
                "!= expected state.decisionsObserved=${state.decisionsObserved}"
        }
        return calibrator.update(state, decision, outcome)
    }

    override fun snapshot(state: GovernanceState): GovernanceSnapshot {
        val history = state.recentHistory.takeLast(snapshotWindowSize)

        val entropyAvg = if (history.isNotEmpty()) {
            history.sumOf { it.entropy } / history.size
        } else 0.0

        val divergenceAvg = if (history.isNotEmpty()) {
            history.sumOf { it.divergence } / history.size
        } else 0.0

        val passCount = history.count { it.outcome == Outcome.PASS }
        val holdCount = history.count { it.outcome == Outcome.HOLD }
        val vetoCount = history.count { it.outcome == Outcome.VETO }

        val windowDuration = if (history.size >= 2) {
            val first = history.first().timestamp
            val last = history.last().timestamp
            (last - first).absoluteValue
        } else {
            1.hours
        }

        return GovernanceSnapshot(
            gamma = state.gamma,
            recentEntropyAverage = entropyAvg,
            recentDivergenceAverage = divergenceAvg,
            recentOutcomes = OutcomeCounts(
                pass = passCount,
                hold = holdCount,
                veto = vetoCount,
                window = windowDuration,
            ),
            referenceEnvelopeDescription = state.referenceEnvelope.description,
            warmupComplete = calibrator.mode(state) is CalibratorMode.Steady,
            timestamp = state.timestamp,
            signingAlgorithm = keyProvider.algorithmLabel(),
        )
    }
}
