package dev.governance.android.app

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import dev.governance.android.platform.parcel.ProposedActionParcel
import dev.governance.attestation.AttestationVerifier
import dev.governance.core.*
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

/**
 * Tests that [AttestationVerifier] correctly distinguishes valid
 * from tampered decisions. This is the most security-critical
 * instrumented test.
 *
 * The verifier is algorithm-agile: it accepts both Ed25519 (production)
 * and ECDSA P-256 (emulator fallback). These tests run unconditionally
 * on all devices regardless of which key provider the service uses.
 */
class VerificationFailureRouteTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    private fun getKernel(): AgentKernelInterface {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, GovernanceKernelService::class.java)
        val binder = serviceRule.bindService(intent)
        return AgentKernelInterface.Stub.asInterface(binder)
    }

    private fun makeDecision(): GateDecision {
        val kernel = getKernel()
        val action = ProposedAction(
            id = ActionId("verify-test-${System.nanoTime()}"),
            kind = "read_file",
            reversibility = Reversibility.FullyReversible,
        )
        return kernel.decide(ProposedActionParcel.from(action)).toKernel()
    }

    @Test
    fun untamperedDecisionVerifies() {
        val decision = makeDecision()
        AttestationVerifier.verify(decision) shouldBe true
    }

    @Test
    fun tamperedSignatureFailsVerification() {
        val decision = makeDecision()

        val sig = decision.attestation.signature
        val tamperedSig = sig.toCharArray().also {
            val mid = it.size / 2
            it[mid] = if (it[mid] == 'a') 'b' else 'a'
        }.concatToString()

        val tampered = decision.copy(
            attestation = decision.attestation.copy(signature = tamperedSig)
        )

        AttestationVerifier.verify(tampered) shouldBe false
    }

    @Test
    fun tamperedContentHashFailsVerification() {
        val decision = makeDecision()

        val hash = decision.attestation.contentHash
        val tamperedHash = hash.toCharArray().also {
            val mid = it.size / 2
            it[mid] = if (it[mid] == '0') '1' else '0'
        }.concatToString()

        val tampered = decision.copy(
            attestation = decision.attestation.copy(contentHash = tamperedHash)
        )

        AttestationVerifier.verify(tampered) shouldBe false
    }
}
