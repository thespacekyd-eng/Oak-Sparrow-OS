package dev.governance.android.platform

import dev.governance.core.KeyProvider
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Software ECDSA P-256 key provider for environments where Ed25519 is
 * unavailable (emulators, older TEEs). NOT for production use on devices
 * that support Ed25519 — the weaker key type reduces the attestation
 * chain's cryptographic strength.
 *
 * // PHASE3-PRODUCTION-FOLLOWUP: Remove this fallback when targeting only
 * // devices with hardware Ed25519 TEE support. Production builds should
 * // fail loudly if Ed25519 is unavailable rather than silently downgrading.
 */
class SoftwareEcKeyProvider : KeyProvider {
    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    // PHASE2D-FOLLOWUP: This provider generates a fresh in-memory keypair on
    // every construction. Audit signatures from previous service sessions are
    // unverifiable after a service restart on emulator. Persistent EC storage
    // is a separate concern if emulator parity is needed.

    override fun publicKey(): ByteArray = keyPair.public.encoded

    override fun algorithmLabel(): String = "ECDSA P-256 (debug fallback)"

    override fun sign(message: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(message)
        return sig.sign()
    }
}
