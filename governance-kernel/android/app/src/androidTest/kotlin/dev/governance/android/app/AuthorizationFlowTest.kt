package dev.governance.android.app

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import dev.governance.android.platform.parcel.ProposedActionParcel
import dev.governance.core.*
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Rule
import org.junit.Test

/**
 * Tests that the kernel returns correct outcomes based on action
 * reversibility. Irreversible actions always HOLD (CompositeGate Rule 2).
 * Reversible actions on fresh defensive-prior state (gamma=0.85) also HOLD
 * because gamma exceeds the 0.5 threshold — but they must never VETO.
 */
class AuthorizationFlowTest {

    @get:Rule
    val serviceRule = ServiceTestRule()

    private fun getKernel(): AgentKernelInterface {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, GovernanceKernelService::class.java)
        val binder = serviceRule.bindService(intent)
        return AgentKernelInterface.Stub.asInterface(binder)
    }

    @Test
    fun irreversibleActionGetsHoldOrVeto() {
        val kernel = getKernel()

        val action = ProposedAction(
            id = ActionId("auth-hold-test"),
            kind = "delete_account",
            reversibility = Reversibility.Irreversible,
        )
        val decision = kernel.decide(ProposedActionParcel.from(action)).toKernel()

        // Irreversible → always HOLD per CompositeGate Rule 2
        // (could be VETO if gamma >= 0.95, but fresh state is 0.85)
        val holdOrVeto = decision.outcome == Outcome.HOLD || decision.outcome == Outcome.VETO
        holdOrVeto shouldBe true
    }

    @Test
    fun reversibleActionNeverVetoedOnDefensivePrior() {
        val kernel = getKernel()

        val action = ProposedAction(
            id = ActionId("auth-no-veto-test"),
            kind = "read_file",
            reversibility = Reversibility.FullyReversible,
        )
        val decision = kernel.decide(ProposedActionParcel.from(action)).toKernel()

        // FullyReversible bias=0.0, so effectiveGamma=gamma.
        // Fresh gamma 0.85 < VETO_THRESHOLD 0.95 → not VETO.
        // gamma 0.85 >= HOLD_THRESHOLD 0.5 → HOLD (expected on fresh state).
        // The important invariant: reversible reads are never vetoed.
        decision.outcome shouldNotBe Outcome.VETO
    }
}
