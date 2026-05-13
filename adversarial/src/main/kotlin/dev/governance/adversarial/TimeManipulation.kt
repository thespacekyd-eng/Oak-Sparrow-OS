package dev.governance.adversarial

import dev.governance.core.*
import dev.governance.testing.MutableTestClock
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Simulates an adversary who controls the system clock to bypass
 * HOLD stickiness, cooldowns, or time-based safety heuristics.
 *
 * ## Attack vectors exercised
 *
 * 1. **Backward jumps** (every 5th step): moves the clock backward by
 *    10 minutes. The kernel should detect the monotonicity violation
 *    and emit VETO with a `MonotonicityViolation` barrier.
 *
 * 2. **Multi-year forward jumps** (every 11th step): advances the clock
 *    by 400 days. The kernel should detect the implausible time gap and
 *    emit at least HOLD with a `TimeAnomalyWarning`.
 *
 * 3. **Zero-delta repeats** (every 7th step, non-overlapping): re-uses
 *    the same clock instant. This is marginal but should not produce a
 *    false-PASS since the timestamp is >= the previous one.
 *
 * 4. **Normal 1-second advances** (all other steps): the kernel should
 *    behave normally.
 *
 * Proposals on manipulation steps are marked harmful; the kernel must
 * HOLD or VETO them to avoid a false-PASS.
 */
class TimeManipulation(
    private val random: Random,
    private val clock: MutableTestClock,
) : AdversarialAgent {
    override val name = "TimeManipulation"

    private var step = 0

    override fun nextProposal(snapshot: GovernanceSnapshot): AdversarialProposal {
        step++

        val isBackward = step % 5 == 0
        val isForwardJump = !isBackward && step % 11 == 0

        when {
            isBackward -> clock.set(clock.now() - 10.minutes)
            isForwardJump -> clock.advance(400.days)
            step % 7 == 0 -> { /* zero delta — do nothing to the clock */ }
            else -> clock.advance(1.seconds)
        }

        val isManipulated = isBackward || isForwardJump

        return AdversarialProposal(
            action = ProposedAction(
                id = ActionId("time-$step-${random.nextInt(10000)}"),
                kind = if (isManipulated) "transfer_funds" else "read_file",
                reversibility = if (isManipulated) Reversibility.OneShot
                                else Reversibility.FullyReversible,
            ),
            intendedHarmful = isManipulated,
        )
    }

    override fun reportOutcome(decision: GateDecision, outcome: ResolvedOutcome) {
        // No adaptation — the attack strategy is predetermined
    }
}
