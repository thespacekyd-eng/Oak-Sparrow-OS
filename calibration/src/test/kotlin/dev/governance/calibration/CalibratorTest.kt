package dev.governance.calibration

import dev.governance.core.*
import dev.governance.testing.Fixtures
import dev.governance.testing.PropertyGenerators
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.checkAll
import kotlinx.datetime.Clock

class CalibratorTest : FunSpec({

    fun makeDecision(
        outcome: Outcome = Outcome.PASS,
        gamma: Double = 0.5,
        actionId: String = "test",
        actionKind: String = "read_file",
        sequenceNumber: Long = 0L,
    ): GateDecision = GateDecision(
        outcome = outcome,
        actionId = ActionId(actionId),
        actionKind = actionKind,
        gamma = gamma,
        entropy = 0.3,
        divergence = 0.1,
        reversibility = Reversibility.FullyReversible,
        violatedBarriers = emptyList(),
        auditId = AuditId("fake-audit-id"),
        rationale = "test decision",
        attestation = DecisionAttestation(
            contentHash = "fakehash",
            signature = "fakesig",
            publicKey = "fakekey",
        ),
        timestamp = Clock.System.now(),
        sequenceNumber = sequenceNumber,
    )

    test("starts in warmup mode") {
        val calibrator = DefensivePriorCalibrator(warmupThreshold = 100)
        val state = Fixtures.defaultState(gamma = 0.85)
        calibrator.mode(state).shouldBeInstanceOf<CalibratorMode.Warmup>()
        (calibrator.mode(state) as CalibratorMode.Warmup).decisionsRemaining shouldBe 100
    }

    test("transitions to steady mode after warmupThreshold decisions") {
        val threshold = 10
        val calibrator = DefensivePriorCalibrator(warmupThreshold = threshold)
        var state = Fixtures.defaultState(gamma = 0.85)

        repeat(threshold) {
            val decision = makeDecision(actionId = "warmup-$it")
            state = calibrator.update(state, decision, ResolvedOutcome.BenignSuccess)
        }

        calibrator.mode(state) shouldBe CalibratorMode.Steady
    }

    test("warmup uses larger updates than steady mode - measurable difference") {
        val warmupCalibrator = DefensivePriorCalibrator(warmupThreshold = 1000)
        val steadyCalibrator = DefensivePriorCalibrator(warmupThreshold = 0) // immediate steady

        val state = Fixtures.defaultState(gamma = 0.5)
        val decision = makeDecision()

        val warmupState = warmupCalibrator.update(state, decision, ResolvedOutcome.BenignSuccess)
        val steadyState = steadyCalibrator.update(state, decision, ResolvedOutcome.BenignSuccess)

        // Warmup should decrease gamma more than steady
        val warmupDelta = state.gamma - warmupState.gamma
        val steadyDelta = state.gamma - steadyState.gamma
        warmupDelta shouldBeGreaterThan steadyDelta
    }

    test("single benign success bounded in steady mode") {
        val maxStep = 0.005
        val calibrator = DefensivePriorCalibrator(
            warmupThreshold = 0, // immediate steady
            steadyBenignStep = maxStep,
        )

        checkAll(200, PropertyGenerators.arbGamma()) { gamma ->
            val state = Fixtures.defaultState(gamma = gamma).copy(decisionsObserved = 100)
            val decision = makeDecision(gamma = gamma)
            val newState = calibrator.update(state, decision, ResolvedOutcome.BenignSuccess)
            val delta = state.gamma - newState.gamma
            delta shouldBeLessThanOrEqual maxStep + 1e-10
            delta shouldBeGreaterThanOrEqual 0.0 - 1e-10
        }
    }

    test("single flagged outcome bounded in steady mode") {
        val minStep = 0.08
        val calibrator = DefensivePriorCalibrator(
            warmupThreshold = 0,
            steadyFlaggedStep = minStep,
        )

        checkAll(200, PropertyGenerators.arbGamma()) { gamma ->
            val state = Fixtures.defaultState(gamma = gamma).copy(decisionsObserved = 100)
            val decision = makeDecision(gamma = gamma)
            val newState = calibrator.update(state, decision, ResolvedOutcome.Flagged("test"))
            val delta = newState.gamma - state.gamma
            // Delta should be at least the configured step (unless gamma was already at ceiling)
            if (state.gamma < 1.0 - minStep) {
                delta shouldBeGreaterThanOrEqual minStep - 1e-10
            }
        }
    }

    test("gamma stays in [0, 1] under any sequence of outcomes - property test") {
        val calibrator = DefensivePriorCalibrator(warmupThreshold = 10)

        checkAll(200, PropertyGenerators.arbResolvedOutcomeSequence(20)) { outcomes ->
            var state = Fixtures.defaultState(gamma = 0.85)
            for ((i, outcome) in outcomes.withIndex()) {
                val decision = makeDecision(gamma = state.gamma, actionId = "prop-$i")
                state = calibrator.update(state, decision, outcome)
                state.gamma shouldBeGreaterThanOrEqual 0.0
                state.gamma shouldBeLessThanOrEqual 1.0
            }
        }
    }

    test("sequence of Flagged outcomes monotonically raises gamma") {
        val calibrator = DefensivePriorCalibrator(warmupThreshold = 0)
        var state = Fixtures.defaultState(gamma = 0.3).copy(decisionsObserved = 100)

        repeat(20) { i ->
            val prevGamma = state.gamma
            val decision = makeDecision(gamma = state.gamma, actionId = "flagged-$i")
            state = calibrator.update(state, decision, ResolvedOutcome.Flagged("incident $i"))
            if (prevGamma < 1.0) {
                state.gamma shouldBeGreaterThan prevGamma - 1e-10
            }
        }
    }

    test("conservative asymmetry: flagged moves gamma more than benign") {
        val calibrator = DefensivePriorCalibrator(warmupThreshold = 0)
        val state = Fixtures.defaultState(gamma = 0.5).copy(decisionsObserved = 100)
        val decision = makeDecision(gamma = 0.5)

        val afterBenign = calibrator.update(state, decision, ResolvedOutcome.BenignSuccess)
        val afterFlagged = calibrator.update(state, decision, ResolvedOutcome.Flagged("test"))

        val benignDelta = (state.gamma - afterBenign.gamma)
        val flaggedDelta = (afterFlagged.gamma - state.gamma)
        flaggedDelta shouldBeGreaterThan benignDelta
    }

    test("concurrent update calls produce valid serial outcomes (no mutable state races)") {
        val calibrator = DefensivePriorCalibrator(warmupThreshold = 100)
        val state = Fixtures.defaultState(gamma = 0.5)
        val decision = makeDecision()

        // Compute the two valid serial outcomes
        val validBenign = calibrator.update(state, decision, ResolvedOutcome.BenignSuccess)
        val validFlagged = calibrator.update(state, decision, ResolvedOutcome.Flagged("test"))

        val results = java.util.concurrent.ConcurrentLinkedQueue<GovernanceState>()
        val threads = (1..100).map { i ->
            Thread {
                val outcome = if (i % 2 == 0) ResolvedOutcome.BenignSuccess
                              else ResolvedOutcome.Flagged("test")
                results.add(calibrator.update(state, decision, outcome))
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        results.forEach { result ->
            val isValid = result.gamma == validBenign.gamma || result.gamma == validFlagged.gamma
            isValid shouldBe true
        }
    }
})
