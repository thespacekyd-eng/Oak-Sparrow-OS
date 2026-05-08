package dev.governance.attestation

import dev.governance.core.*
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * The subset of a [GateDecision] that is hashed and signed.
 * Excludes [GateDecision.auditId] and [GateDecision.attestation] to avoid
 * circular dependencies in the content hash.
 *
 * [sequenceNumber] is included to bind the attestation to a specific point
 * in the governance timeline. A replayed attestation with a stale sequence
 * number will produce a different content hash and fail verification.
 */
@Serializable
internal data class PreDecision(
    val outcome: Outcome,
    val actionId: ActionId,
    val actionKind: String,
    val gamma: Double,
    val entropy: Double,
    val divergence: Double,
    val reversibility: Reversibility,
    val violatedBarriers: List<String>,
    val rationale: String,
    val timestamp: Instant,
    val sequenceNumber: Long,
)

/**
 * Signs gate decisions to produce tamper-evident [DecisionAttestation]s.
 *
 * Flow:
 * 1. Build a [PreDecision] from the gate's computed values
 * 2. Serialize to canonical JSON (sorted keys, no whitespace)
 * 3. SHA-256 the canonical JSON bytes → contentHash
 * 4. Ed25519 sign the contentHash bytes → signature
 * 5. Assemble [GateDecision] with [AuditId] = contentHash and the attestation
 */
class DecisionSigner(private val keyProvider: KeyProvider) {

    fun sign(
        outcome: Outcome,
        actionId: ActionId,
        actionKind: String,
        gamma: Double,
        entropy: Double,
        divergence: Double,
        reversibility: Reversibility,
        violatedBarriers: List<String>,
        rationale: String,
        timestamp: Instant,
        sequenceNumber: Long,
    ): GateDecision {
        val preDecision = PreDecision(
            outcome = outcome,
            actionId = actionId,
            actionKind = actionKind,
            gamma = gamma,
            entropy = entropy,
            divergence = divergence,
            reversibility = reversibility,
            violatedBarriers = violatedBarriers,
            rationale = rationale,
            timestamp = timestamp,
            sequenceNumber = sequenceNumber,
        )

        val canonicalJson = CanonicalJson.encode(PreDecision.serializer(), preDecision)
        val contentHash = sha256Hex(canonicalJson.encodeToByteArray())
        val signature = keyProvider.sign(contentHash.hexToBytes())
        val publicKey = keyProvider.publicKey()

        val attestation = DecisionAttestation(
            contentHash = contentHash,
            signature = signature.toHex(),
            publicKey = publicKey.toHex(),
        )

        return GateDecision(
            outcome = outcome,
            actionId = actionId,
            actionKind = actionKind,
            gamma = gamma,
            entropy = entropy,
            divergence = divergence,
            reversibility = reversibility,
            violatedBarriers = violatedBarriers,
            auditId = AuditId(contentHash),
            rationale = rationale,
            attestation = attestation,
            timestamp = timestamp,
            sequenceNumber = sequenceNumber,
        )
    }
}

/** SHA-256 hash of [data], returned as a lowercase hex string. */
internal fun sha256Hex(data: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(data).toHex()
}
