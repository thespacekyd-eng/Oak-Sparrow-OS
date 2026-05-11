package dev.governance.android.app.agent

import dev.governance.core.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.hours

class GatePredictorTest : FunSpec({

    fun snapshot(
        gamma: Double = 0.2,
        entropy: Double = 0.3,
        divergence: Double = 0.1,
        warmupComplete: Boolean = true,
    ) = GovernanceSnapshot(
        gamma = gamma,
        recentEntropyAverage = entropy,
        recentDivergenceAverage = divergence,
        recentOutcomes = OutcomeCounts(pass = 10, hold = 1, veto = 0, window = 1.hours),
        referenceEnvelopeDescription = "test",
        warmupComplete = warmupComplete,
        timestamp = Clock.System.now(),
    )

    fun step(
        kind: String = "open_app",
        reversibility: Reversibility = Reversibility.FullyReversible,
    ) = PlannedStep(kind, "test", "test", reversibility)

    test("predicts PASS for low-gamma reversible app action") {
        val p = GatePredictor.predict(step(), snapshot(gamma = 0.1))
        p.outcome shouldBe Outcome.PASS
        p.confidence shouldBe GatePredictor.Confidence.HIGH
    }

    test("predicts HOLD for root-tier action regardless of gamma") {
        val p = GatePredictor.predict(step(kind = "shell_exec"), snapshot(gamma = 0.0))
        p.outcome shouldBe Outcome.HOLD
    }

    test("predicts HOLD for irreversible action") {
        val p = GatePredictor.predict(
            step(reversibility = Reversibility.Irreversible),
            snapshot(gamma = 0.0),
        )
        p.outcome shouldBe Outcome.HOLD
    }

    test("predicts VETO for extreme gamma") {
        val p = GatePredictor.predict(step(), snapshot(gamma = 0.96))
        p.outcome shouldBe Outcome.VETO
    }

    test("predicts HOLD for moderate gamma") {
        val p = GatePredictor.predict(step(), snapshot(gamma = 0.5))
        p.outcome shouldBe Outcome.HOLD
    }

    test("predicts HOLD for high entropy with non-trivial gamma") {
        val p = GatePredictor.predict(step(), snapshot(gamma = 0.15, entropy = 0.8))
        p.outcome shouldBe Outcome.HOLD
    }

    test("LOW confidence for borderline gamma") {
        // gamma=0.42 → margin=(0.5-0.42)/0.5=0.16 < 0.20 → LOW
        val p = GatePredictor.predict(step(), snapshot(gamma = 0.42))
        p.outcome shouldBe Outcome.PASS
        p.confidence shouldBe GatePredictor.Confidence.LOW
    }

    test("cost bias pushes share_to_social closer to HOLD than open_app") {
        val snap = snapshot(gamma = 0.3)
        val openP = GatePredictor.predict(step(kind = "open_app"), snap)
        val shareP = GatePredictor.predict(step(kind = "share_to_social_app"), snap)
        (shareP.effectiveGamma > openP.effectiveGamma) shouldBe true
    }

    // --- shouldInstantDispatch ---

    test("shouldInstantDispatch true for low-gamma reversible app action post-warmup") {
        GatePredictor.shouldInstantDispatch(step(), snapshot(gamma = 0.1)) shouldBe true
    }

    test("shouldInstantDispatch false during warmup") {
        GatePredictor.shouldInstantDispatch(
            step(), snapshot(gamma = 0.1, warmupComplete = false),
        ) shouldBe false
    }

    test("shouldInstantDispatch false for OneShot") {
        GatePredictor.shouldInstantDispatch(
            step(reversibility = Reversibility.OneShot),
            snapshot(gamma = 0.1),
        ) shouldBe false
    }

    test("shouldInstantDispatch false for borderline gamma") {
        // gamma=0.42 → margin=0.16 < 0.20 → LOW confidence → no instant
        GatePredictor.shouldInstantDispatch(step(), snapshot(gamma = 0.42)) shouldBe false
    }

    test("shouldInstantDispatch false for RootSystem kind") {
        GatePredictor.shouldInstantDispatch(
            step(kind = "shell_exec"), snapshot(gamma = 0.0),
        ) shouldBe false
    }
})
