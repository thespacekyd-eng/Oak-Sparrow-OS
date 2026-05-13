package dev.governance.attestation

import dev.governance.core.KeyProvider
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * ECDSA P-256 key provider for testing the algorithm-agile verifier.
 * Mirrors [dev.governance.android.platform.SoftwareEcKeyProvider] but
 * lives in :attestation's test sources to avoid a dependency on :android-platform.
 */
class EcTestKeyProvider : KeyProvider {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    override fun publicKey(): ByteArray = keyPair.public.encoded

    override fun sign(message: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(message)
        return sig.sign()
    }
}
