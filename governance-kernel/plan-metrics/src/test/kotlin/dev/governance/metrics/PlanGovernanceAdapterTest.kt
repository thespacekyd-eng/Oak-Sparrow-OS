package dev.governance.metrics

import dev.governance.attestation.EphemeralKeyProvider
import dev.governance.core.Reversibility
import dev.governance.plan.DefaultPlanDecisionSigner
import dev.governance.plan.EvaluationResult
import dev.governance.plan.Plan
import dev.governance.plan.PlanEvaluator
import dev.governance.plan.PlanId
import dev.governance.plan.PlanStep
import dev.governance.plan.PlanTier
import dev.governance.plan.SignedDecision
import dev.governance.plan.StepIndex
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class PlanGovernanceAdapterTest : StringSpec({

    val fixedClock = object : Clock {
        override fun now() = Instant.parse("2026-05-16T12:00:00Z")
    }

    fun appPlan(steps: Int = 1) = Plan(
        id = PlanId.new(),
        createdAt = Instant.parse("2026-05-16T11:00:00Z"),
        intent = "app plan",
        steps = (0 until steps).map { i ->
            PlanStep(
                index = StepIndex(i),
                kind = "noop",
                target = "target-$i",
                reversibility = Reversibility.FullyReversible,
                tier = PlanTier.App,
            )
        },
    )

    fun rootPlan() = Plan(
        id = PlanId.new(),
        createdAt = Instant.parse("2026-05-16T11:00:00Z"),
        intent = "root plan",
        steps = listOf(
            PlanStep(
                index = StepIndex(0),
                kind = "format",
                target = "/dev/block/sda1",
                reversibility = Reversibility.FullyReversible,
                tier = PlanTier.RootSystem,
            ),
        ),
    )

    fun irreversibleSystemPlan() = Plan(
        id = PlanId.new(),
        createdAt = Instant.parse("2026-05-16T11:00:00Z"),
        intent = "irreversible system",
        steps = listOf(
            PlanStep(
                index = StepIndex(0),
                kind = "delete",
                target = "/data/system/secret",
                reversibility = Reversibility.Irreversible,
                tier = PlanTier.System,
            ),
        ),
    )

    fun writeSignedJsonl(
        path: Path,
        decisions: List<SignedDecision>,
        json: Json = PlanGovernanceAdapter.DEFAULT_JSON,
    ) {
        val lines = decisions.joinToString(separator = "\n") {
            json.encodeToString(SignedDecision.serializer(), it)
        } + "\n"
        Files.writeString(path, lines)
    }

    "full roundtrip: signed plan-governance decisions read back via adapter" {
        val signer = DefaultPlanDecisionSigner(EphemeralKeyProvider())
        val evaluator = PlanEvaluator(clock = fixedClock)

        val decisions = listOf(
            signer.sign(evaluator.evaluate(appPlan(steps = 3))),         // APPROVED
            signer.sign(evaluator.evaluate(rootPlan())),                 // REJECTED noRootSystemActions
            signer.sign(evaluator.evaluate(irreversibleSystemPlan())),   // REJECTED noIrreversibleSystemActions
            signer.sign(evaluator.evaluate(appPlan(steps = 1))),         // APPROVED
            signer.sign(evaluator.evaluate(rootPlan())),                 // REJECTED noRootSystemActions
        )

        val logFile = tempfile(prefix = "plan-decision-log", suffix = ".jsonl").toPath()
        writeSignedJsonl(logFile, decisions)

        val adapter = PlanGovernanceAdapter(signer = signer)
        val decisionLog = adapter.read(logFile)
        val metrics = GovernanceMetrics.from(decisionLog)

        metrics.totalDecisions shouldBe 5
        metrics.approvals shouldBe 2
        metrics.rejections shouldBe 3
        metrics.ruleTriggerFrequencies[Rules.NO_ROOT_SYSTEM_ACTIONS] shouldBe 2
        metrics.ruleTriggerFrequencies[Rules.NO_IRREVERSIBLE_SYSTEM_ACTIONS] shouldBe 1
        metrics.ruleTriggerFrequencies[Rules.BOUNDED_PLAN_SIZE] shouldBe 0
    }

    "reason strings exactly match the literals emitted by Rules.standard" {
        val signer = DefaultPlanDecisionSigner(EphemeralKeyProvider())
        val evaluator = PlanEvaluator(clock = fixedClock)

        val rootResult = evaluator.evaluate(rootPlan()) as EvaluationResult.Rejected
        val irreversibleResult = evaluator.evaluate(irreversibleSystemPlan()) as EvaluationResult.Rejected

        rootResult.reason shouldBe "RootSystem tier requires human approval"
        irreversibleResult.reason shouldBe "Irreversible System actions are not permitted"

        val logFile = tempfile(prefix = "plan-decision-log", suffix = ".jsonl").toPath()
        writeSignedJsonl(logFile, listOf(signer.sign(rootResult), signer.sign(irreversibleResult)))

        val decisionLog = PlanGovernanceAdapter(signer = signer).read(logFile)
        decisionLog.records.map { it.rejectedByRuleIds.single() } shouldBe listOf(
            Rules.NO_ROOT_SYSTEM_ACTIONS,
            Rules.NO_IRREVERSIBLE_SYSTEM_ACTIONS,
        )
    }

    "missing file returns an empty DecisionLog" {
        val signer = DefaultPlanDecisionSigner(EphemeralKeyProvider())
        val adapter = PlanGovernanceAdapter(signer = signer)

        val missing = Paths.get("does-not-exist-${System.nanoTime()}.jsonl")
        val log = adapter.read(missing)

        log.records.shouldBeEmpty()
    }
})

private fun List<*>.shouldBeEmpty() {
    if (isNotEmpty()) throw AssertionError("expected empty list but had $size element(s)")
}
