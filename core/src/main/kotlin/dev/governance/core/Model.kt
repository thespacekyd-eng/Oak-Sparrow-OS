package dev.governance.core

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.time.Duration

/**
 * The calibrated reference trajectory envelope. The kernel measures divergence
 * of the agent's recent behavior from this envelope. [center] is a feature vector
 * in an embedding space; [radius] defines the acceptable distance from center.
 */
@Serializable
data class ReferenceEnvelope(
    val center: List<Double>,
    val radius: Double,
    val description: String,
)

/**
 * A single entry in the kernel's recent decision history. Used by metrics
 * to compute γ, entropy, and divergence.
 */
@Serializable
data class HistoryEntry(
    val actionId: ActionId,
    val kind: String,
    val outcome: Outcome,
    val resolvedOutcome: ResolvedOutcome? = null,
    val gamma: Double,
    val entropy: Double = 0.0,
    val divergence: Double = 0.0,
    val timestamp: Instant,
)

/**
 * The full governance state at a point in time. Passed into metrics, the gate,
 * and the calibrator. Managed externally — the kernel returns new states rather
 * than mutating.
 */
@Serializable
data class GovernanceState(
    val gamma: Double,
    val referenceEnvelope: ReferenceEnvelope,
    val recentHistory: List<HistoryEntry>,
    val decisionsObserved: Long,
    val timestamp: Instant,
) {
    init {
        require(gamma in 0.0..1.0) { "gamma must be in [0, 1], got $gamma" }
    }
}

/** A proposed action from the external agent, awaiting the gate's decision. */
@Serializable
data class ProposedAction(
    val id: ActionId,
    val kind: String,
    val reversibility: Reversibility,
    val payload: Map<String, JsonElement> = emptyMap(),
) {
    init {
        require(id.value.isNotEmpty()) { "ProposedAction id must not be empty" }
    }
}

/** Raw telemetry from the agent, before noise-signal decomposition. */
@Serializable
data class AgentTelemetry(
    val timestamp: Instant,
    val entries: Map<String, JsonElement>,
)

/** Telemetry after decomposition into structured signal and noise. */
@Serializable
data class DecomposedTelemetry(
    val signal: Map<String, JsonElement>,
    val noise: Map<String, JsonElement>,
)

/**
 * Cryptographic attestation binding a [GateDecision] to the kernel's signing key.
 * All byte arrays are hex-encoded strings for serialization safety and proper equality.
 */
@Serializable
data class DecisionAttestation(
    /** Hex-encoded SHA-256 of the canonical JSON of the decision (excluding auditId and attestation). */
    val contentHash: String,
    /** Hex-encoded Ed25519 signature over [contentHash] bytes. */
    val signature: String,
    /** Hex-encoded Ed25519 public key for self-contained verification. */
    val publicKey: String,
) {
    init {
        require(contentHash.isNotEmpty()) { "contentHash must not be empty" }
        require(signature.isNotEmpty()) { "signature must not be empty" }
        require(publicKey.isNotEmpty()) { "publicKey must not be empty" }
    }
}

/**
 * The kernel's decision on a proposed action. Includes the soft-state values
 * at decision time, the attestation, and a content-addressed audit ID.
 *
 * [sequenceNumber] is a monotonically increasing counter derived from
 * [GovernanceState.decisionsObserved] at decision time. It is included in
 * the attestation content hash to bind each decision to a unique point in
 * the governance timeline, preventing replay attacks where a valid older
 * attestation is re-presented at a later state.
 */
@Serializable
data class GateDecision(
    val outcome: Outcome,
    val actionId: ActionId,
    val actionKind: String,
    val gamma: Double,
    val entropy: Double,
    val divergence: Double,
    val reversibility: Reversibility,
    val violatedBarriers: List<String>,
    val auditId: AuditId,
    val rationale: String,
    val attestation: DecisionAttestation,
    val timestamp: Instant,
    val sequenceNumber: Long,
    /** Cost-based risk weight applied to this action. 1.0 = baseline. */
    val riskWeight: Double = 1.0,
    /** Normalized distance from each decision threshold at decision time. */
    val margins: SafetyMargin = SafetyMargin.SAFE,
)

/** Outcome counts over a recent time window, for the agent-facing snapshot. */
@Serializable
data class OutcomeCounts(
    val pass: Int,
    val hold: Int,
    val veto: Int,
    val window: Duration,
)

/**
 * Read-only snapshot of governance state for cooperative agents. The agent can
 * consult this before proposing actions to self-modulate. The kernel does NOT
 * trust the agent's reading — the gate still runs on every proposal.
 */
@Serializable
data class GovernanceSnapshot(
    val gamma: Double,
    val recentEntropyAverage: Double,
    val recentDivergenceAverage: Double,
    val recentOutcomes: OutcomeCounts,
    val referenceEnvelopeDescription: String,
    val warmupComplete: Boolean,
    val timestamp: Instant,
    val signingAlgorithm: String = "Ed25519",
) {
    init {
        require(gamma in 0.0..1.0) { "gamma must be in [0, 1], got $gamma" }
    }
}

/**
 * A complete audit record written to the JSONL log. Contains the decision,
 * the inputs that produced it, and the attestation for tamper evidence.
 */
@Serializable
data class AuditRecord(
    val auditId: AuditId,
    val proposedAction: ProposedAction,
    val stateBefore: GovernanceState,
    val decision: GateDecision,
    val timestamp: Instant,
)

/**
 * A system-level event logged to the audit stream without going through
 * the kernel's decision path. Used for boot, shutdown, key rotation, and
 * accessibility observations.
 *
 * Distinguished from [AuditRecord] by the [type] field which is always
 * `"system_event"`. The reader uses this field to route parsing.
 *
 * Lives in `:core` (not `:audit`) because [AuditWriter] must reference it.
 */
@Serializable
data class SystemEventRecord(
    val type: String = "system_event",
    val timestamp: Instant,
    val kind: String,
    val message: String,
    val severity: Severity,
) {
    @Serializable
    enum class Severity { INFO, WARN, ERROR }
}
