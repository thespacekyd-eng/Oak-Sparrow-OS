package dev.governance.metrics

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class GovernanceMetricsCalculatorTest : StringSpec({

    "computes expected aggregates over the demo log" {
        val metrics = GovernanceMetrics.from(GovernanceMetricsSelfCheck.demoDecisionLog())

        metrics.totalDecisions shouldBe 5
        metrics.approvals shouldBe 2
        metrics.rejections shouldBe 3
        metrics.approvalRate shouldBe (0.4 plusOrMinus 1e-9)
        metrics.rejectionRate shouldBe (0.6 plusOrMinus 1e-9)
        metrics.averagePlanSize shouldBe (3.0 plusOrMinus 1e-9)
        metrics.ruleTriggerFrequencies shouldContainExactly mapOf(
            Rules.NO_ROOT_SYSTEM_ACTIONS to 2,
            Rules.NO_IRREVERSIBLE_SYSTEM_ACTIONS to 1,
            Rules.BOUNDED_PLAN_SIZE to 0,
        )
    }

    "empty log produces zero metrics with standard rules seeded at 0" {
        val metrics = GovernanceMetrics.from(DecisionLog(emptyList()))

        metrics.totalDecisions shouldBe 0
        metrics.approvals shouldBe 0
        metrics.rejections shouldBe 0
        metrics.approvalRate shouldBe (0.0 plusOrMinus 1e-9)
        metrics.rejectionRate shouldBe (0.0 plusOrMinus 1e-9)
        metrics.averagePlanSize shouldBe (0.0 plusOrMinus 1e-9)
        metrics.ruleTriggerFrequencies shouldContainExactly mapOf(
            Rules.NO_ROOT_SYSTEM_ACTIONS to 0,
            Rules.NO_IRREVERSIBLE_SYSTEM_ACTIONS to 0,
            Rules.BOUNDED_PLAN_SIZE to 0,
        )
    }

    "approved records do not increment rejection rule frequencies" {
        val log = DecisionLog(
            records = listOf(
                DecisionRecord(
                    id = "a-with-bogus-rule",
                    decision = GovernanceDecision.APPROVED,
                    planSize = 2,
                    rejectedByRuleIds = listOf(Rules.NO_ROOT_SYSTEM_ACTIONS),
                ),
            ),
        )

        val metrics = GovernanceMetrics.from(log)

        metrics.approvals shouldBe 1
        metrics.rejections shouldBe 0
        metrics.ruleTriggerFrequencies[Rules.NO_ROOT_SYSTEM_ACTIONS] shouldBe 0
    }

    "unknown rule IDs on rejections are silently ignored" {
        val log = DecisionLog(
            records = listOf(
                DecisionRecord(
                    id = "r-unknown",
                    decision = GovernanceDecision.REJECTED,
                    planSize = 1,
                    rejectedByRuleIds = listOf("not_in_standard_rules"),
                ),
            ),
        )

        val metrics = GovernanceMetrics.from(log)

        metrics.rejections shouldBe 1
        metrics.ruleTriggerFrequencies.values.sum() shouldBe 0
    }

    "CLI output contains all three real rule IDs without truncation" {
        val metrics = GovernanceMetrics.from(GovernanceMetricsSelfCheck.demoDecisionLog())
        val rendered = GovernanceMetricsCli.render(metrics)

        rendered shouldContain "Governance Metrics"
        rendered shouldContain "Total decisions"
        rendered shouldContain Rules.NO_ROOT_SYSTEM_ACTIONS
        rendered shouldContain Rules.NO_IRREVERSIBLE_SYSTEM_ACTIONS
        rendered shouldContain Rules.BOUNDED_PLAN_SIZE
    }
})
