package dev.oasse.plan

import kotlinx.serialization.json.Json
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature as JdkSignature

interface DecisionSigner {
    fun sign(result: EvaluationResult): SignedDecision
    fun verify(decision: SignedDecision): Boolean
}

class Ed25519DecisionSigner(
    private val keyPair: KeyPair,
) : DecisionSigner {

    private val canonicalJson = Json {
        classDiscriminator = "kind"
        encodeDefaults = true
    }

    private val publicKeyFingerprint: String =
        MessageDigest.getInstance("SHA-256")
            .digest(keyPair.public.encoded)
            .copyOfRange(0, 8)
            .joinToString("") { "%02x".format(it) }

    override fun sign(result: EvaluationResult): SignedDecision {
        val canonical = encodeCanonical(result)
        val bytes = JdkSignature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(canonical)
            sign()
        }
        return SignedDecision(
            result = result,
            signature = Signature(bytes),
            publicKeyFingerprint = publicKeyFingerprint,
        )
    }

    override fun verify(decision: SignedDecision): Boolean {
        val canonical = encodeCanonical(decision.result)
        return JdkSignature.getInstance("Ed25519").run {
            initVerify(keyPair.public)
            update(canonical)
            verify(decision.signature.bytes)
        }
    }

    private fun encodeCanonical(result: EvaluationResult): ByteArray =
        canonicalJson
            .encodeToString(EvaluationResult.serializer(), result)
            .toByteArray(Charsets.UTF_8)
}
