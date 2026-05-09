package dev.governance.android.app

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import dev.governance.android.platform.parcel.ProposedActionParcel
import dev.governance.android.platform.parcel.ResolvedOutcomeParcel
import dev.governance.core.*
import io.kotest.matchers.doubles.shouldBeBetween
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Rule
import org.junit.Test

/**
 * Tests kernel service lifecycle: snapshot validity and state continuity
 * across bind/unbind cycles.
 */
class KernelServiceLifecycleTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    @Test
    fun snapshotIsWellFormed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, GovernanceKernelService::class.java)
        val binder = serviceRule.bindService(intent)
        val kernel = AgentKernelInterface.Stub.asInterface(binder)

        val snapshot = kernel.snapshot().toKernel()

        snapshot.gamma.shouldBeBetween(0.0, 1.0, 0.0)
        snapshot.recentOutcomes shouldNotBe null
        snapshot.referenceEnvelopeDescription.isNotEmpty() shouldBe true
    }

    @Test
    fun statePreservedAcrossRebind() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, GovernanceKernelService::class.java)

        val binder1 = serviceRule.bindService(intent)
        val kernel1 = AgentKernelInterface.Stub.asInterface(binder1)

        // Send a decision and resolve it to change the kernel state
        val action = ProposedAction(
            id = ActionId("lifecycle-test-${System.nanoTime()}"),
            kind = "read_file",
            reversibility = Reversibility.FullyReversible,
        )
        val decision = kernel1.decide(ProposedActionParcel.from(action)).toKernel()
        kernel1.resolve(
            decision.auditId.value,
            ResolvedOutcomeParcel.from(ResolvedOutcome.BenignSuccess),
        )

        val snap1 = kernel1.snapshot().toKernel()

        // Unbind and rebind (service stays alive as foreground)
        serviceRule.unbindService()
        val binder2 = serviceRule.bindService(intent)
        val kernel2 = AgentKernelInterface.Stub.asInterface(binder2)

        val snap2 = kernel2.snapshot().toKernel()

        // State must be preserved across rebind
        snap2.gamma shouldBe snap1.gamma
    }
}
