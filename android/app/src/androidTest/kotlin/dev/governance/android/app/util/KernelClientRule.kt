package dev.governance.android.app.util

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import dev.governance.android.app.AgentKernelInterface
import dev.governance.android.app.GovernanceKernelService
import dev.governance.android.platform.parcel.ProposedActionParcel
import dev.governance.android.platform.parcel.ResolvedOutcomeParcel
import dev.governance.core.*
import org.junit.rules.ExternalResource

/**
 * JUnit rule that binds to [GovernanceKernelService] for the
 * duration of a test class. Provides typed helpers for
 * `decide()`, `resolve()`, and `snapshot()` without AIDL
 * boilerplate.
 *
 * Usage:
 * ```
 * @get:Rule val kernelRule = KernelClientRule()
 * ```
 */
class KernelClientRule : ExternalResource() {

    private val serviceRule = ServiceTestRule()
    lateinit var kernel: AgentKernelInterface
        private set

    override fun before() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, GovernanceKernelService::class.java)
        val binder = serviceRule.bindService(intent)
        kernel = AgentKernelInterface.Stub.asInterface(binder)
    }

    override fun after() {
        try { serviceRule.unbindService() } catch (_: Exception) {}
    }

    fun snapshot(): GovernanceSnapshot =
        kernel.snapshot().toKernel()

    fun decide(action: ProposedAction): GateDecision =
        kernel.decide(ProposedActionParcel.from(action)).toKernel()

    fun resolve(decision: GateDecision, outcome: ResolvedOutcome) {
        kernel.resolve(
            decision.auditId.value,
            ResolvedOutcomeParcel.from(outcome),
        )
    }
}
