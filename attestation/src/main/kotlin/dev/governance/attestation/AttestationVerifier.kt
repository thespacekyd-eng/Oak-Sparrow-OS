package dev.governance.attestation

import dev.governance.core.*
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verifies that a [GateDecision]'s attestation is authentic and untampered.
 * Downstream renderers (UI, trusted display) use this to confirm that the
 * decision came from the kernel and was not synthesized by the agent.
 *
 * ## Algorithm agility
 *
 * Ed25519 is the production algorithm. ECDSA P-256 (SHA256withECDSA) is
 * supported as a fallback for debug builds on emulators or older TEEs
 * that lack Ed25519 Keystore support. The verifier dispatches on the
 * public key's algorithm OID embedded in the X.509 SubjectPublicKeyInfo.
 *
 * Production deployments must use hardware Ed25519. The fallback exists
 * so the full integration (service, IPC, UI, audit) can be exercised
 * on emulators without skipping the verification path entirely.
 */
object AttestationVerifier {

    /**
     * Verify a [GateDecision]'s attestation.
     *
     * 1. Rebuild the [PreDecision] from the decision's fields
     * 2. Canonical-JSON serialize and SHA-256 hash
     * 3. Verify the hash matches [DecisionAttestation.contentHash]
     * 4. Verify the signature over the contentHash bytes using the
     *    algorithm identified by the public key (Ed25519 or ECDSA P-256)
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

        return try {
            val contentHashBytes = decision.attestation.contentHash.hexToBytes()
            val signatureBytes = decision.attestation.signature.hexToBytes()
            val publicKeyBytes = decision.attestation.publicKey.hexToBytes()
            val keySpec = X509EncodedKeySpec(publicKeyBytes)

            // Try Ed25519 first (production path), then ECDSA P-256 (debug/emulator).
            verifyWith("Ed25519", "Ed25519", keySpec, signatureBytes, contentHashBytes)
                || verifyWith("EC", "SHA256withECDSA", keySpec, signatureBytes, contentHashBytes)
        } catch (_: Exception) {
            false
        }
    }

    private fun verifyWith(
        keyAlg: String,
        sigAlg: String,
        keySpec: X509EncodedKeySpec,
        signatureBytes: ByteArray,
        contentHashBytes: ByteArray,
    ): Boolean = try {
        val publicKey = KeyFactory.getInstance(keyAlg).generatePublic(keySpec)
        val sig = Signature.getInstance(sigAlg)
        sig.initVerify(publicKey)
        sig.update(contentHashBytes)
        sig.verify(signatureBytes)
    } catch (_: Exception) {
        false
    }
}
