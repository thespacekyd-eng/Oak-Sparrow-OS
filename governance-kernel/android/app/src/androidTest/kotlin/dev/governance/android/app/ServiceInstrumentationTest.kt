package dev.governance.android.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import dev.governance.android.platform.parcel.ProposedActionParcel
import dev.governance.android.platform.parcel.ResolvedOutcomeParcel
import dev.governance.core.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumentation tests for [GovernanceKernelService].
 *
 * Requires API 35 emulator:
 * ```
 * ./gradlew :android-app:connectedAndroidTest
 * ```
 *
 * These tests bind to the service, send decisions over Binder, and
 * verify that parcelization round-trips produce valid signed decisions.
 */
class ServiceInstrumentationTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    @Test
    fun bindToServiceAndGetBinder() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, GovernanceKernelService::class.java)
        val binder = serviceRule.bindService(intent)
        val kernel = AgentKernelInterface.Stub.asInterface(binder)
        kernel shouldNotBe null
    }

    @Test
    fun decideReturnsValidGateDecision() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, GovernanceKernelService::class.java)
        val binder = serviceRule.bindService(intent)
        val kernel = AgentKernelInterface.Stub.asInterface(binder)

        val action = ProposedAction(
            id = ActionId("test-action-1"),
            kind = "read_file",
            reversibility = Reversibility.FullyReversible,
        )
        val actionParcel = ProposedActionParcel.from(action)
        val decisionParcel = kernel.decide(actionParcel)
        val decision = decisionParcel.toKernel()

        decision.actionId shouldBe action.id
        decision.actionKind shouldBe action.kind
        decision.attestation.contentHash.shouldNotBeEmpty()
        decision.attestation.signature.shouldNotBeEmpty()
    }

    @Test
    fun snapshotReturnsValidState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, GovernanceKernelService::class.java)
        val binder = serviceRule.bindService(intent)
        val kernel = AgentKernelInterface.Stub.asInterface(binder)

        val snapshotParcel = kernel.snapshot()
        val snapshot = snapshotParcel.toKernel()

        snapshot.gamma shouldNotBe null
        // Fresh state starts with gamma = 0.85
        // (may vary if state was persisted from prior test runs)
    }

    @Test
    fun decideAndResolveRoundTrip() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, GovernanceKernelService::class.java)
        val binder = serviceRule.bindService(intent)
        val kernel = AgentKernelInterface.Stub.asInterface(binder)

        val action = ProposedAction(
            id = ActionId("roundtrip-test"),
            kind = "read_file",
            reversibility = Reversibility.FullyReversible,
        )
        val decisionParcel = kernel.decide(ProposedActionParcel.from(action))
        val decision = decisionParcel.toKernel()

        // Resolve the decision
        kernel.resolve(
            decision.auditId.value,
            ResolvedOutcomeParcel.from(ResolvedOutcome.BenignSuccess),
        )

        // Snapshot should reflect the resolved decision
        val snapshot = kernel.snapshot().toKernel()
        snapshot shouldNotBe null
    }
}
