package dev.governance.plan

import dev.governance.attestation.EphemeralKeyProvider
import dev.governance.core.Reversibility
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

class PlanDecisionLogTest : StringSpec({

    fun newSigner() = DefaultPlanDecisionSigner(EphemeralKeyProvider())

    fun samplePlan(intent: String = "test plan") = Plan(
        id = PlanId.new(),
        createdAt = Instant.parse("2026-05-14T11:00:00Z"),
        intent = intent,
        steps = listOf(
            PlanStep(
                index = StepIndex(0),
                kind = "noop",
                target = "target",
                reversibility = Reversibility.FullyReversible,
                tier = PlanTier.App,
            ),
        ),
    )

    val fixedClock = object : Clock {
        override fun now() = Instant.parse("2026-05-14T12:00:00Z")
    }

    "record-and-verify roundtrip on a temp file" {
        val signer = newSigner()
        val log = PlanDecisionLog(path = tempfile().toPath(), signer = signer)
        val evaluator = PlanEvaluator(clock = fixedClock)

        val plan = samplePlan()
        val signed = log.record(evaluator.evaluate(plan))

        signer.verify(signed) shouldBe true
        signed.result.plan.id shouldBe plan.id

        val verifications = log.verifyAll()
        verifications shouldHaveSize 1
        verifications[0].planId shouldBe plan.id
        verifications[0].valid shouldBe true
    }

    "verifyAll returns one valid result per recorded decision" {
        val signer = newSigner()
        val log = PlanDecisionLog(path = tempfile().toPath(), signer = signer)
        val evaluator = PlanEvaluator(clock = fixedClock)

        val plans = (1..5).map { samplePlan("plan $it") }
        plans.forEach { log.record(evaluator.evaluate(it)) }

        val verifications = log.verifyAll()
        verifications shouldHaveSize plans.size
        verifications.all { it.valid } shouldBe true
        verifications.map { it.planId } shouldBe plans.map { it.id }
    }
})
