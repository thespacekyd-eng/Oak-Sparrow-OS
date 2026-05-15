package dev.governance.plan

import dev.governance.core.Reversibility
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.datetime.Instant

class PlanEvaluatorPropertyTest : StringSpec({

    val planArb: Arb<Plan> = arbitrary {
        val size = Arb.int(1..20).bind()
        val steps = List(size) { i ->
            val tier = Arb.of(PlanTier.App, PlanTier.System).bind()
            val reversibility = if (tier == PlanTier.System) {
                Arb.of(Reversibility.FullyReversible, Reversibility.PartiallyReversible).bind()
            } else {
                Arb.enum<Reversibility>().bind()
            }
            PlanStep(
                index = StepIndex(i),
                kind = Arb.string(minSize = 1, maxSize = 20).bind(),
                target = Arb.string(minSize = 1, maxSize = 20).bind(),
                reversibility = reversibility,
                tier = tier,
            )
        }
        Plan(
            id = PlanId.new(),
            createdAt = Instant.parse("2026-05-14T11:00:00Z"),
            intent = Arb.string(minSize = 1, maxSize = 50).bind(),
            steps = steps,
        )
    }

    "any plan with no RootSystem, no Irreversible-System, and ≤20 steps approves under standard rules" {
        val evaluator = PlanEvaluator()
        checkAll(planArb) { plan ->
            val result = evaluator.evaluate(plan)
            result.shouldBeInstanceOf<EvaluationResult.Approved>()
        }
    }
})
