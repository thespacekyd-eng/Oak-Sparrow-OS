package dev.governance.android.app

import dev.governance.android.platform.AndroidKeystoreKeyProvider
import io.kotest.matchers.shouldBe
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Tests that the Android Keystore key provider returns stable keys
 * across constructions with the same alias, and distinct keys for
 * different aliases.
 *
 * These tests exercise the Keystore-Ed25519 path specifically and are
 * skipped on emulators that lack Ed25519 support. The EC P-256 debug
 * fallback (SoftwareEcKeyProvider) intentionally has NO key continuity —
 * it generates a fresh in-memory keypair on every construction. This
 * means audit signatures from previous service sessions are unverifiable
 * after a service restart on emulator. Persistent EC storage is a
 * Phase 3 concern if emulator parity is needed.
 */
class KeystoreContinuityTest {

    private fun assumeEd25519Supported() {
        try {
            val alias = "test_probe_${System.nanoTime()}"
            AndroidKeystoreKeyProvider(alias)
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry(alias)
        } catch (e: Exception) {
            assumeTrue(
                "Ed25519 not supported in AndroidKeyStore on this device (${e.message})",
                false
            )
        }
    }

    @Test
    fun sameAliasReturnsSamePublicKey() {
        assumeEd25519Supported()

        val alias = "test_continuity_${System.nanoTime()}"
        try {
            val provider1 = AndroidKeystoreKeyProvider(alias)
            val key1 = provider1.publicKey()

            val provider2 = AndroidKeystoreKeyProvider(alias)
            val key2 = provider2.publicKey()

            key1.contentEquals(key2) shouldBe true
        } finally {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry(alias)
        }
    }

    @Test
    fun differentAliasReturnsDifferentPublicKey() {
        assumeEd25519Supported()

        val alias1 = "test_distinct_a_${System.nanoTime()}"
        val alias2 = "test_distinct_b_${System.nanoTime()}"

        try {
            val key1 = AndroidKeystoreKeyProvider(alias1).publicKey()
            val key2 = AndroidKeystoreKeyProvider(alias2).publicKey()

            key1.contentEquals(key2) shouldBe false
        } finally {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry(alias1)
            ks.deleteEntry(alias2)
        }
    }
}
