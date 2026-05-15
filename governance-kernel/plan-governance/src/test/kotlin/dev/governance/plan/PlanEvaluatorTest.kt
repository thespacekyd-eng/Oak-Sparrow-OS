package dev.governance.plan

import dev.governance.core.Reversibility
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class PlanEvaluatorTest : StringSpec({

    val fixedNow = Instant.parse("2026-05-14T12:00:00Z")
    val fixedClock = object : Clock {
        override fun now() = fixedNow
    }

    "approves a clean App-tier plan" {
        val plan = planOf(appStep(0), appStep(1))
        val result = PlanEvaluator(clock = fixedClock).evaluate(plan)

        result.shouldBeInstanceOf<EvaluationResult.Approved>()
        result.plan shouldBe plan
        result.evaluatedAt shouldBe fixedNow
    }

    "rejects any plan with a RootSystem step" {
        val plan = planOf(appStep(0), rootStep(1))
        val result = PlanEvaluator(clock = fixedClock).evaluate(plan)

        result.shouldBeInstanceOf<EvaluationResult.Rejected>()
        result.reason shouldContain "RootSystem"
        result.failedStep shouldBe StepIndex(1)
    }

    "rejects Irreversible System actions" {
        val plan = planOf(appStep(0), irreversibleSystemStep(1))
        val result = PlanEvaluator(clock = fixedClock).evaluate(plan)

        result.shouldBeInstanceOf<EvaluationResult.Rejected>()
        result.reason shouldContain "Irreversible System"
        result.failedStep shouldBe StepIndex(1)
    }

    "rejects plans larger than the configured max" {
        val plan = planOf(appStep(0), appStep(1), appStep(2), appStep(3))
        val rules = listOf(Rules.boundedPlanSize(maxSteps = 3))
        val result = PlanEvaluator(rules = rules, clock = fixedClock).evaluate(plan)

        result.shouldBeInstanceOf<EvaluationResult.Rejected>()
        result.reason shouldContain "maximum of 3"
        result.failedStep shouldBe null
    }

    "rejects blocked step kinds and reports the failing index" {
        val plan = planOf(appStep(0), appStep(1, kind = "delete_data"), appStep(2))
        val rules = listOf(Rules.kindBlocklist(setOf("delete_data")))
        val result = PlanEvaluator(rules = rules, clock = fixedClock).evaluate(plan)

        result.shouldBeInstanceOf<EvaluationResult.Rejected>()
        result.reason shouldContain "delete_data"
        result.failedStep shouldBe StepIndex(1)
    }

    "applies rules in order and reports the first failure only" {
        val steps = (0..20).map { appStep(it) } + rootStep(21)
        val plan = planOf(*steps.toTypedArray())
        val result = PlanEvaluator(clock = fixedClock).evaluate(plan)

        result.shouldBeInstanceOf<EvaluationResult.Rejected>()
        result.reason shouldContain "RootSystem"
        result.failedStep shouldBe StepIndex(21)
    }
})

private fun planOf(vararg steps: PlanStep): Plan = Plan(
    id = PlanId.new(),
    createdAt = Instant.parse("2026-05-14T11:00:00Z"),
    intent = "test plan",
    steps = steps.toList(),
)

private fun appStep(i: Int, kind: String = "noop"): PlanStep = PlanStep(
    index = StepIndex(i),
    kind = kind,
    target = "target-$i",
    reversibility = Reversibility.FullyReversible,
    tier = PlanTier.App,
)

private fun rootStep(i: Int): PlanStep = PlanStep(
    index = StepIndex(i),
    kind = "root_op",
    target = "target-$i",
    reversibility = Reversibility.FullyReversible,
    tier = PlanTier.RootSystem,
)

private fun irreversibleSystemStep(i: Int): PlanStep = PlanStep(
    index = StepIndex(i),
    kind = "sys_op",
    target = "target-$i",
    reversibility = Reversibility.Irreversible,
    tier = PlanTier.System,
)
