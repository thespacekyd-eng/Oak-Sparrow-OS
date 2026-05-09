package dev.governance.android.app

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import dev.governance.android.platform.parcel.ProposedActionParcel
import dev.governance.attestation.AttestationVerifier
import dev.governance.core.*
import dev.governance.android.app.util.UiAutomatorExt
import dev.governance.android.app.util.UiAutomatorExt.waitForTextContaining
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
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

    // -- UI-level route tests --

    private val device by lazy { UiAutomatorExt.device() }

    private fun launchAuthActivity(decision: GateDecision) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val json = Json.encodeToString(GateDecision.serializer(), decision)
        val intent = Intent(context, AuthorizationActivity::class.java).apply {
            putExtra(AuthorizationActivity.EXTRA_DECISION_JSON, json)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    @Test
    fun tamperedDecisionRoutesToVerificationFailureScreen() {
        val decision = makeDecision()
        val sig = decision.attestation.signature
        val tamperedSig = sig.toCharArray().also {
            val mid = it.size / 2
            it[mid] = if (it[mid] == 'a') 'b' else 'a'
        }.concatToString()
        val tampered = decision.copy(
            attestation = decision.attestation.copy(signature = tamperedSig),
        )

        launchAuthActivity(tampered)

        // Should show verification failure, not the auth dialog
        device.waitForTextContaining("failed", 5000) shouldBe true
        device.waitForTextContaining("SYSTEM", 2000) shouldBe false

        device.pressBack()
    }

    @Test
    fun untamperedDecisionRoutesToAuthDialog() {
        val decision = makeDecision()

        launchAuthActivity(decision)

        // Should show auth dialog, not verification failure
        device.waitForTextContaining("SYSTEM", 5000) shouldBe true
        device.waitForTextContaining("failed", 2000) shouldBe false

        device.pressBack()
    }
}
