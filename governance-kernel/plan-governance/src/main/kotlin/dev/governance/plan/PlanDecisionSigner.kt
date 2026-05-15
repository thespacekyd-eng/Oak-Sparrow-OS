package dev.governance.plan

import dev.governance.attestation.CanonicalJson
import dev.governance.core.KeyProvider
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.security.Signature as JdkSignature

interface PlanDecisionSigner {
    fun sign(result: EvaluationResult): SignedDecision
    fun verify(decision: SignedDecision): Boolean
}

/**
 * Signs plan evaluation results using a [KeyProvider] from the kernel's
 * attestation layer, and canonicalizes JSON via [CanonicalJson] to ensure
 * deterministic signatures.
 */
class DefaultPlanDecisionSigner(
    private val keyProvider: KeyProvider,
) : PlanDecisionSigner {

    private val publicKeyHex: String = keyProvider.publicKey().toHex()

    override fun sign(result: EvaluationResult): SignedDecision {
        val canonical = encodeCanonical(result)
        val signatureBytes = keyProvider.sign(canonical)
        return SignedDecision(
            result = result,
            signature = Signature(signatureBytes),
            publicKey = publicKeyHex,
        )
    }

    override fun verify(decision: SignedDecision): Boolean {
        val canonical = encodeCanonical(decision.result)
        val pubKeySpec = X509EncodedKeySpec(decision.publicKey.hexToBytes())
        val pubKey = KeyFactory.getInstance("Ed25519").generatePublic(pubKeySpec)
        return JdkSignature.getInstance("Ed25519").run {
            initVerify(pubKey)
            update(canonical)
            verify(decision.signature.bytes)
        }
    }

    private fun encodeCanonical(result: EvaluationResult): ByteArray =
        CanonicalJson.encode(EvaluationResult.serializer(), result)
            .toByteArray(Charsets.UTF_8)
}
