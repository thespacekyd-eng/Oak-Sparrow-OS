package dev.oasse.plan

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64
import java.util.Objects

@Serializable
@JvmInline
value class Signature(@Serializable(with = Base64ByteArraySerializer::class) val bytes: ByteArray) {
    init { require(bytes.size == 64) { "Ed25519 signatures are 64 bytes" } }
}

/**
 * A governance decision with its Ed25519 attestation.
 *
 * `equals` / `hashCode` are overridden so [Signature]'s `ByteArray` compares
 * by content rather than reference identity — without this, two decisions
 * with byte-identical signatures from separate array instances would test
 * unequal. Round-tripping through serialization always produces a fresh
 * `ByteArray`, so this matters every time a [SignedDecision] is read back
 * from disk.
 */
@Serializable
data class SignedDecision(
    val result: EvaluationResult,
    val signature: Signature,
    val publicKeyFingerprint: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignedDecision) return false
        if (result != other.result) return false
        if (!signature.bytes.contentEquals(other.signature.bytes)) return false
        if (publicKeyFingerprint != other.publicKeyFingerprint) return false
        return true
    }

    override fun hashCode(): Int = Objects.hash(
        result,
        signature.bytes.contentHashCode(),
        publicKeyFingerprint,
    )
}

private object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("ByteArray.Base64", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    }
    override fun deserialize(decoder: Decoder): ByteArray {
        return Base64.getDecoder().decode(decoder.decodeString())
    }
}
