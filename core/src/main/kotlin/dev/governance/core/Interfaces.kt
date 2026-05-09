package dev.governance.core

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------
// Soft-state metric interfaces
// ---------------------------------------------------------------------------

/**
 * Computes the γ(t) dilation factor — a scalar in [0, 1] representing how
 * much the current decision should be slowed and scrutinized. Higher γ means
 * more scrutiny.
 */
interface DilationFactor {
    fun compute(state: GovernanceState): Double
}

/**
 * Forward-looking estimate of the entropy over the agent's likely next-action
 * distribution. Used as an early warning, not a backward-looking audit metric.
 */
interface PredictiveEntropy {
    fun estimate(state: GovernanceState): Double
}

/**
 * Distance between the agent's actual recent behavioral trajectory and the
 * calibrated [ReferenceEnvelope]. The embedding space and distance metric
 * are caller-pluggable.
 */
interface TrajectoryDivergence {
    fun measure(state: GovernanceState, reference: ReferenceEnvelope): Double
}

/**
 * Splits incoming agent telemetry into a structured signal component (used
 * for state estimation) and a noise component (logged but not allowed to
 * drive γ updates). The decomposition function is pluggable.
 */
interface NoiseSignalDecomposer {
    fun split(telemetry: AgentTelemetry): DecomposedTelemetry
}

// ---------------------------------------------------------------------------
// Hard barrier (CBF integration point)
// ---------------------------------------------------------------------------

/**
 * A control-barrier-function-style safety filter. If any hard barrier reports
 * the action as violating its safe set, the gate emits VETO regardless of
 * soft state. Phase 1 ships this interface only — no concrete subclasses.
 */
interface HardBarrier {
    val name: String
    fun violated(action: ProposedAction, state: GovernanceState): Boolean
}

// ---------------------------------------------------------------------------
// Calibration
// ---------------------------------------------------------------------------

/** Whether the calibrator is still in its initial warmup phase or has reached steady state. */
@Serializable
sealed interface CalibratorMode {
    /** Large update magnitudes for rapid convergence. */
    @Serializable
    data class Warmup(val decisionsRemaining: Int) : CalibratorMode

    /** Smaller update magnitudes for stability. */
    @Serializable
    data object Steady : CalibratorMode
}

/**
 * Recursive trust calibrator. After each decision outcome resolves, updates
 * γ and the reference envelope. Has a warmup phase with larger updates for
 * rapid convergence, then transitions to a steady mode with smaller updates.
 */
interface Calibrator {
    /**
     * Current calibration mode derived purely from [state].
     * Must not depend on any mutable internal field so the calibrator remains stateless.
     */
    fun mode(state: GovernanceState): CalibratorMode

    /**
     * Update governance state based on the resolved outcome of a decision.
     * Returns a new [GovernanceState] — does not mutate the input.
     */
    fun update(
        state: GovernanceState,
        decision: GateDecision,
        outcome: ResolvedOutcome,
    ): GovernanceState
}

// ---------------------------------------------------------------------------
// Attestation
// ---------------------------------------------------------------------------

/**
 * Provides an Ed25519 signing key for decision attestations. Phase 1 ships
 * [EphemeralKeyProvider][dev.governance.attestation.EphemeralKeyProvider];
 * Phase 2 plugs in the Android Keystore.
 */
interface KeyProvider {
    /** The public key in X.509 SubjectPublicKeyInfo encoding. */
    fun publicKey(): ByteArray

    /** Sign [message] with the private key. */
    fun sign(message: ByteArray): ByteArray

    /** Identifier for the algorithm this provider uses. Used by the UI
     *  to surface degraded-crypto warnings on debug builds. */
    fun algorithmLabel(): String = "Ed25519"
}

// ---------------------------------------------------------------------------
// Audit
// ---------------------------------------------------------------------------

/** Writes [AuditRecord] and [SystemEventRecord] entries to a persistent log. */
interface AuditWriter {
    fun write(record: AuditRecord)

    /** Write a system event that bypasses the kernel decision path. */
    fun writeSystemEvent(event: SystemEventRecord) {
        throw UnsupportedOperationException("System events not supported by this writer")
    }
}

// ---------------------------------------------------------------------------
// Composite helpers
// ---------------------------------------------------------------------------

/** Bundles the three soft-state metric implementations for the gate. */
data class GateMetrics(
    val dilationFactor: DilationFactor,
    val predictiveEntropy: PredictiveEntropy,
    val trajectoryDivergence: TrajectoryDivergence,
)
