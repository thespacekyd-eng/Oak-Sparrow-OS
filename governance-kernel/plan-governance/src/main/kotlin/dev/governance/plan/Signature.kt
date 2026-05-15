package dev.governance.plan

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
 * A plan governance decision with its Ed25519 attestation.
 *
 * [publicKey] stores the hex-encoded X.509 SubjectPublicKeyInfo bytes of the
 * signing key, matching the convention used in the kernel's [DecisionAttestation].
 * This enables standalone verification without access to the original signer.
 */
@Serializable
data class SignedDecision(
    val result: EvaluationResult,
    val signature: Signature,
    val publicKey: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignedDecision) return false
        if (result != other.result) return false
        if (!signature.bytes.contentEquals(other.signature.bytes)) return false
        if (publicKey != other.publicKey) return false
        return true
    }

    override fun hashCode(): Int = Objects.hash(
        result,
        signature.bytes.contentHashCode(),
        publicKey,
    )
}

internal object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor = PrimitiveSerialDescriptor("ByteArray.Base64", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    }
    override fun deserialize(decoder: Decoder): ByteArray {
        return Base64.getDecoder().decode(decoder.decodeString())
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
