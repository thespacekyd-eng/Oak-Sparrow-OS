package dev.governance.gate

import dev.governance.core.*
import dev.governance.attestation.DecisionSigner
import dev.governance.attestation.EphemeralKeyProvider
import dev.governance.calibration.DefensivePriorCalibrator
import dev.governance.metrics.*
import dev.governance.testing.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.Clock
import java.io.StringWriter

class CompositeGateTest : FunSpec({

    fun buildKernel(): DefaultGovernanceKernel {
        val keyProvider = EphemeralKeyProvider()
        return DefaultGovernanceKernel(
            metrics = GateMetrics(
                dilationFactor = DefaultDilationFactor(),
                predictiveEntropy = DefaultPredictiveEntropy(),
                trajectoryDivergence = DefaultTrajectoryDivergence(),
            ),
            barriers = emptyList(),
            calibrator = DefensivePriorCalibrator(warmupThreshold = 100),
            keyProvider = keyProvider,
            auditWriter = dev.governance.audit.JsonlAuditWriter(StringWriter()),
        )
    }

    // A test barrier that always violates
    class AlwaysViolatedBarrier : HardBarrier {
        override val name = "always_violated"
        override fun violated(action: ProposedAction, state: GovernanceState) = true
    }

    // A test barrier that never violates
    class NeverViolatedBarrier : HardBarrier {
        override val name = "never_violated"
        override fun violated(action: ProposedAction, state: GovernanceState) = false
    }

    test("hard barrier violation forces VETO regardless of soft state") {
        val result = CompositeGate.evaluate(
            gamma = 0.0,  // very low gamma — would normally PASS
            entropy = 0.0,
            divergence = 0.0,
            reversibility = Reversibility.FullyReversible,
            barriers = listOf(AlwaysViolatedBarrier()),
            action = Fixtures.proposedAction(),
            state = Fixtures.defaultState(gamma = 0.0),
        )

        result.outcome shouldBe Outcome.VETO
        result.violatedBarriers shouldBe listOf("always_violated")
    }

    test("reversibility asymmetry: same soft state, different decisions") {
        val lowGammaState = Fixtures.stateWithHistory(gamma = 0.3, historySize = 20)

        val fullyReversible = CompositeGate.evaluate(
            gamma = 0.3, entropy = 0.2, divergence = 0.1,
            reversibility = Reversibility.FullyReversible,
            barriers = emptyList(),
            action = Fixtures.proposedAction(reversibility = Reversibility.FullyReversible),
            state = lowGammaState,
        )

        val irreversible = CompositeGate.evaluate(
            gamma = 0.3, entropy = 0.2, divergence = 0.1,
            reversibility = Reversibility.Irreversible,
            barriers = emptyList(),
            action = Fixtures.proposedAction(reversibility = Reversibility.Irreversible),
            state = lowGammaState,
        )

        fullyReversible.outcome shouldBe Outcome.PASS
        irreversible.outcome shouldBe Outcome.HOLD
    }

    test("high gamma + low divergence produces HOLD not VETO") {
        val result = CompositeGate.evaluate(
            gamma = 0.7, entropy = 0.3, divergence = 0.2,
            reversibility = Reversibility.FullyReversible,
            barriers = emptyList(),
            action = Fixtures.proposedAction(),
            state = Fixtures.defaultState(gamma = 0.7),
        )

        result.outcome shouldBe Outcome.HOLD
    }

    test("low gamma + low entropy + reversible produces PASS") {
        // Build state with low-gamma history so last entry isn't HOLD
        val history = (1..5).map {
            Fixtures.historyEntry(outcome = Outcome.PASS)
        }
        val state = Fixtures.defaultState(gamma = 0.2).copy(recentHistory = history)

        val result = CompositeGate.evaluate(
            gamma = 0.2, entropy = 0.1, divergence = 0.1,
            reversibility = Reversibility.FullyReversible,
            barriers = emptyList(),
            action = Fixtures.proposedAction(),
            state = state,
        )

        result.outcome shouldBe Outcome.PASS
    }

    test("HOLD is sticky for at least one cycle after recovery") {
        // State where last decision was HOLD
        val holdHistory = listOf(
            Fixtures.historyEntry(outcome = Outcome.HOLD)
        )
        val state = Fixtures.defaultState(gamma = 0.46).copy(recentHistory = holdHistory)

        // gamma=0.46 is below normal HOLD threshold (0.5) but above sticky threshold (0.45)
        val result = CompositeGate.evaluate(
            gamma = 0.46, entropy = 0.2, divergence = 0.1,
            reversibility = Reversibility.FullyReversible,
            barriers = emptyList(),
            action = Fixtures.proposedAction(),
            state = state,
        )

        // Should still HOLD due to stickiness
        result.outcome shouldBe Outcome.HOLD
    }

    test("HOLD stickiness does not persist after PASS cycle") {
        // State where last decision was PASS
        val passHistory = listOf(
            Fixtures.historyEntry(outcome = Outcome.PASS)
        )
        val state = Fixtures.defaultState(gamma = 0.46).copy(recentHistory = passHistory)

        val result = CompositeGate.evaluate(
            gamma = 0.46, entropy = 0.2, divergence = 0.1,
            reversibility = Reversibility.FullyReversible,
            barriers = emptyList(),
            action = Fixtures.proposedAction(),
            state = state,
        )

        // gamma=0.46 < 0.5 and no stickiness → PASS
        result.outcome shouldBe Outcome.PASS
    }

    test("Irreversible always HOLD even with perfect soft state") {
        val state = Fixtures.stateWithHistory(gamma = 0.05, historySize = 20)

        val result = CompositeGate.evaluate(
            gamma = 0.05, entropy = 0.01, divergence = 0.01,
            reversibility = Reversibility.Irreversible,
            barriers = emptyList(),
            action = Fixtures.proposedAction(reversibility = Reversibility.Irreversible),
            state = state,
        )

        result.outcome shouldBe Outcome.HOLD
    }

    test("OneShot with moderate gamma produces HOLD") {
        val state = Fixtures.stateWithHistory(gamma = 0.3, historySize = 10)

        val result = CompositeGate.evaluate(
            gamma = 0.3, entropy = 0.2, divergence = 0.1,
            reversibility = Reversibility.OneShot,
            barriers = emptyList(),
            action = Fixtures.proposedAction(reversibility = Reversibility.OneShot),
            state = state,
        )

        // effective_gamma = 0.3 + 0.25 = 0.55 >= 0.5 → HOLD
        result.outcome shouldBe Outcome.HOLD
    }

    test("high entropy triggers HOLD even with moderate gamma") {
        val state = Fixtures.stateWithHistory(gamma = 0.35, historySize = 10)

        val result = CompositeGate.evaluate(
            gamma = 0.35, entropy = 0.85, divergence = 0.1,
            reversibility = Reversibility.FullyReversible,
            barriers = emptyList(),
            action = Fixtures.proposedAction(),
            state = state,
        )

        // entropy=0.85 > 0.7, effective_gamma=0.35 >= 0.3 → HOLD
        result.outcome shouldBe Outcome.HOLD
    }

    test("full kernel decide produces valid attestation") {
        val kernel = buildKernel()
        val state = Fixtures.defaultState()
        val action = Fixtures.proposedAction()

        val decision = kernel.decide(state, action)
        decision.outcome shouldNotBe null
        decision.attestation.contentHash.isNotEmpty() shouldBe true
        decision.auditId.value shouldBe decision.attestation.contentHash

        // Verify attestation
        dev.governance.attestation.AttestationVerifier.verify(decision) shouldBe true
    }
})
