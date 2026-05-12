package dev.governance.attestation

import dev.governance.core.KeyProvider
import java.security.KeyPairGenerator
import java.security.Signature

/**
 * Ed25519 key provider that generates a fresh keypair on construction.
 * The keypair lives only in memory and is lost when the JVM exits.
 *
 * Phase 1 only — Phase 2 replaces this with an Android Keystore-backed provider.
 */
class EphemeralKeyProvider : KeyProvider {
    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    override fun publicKey(): ByteArray = keyPair.public.encoded

    override fun sign(message: ByteArray): ByteArray {
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(keyPair.private)
        sig.update(message)
        return sig.sign()
    }
}
