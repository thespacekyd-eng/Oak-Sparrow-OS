package dev.governance.gate

import dev.governance.core.*
import dev.governance.testing.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class CostWeightedGateTest : FunSpec({

    fun passHistory() = (1..5).map { Fixtures.historyEntry(outcome = Outcome.PASS) }

    test("cost bias differentiates send_email from share_to_social at borderline gamma") {
        // At gamma=0.2 with OneShot:
        //   send_email:  bias = 0.25 + ln(2.5)*0.05 = 0.296 → effective 0.496 → PASS
        //   share_to_social: bias = 0.25 + ln(4.0)*0.05 = 0.319 → effective 0.519 → HOLD
        val state = Fixtures.defaultState(gamma = 0.2).copy(recentHistory = passHistory())

        val emailResult = CompositeGate.evaluate(
            gamma = 0.2, entropy = 0.1, divergence = 0.1,
            reversibility = Reversibility.OneShot,
            barriers = emptyList(),
            action = Fixtures.proposedAction(kind = "send_email", reversibility = Reversibility.OneShot),
            state = state,
        )

        val socialResult = CompositeGate.evaluate(
            gamma = 0.2, entropy = 0.1, divergence = 0.1,
            reversibility = Reversibility.OneShot,
            barriers = emptyList(),
            action = Fixtures.proposedAction(kind = "share_to_social_app", reversibility = Reversibility.OneShot),
            state = state,
        )

        emailResult.outcome shouldBe Outcome.PASS
        socialResult.outcome shouldBe Outcome.HOLD
    }

    test("read_calendar has zero cost bias") {
        val state = Fixtures.defaultState(gamma = 0.1).copy(recentHistory = passHistory())

        val result = CompositeGate.evaluate(
            gamma = 0.1, entropy = 0.1, divergence = 0.1,
            reversibility = Reversibility.FullyReversible,
            barriers = emptyList(),
            action = Fixtures.proposedAction(kind = "read_calendar"),
            state = state,
        )

        result.outcome shouldBe Outcome.PASS
        result.riskWeight shouldBe 1.0
    }

    test("risk weight is populated in GateResult") {
        val state = Fixtures.defaultState(gamma = 0.1).copy(recentHistory = passHistory())

        val result = CompositeGate.evaluate(
            gamma = 0.1, entropy = 0.1, divergence = 0.1,
            reversibility = Reversibility.OneShot,
            barriers = emptyList(),
            action = Fixtures.proposedAction(kind = "send_email", reversibility = Reversibility.OneShot),
            state = state,
        )

        result.riskWeight shouldBe 2.5
    }

    test("margins are computed in GateResult") {
        val state = Fixtures.defaultState(gamma = 0.3).copy(recentHistory = passHistory())

        val result = CompositeGate.evaluate(
            gamma = 0.3, entropy = 0.5, divergence = 0.2,
            reversibility = Reversibility.FullyReversible,
            barriers = emptyList(),
            action = Fixtures.proposedAction(kind = "read_calendar"),
            state = state,
        )

        result.margins.gammaToVeto shouldBeGreaterThan 0.0
        result.margins.gammaToHold shouldBeGreaterThan 0.0
        result.margins.entropyToHold shouldBeGreaterThan 0.0
        result.margins.divergenceToHold shouldBeGreaterThan 0.0
    }

    test("rationale includes cost bias breakdown") {
        val state = Fixtures.defaultState(gamma = 0.4).copy(recentHistory = passHistory())

        val result = CompositeGate.evaluate(
            gamma = 0.4, entropy = 0.1, divergence = 0.1,
            reversibility = Reversibility.OneShot,
            barriers = emptyList(),
            action = Fixtures.proposedAction(kind = "send_email", reversibility = Reversibility.OneShot),
            state = state,
        )

        // effective gamma = 0.4 + 0.25 + 0.046 = 0.696 → HOLD
        result.outcome shouldBe Outcome.HOLD
        result.rationale shouldContain "cost="
    }

    test("margins go negative on VETO path") {
        val state = Fixtures.defaultState(gamma = 0.9).copy(recentHistory = passHistory())

        val result = CompositeGate.evaluate(
            gamma = 0.9, entropy = 0.1, divergence = 0.1,
            reversibility = Reversibility.PartiallyReversible,
            barriers = emptyList(),
            action = Fixtures.proposedAction(
                kind = "share_to_social_app",
                reversibility = Reversibility.PartiallyReversible,
            ),
            state = state,
        )

        // effective gamma = 0.9 + 0.1 + 0.069 = ~1.0 → VETO
        result.outcome shouldBe Outcome.VETO
        result.margins.gammaToVeto shouldBeLessThan 0.0
        result.margins.overall shouldBeLessThan 0.0
    }
})
