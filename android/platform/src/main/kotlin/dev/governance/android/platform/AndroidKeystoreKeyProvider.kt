package dev.governance.android.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.governance.core.KeyProvider
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature

/**
 * [KeyProvider] backed by the Android Keystore, producing Ed25519 signing keys.
 *
 * On first run, generates an Ed25519 key pair in the Keystore under [alias].
 * On subsequent runs, loads the existing key. The key is scoped to this app
 * and cannot be extracted from the Keystore.
 *
 * ## Authentication
 *
 * `setUserAuthenticationRequired(false)` is used because the kernel signs
 * many decisions per second. Prompting for biometrics on every signature
 * would be unusable.
 *
 * ## Key rotation
 *
 * Phase 2 ships with no key rotation. Rotation is a Phase 3 concern.
 * When rotation is implemented, the old public key must be retained so
 * audit verification can cover historical records signed with the old key.
 *
 * ## Error policy
 *
 * If the Keystore reports the key is missing or corrupted, this class
 * throws [IllegalStateException]. It does NOT silently regenerate the key,
 * because that would invalidate every previous audit attestation signed
 * with the old key.
 *
 * ## API level
 *
 * Ed25519 in Android Keystore requires API 33+. The project's minSdk is 33,
 * so no fallback path is needed. Dual-path crypto was rejected for trust
 * chain simplicity.
 *
 * @param alias Keystore alias for the governance signing key
 */
class AndroidKeystoreKeyProvider(
    private val alias: String = DEFAULT_ALIAS,
) : KeyProvider {

    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!keyStore.containsAlias(alias)) {
            generateKey()
        }
    }

    private fun generateKey() {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .build()

        val kpg = KeyPairGenerator.getInstance("Ed25519", "AndroidKeyStore")
        kpg.initialize(spec)
        kpg.generateKeyPair()
    }

    override fun publicKey(): ByteArray {
        val entry = keyStore.getEntry(alias, null)
            ?: throw IllegalStateException(
                "Governance signing key '$alias' not found in Keystore. " +
                    "Cannot regenerate — this would invalidate all prior audit attestations."
            )
        val keyEntry = entry as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("Keystore entry '$alias' is not a PrivateKeyEntry")
        return keyEntry.certificate.publicKey.encoded
    }

    override fun sign(message: ByteArray): ByteArray {
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException(
                "Governance signing key '$alias' missing or corrupted in Keystore."
            )
        val sig = Signature.getInstance("Ed25519")
        sig.initSign(entry.privateKey)
        sig.update(message)
        return sig.sign()
    }

    companion object {
        const val DEFAULT_ALIAS = "governance_ed25519_signing_key"
    }
}
