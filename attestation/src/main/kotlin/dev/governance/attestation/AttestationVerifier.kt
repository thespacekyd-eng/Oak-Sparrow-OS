package dev.governance.attestation

import dev.governance.core.*
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verifies that a [GateDecision]'s attestation is authentic and untampered.
 * Downstream renderers (UI, trusted display) use this to confirm that the
 * decision came from the kernel and was not synthesized by the agent.
 */
object AttestationVerifier {

    /**
     * Verify a [GateDecision]'s attestation.
     *
     * 1. Rebuild the [PreDecision] from the decision's fields
     * 2. Canonical-JSON serialize and SHA-256 hash
     * 3. Verify the hash matches [DecisionAttestation.contentHash]
     * 4. Verify the Ed25519 signature over the contentHash bytes
     *
     * @return true if the attestation is valid and the decision is untampered
     */
    fun verify(decision: GateDecision): Boolean {
        val preDecision = PreDecision(
            outcome = decision.outcome,
            actionId = decision.actionId,
            actionKind = decision.actionKind,
            gamma = decision.gamma,
            entropy = decision.entropy,
            divergence = decision.divergence,
            reversibility = decision.reversibility,
            violatedBarriers = decision.violatedBarriers,
            rationale = decision.rationale,
            timestamp = decision.timestamp,
            sequenceNumber = decision.sequenceNumber,
        )

        val canonicalJson = CanonicalJson.encode(PreDecision.serializer(), preDecision)
        val expectedHash = sha256Hex(canonicalJson.encodeToByteArray())

        // Content hash must match
        if (decision.attestation.contentHash != expectedHash) return false

        // Verify Ed25519 signature
        return try {
            val contentHashBytes = decision.attestation.contentHash.hexToBytes()
            val signatureBytes = decision.attestation.signature.hexToBytes()
            val publicKeyBytes = decision.attestation.publicKey.hexToBytes()

            val keyFactory = KeyFactory.getInstance("Ed25519")
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes))

            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(publicKey)
            sig.update(contentHashBytes)
            sig.verify(signatureBytes)
        } catch (_: Exception) {
            false
        }
    }
}
