package dev.oasse.plan

import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.security.KeyPairGenerator
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DecisionLogTest : StringSpec({

    fun newSigner() = Ed25519DecisionSigner(
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    )

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
                tier = Tier.App,
            ),
        ),
    )

    val fixedClock = Clock.fixed(Instant.parse("2026-05-14T12:00:00Z"), ZoneOffset.UTC)

    "record-and-verify roundtrip on a temp file" {
        val signer = newSigner()
        val log = DecisionLog(path = tempfile().toPath(), signer = signer)
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
        val log = DecisionLog(path = tempfile().toPath(), signer = signer)
        val evaluator = PlanEvaluator(clock = fixedClock)

        val plans = (1..5).map { samplePlan("plan $it") }
        plans.forEach { log.record(evaluator.evaluate(it)) }

        val verifications = log.verifyAll()
        verifications shouldHaveSize plans.size
        verifications.all { it.valid } shouldBe true
        verifications.map { it.planId } shouldBe plans.map { it.id }
    }
})
