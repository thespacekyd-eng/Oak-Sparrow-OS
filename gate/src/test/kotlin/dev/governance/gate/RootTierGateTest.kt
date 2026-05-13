package dev.governance.gate

import dev.governance.core.HardBarrier
import dev.governance.core.Outcome
import dev.governance.core.ProposedAction
import dev.governance.core.Reversibility
import dev.governance.testing.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Verifies the gate rule for root-tier action kinds: every kind in
 * `ActionTier.ROOT_SYSTEM_KINDS` produces HOLD regardless of γ,
 * regardless of warmup state, regardless of reversibility.
 *
 * The dispatcher additionally requires system-app placement to
 * actually execute the privileged operation (see RootCapabilityDispatcher),
 * but the gate's job is to ensure no such action ever PASSes silently.
 */
class RootTierGateTest : FunSpec({

    test("shell_exec at low gamma still HOLDs") {
        val result = CompositeGate.evaluate(
            gamma = 0.0, entropy = 0.0, divergence = 0.0,
            reversibility = Reversibility.FullyReversible,
            barriers = emptyList(),
            action = Fixtures.proposedAction(kind = "shell_exec"),
            state = Fixtures.defaultState(gamma = 0.0),
        )

        result.outcome shouldBe Outcome.HOLD
        result.rationale shouldContain "root-tier"
        result.rationale shouldContain "shell_exec"
    }

    test("package_install at low gamma still HOLDs") {
        val result = CompositeGate.evaluate(
            gamma = 0.0, entropy = 0.0, divergence = 0.0,
            reversibility = Reversibility.OneShot,
            barriers = emptyList(),
            action = Fixtures.proposedAction(kind = "package_install"),
            state = Fixtures.defaultState(gamma = 0.0),
        )

        result.outcome shouldBe Outcome.HOLD
        result.rationale shouldContain "root-tier"
    }

    test("settings_put at low gamma still HOLDs") {
        val result = CompositeGate.evaluate(
            gamma = 0.0, entropy = 0.0, divergence = 0.0,
            reversibility = Reversibility.FullyReversible,
            barriers = emptyList(),
            action = Fixtures.proposedAction(kind = "settings_put"),
            state = Fixtures.defaultState(gamma = 0.0),
        )

        result.outcome shouldBe Outcome.HOLD
    }

    test("network_control HOLDs even with FullyReversible") {
        val result = CompositeGate.evaluate(
            gamma = 0.0, entropy = 0.0, divergence = 0.0,
            reversibility = Reversibility.FullyReversible,
            barriers = emptyList(),
            action = Fixtures.proposedAction(kind = "network_control"),
            state = Fixtures.defaultState(gamma = 0.0),
        )

        result.outcome shouldBe Outcome.HOLD
    }

    test("hard barrier violation still wins over root-tier HOLD") {
        // VETO from a hard-barrier violation must take precedence over
        // the root-tier HOLD rule. Otherwise a bypass could turn VETOs
        // into HOLDs by forging a root-tier kind.
        class AlwaysViolated : HardBarrier {
            override val name = "always"
            override fun violated(action: ProposedAction, state: dev.governance.core.GovernanceState) = true
        }

        val result = CompositeGate.evaluate(
            gamma = 0.0, entropy = 0.0, divergence = 0.0,
            reversibility = Reversibility.FullyReversible,
            barriers = listOf(AlwaysViolated()),
            action = Fixtures.proposedAction(kind = "shell_exec"),
            state = Fixtures.defaultState(gamma = 0.0),
        )

        result.outcome shouldBe Outcome.VETO
    }

    test("App-tier kinds with low gamma still PASS as before") {
        // Smoke test: the new RootSystem rule must not regress App-tier
        // PASS behavior on identical inputs.
        val result = CompositeGate.evaluate(
            gamma = 0.0, entropy = 0.0, divergence = 0.0,
            reversibility = Reversibility.FullyReversible,
            barriers = emptyList(),
            action = Fixtures.proposedAction(kind = "read_calendar"),
            state = Fixtures.defaultState(gamma = 0.0),
        )

        result.outcome shouldBe Outcome.PASS
    }
})
