package dev.governance.metrics

/**
 * Dependency-free self-checks for quick demo validation.
 *
 * These are not a replacement for the host project's test suite. They are
 * included so the stage/demo module can prove the obvious invariants without
 * pulling in a test framework. Demo data uses the three real rule IDs from
 * [Rules.standard]. Each rejection has exactly one rule ID because
 * [dev.governance.plan.PlanEvaluator] short-circuits on the first failure.
 */
object GovernanceMetricsSelfCheck {

    fun runAll() {
        computesExpectedMetrics()
        handlesEmptyLog()
        ignoresApprovedRecordRuleMetadata()
        ignoresUnknownRuleIds()
        keepsZeroFrequencyStandardRules()
        rendererIncludesCoreFields()
    }

    private fun computesExpectedMetrics() {
        val log = demoDecisionLog()
        val metrics = GovernanceMetrics.from(log)

        check(metrics.totalDecisions == 5) { "expected 5 total decisions" }
        check(metrics.approvals == 2) { "expected 2 approvals" }
        check(metrics.rejections == 3) { "expected 3 rejections" }
        check(close(metrics.approvalRate, 0.4)) { "expected approvalRate 0.4" }
        check(close(metrics.rejectionRate, 0.6)) { "expected rejectionRate 0.6" }
        check(close(metrics.averagePlanSize, 3.0)) { "expected averagePlanSize 3.0" }
        check(metrics.ruleTriggerFrequencies[Rules.NO_ROOT_SYSTEM_ACTIONS] == 2) {
            "expected noRootSystemActions frequency 2"
        }
        check(metrics.ruleTriggerFrequencies[Rules.NO_IRREVERSIBLE_SYSTEM_ACTIONS] == 1) {
            "expected noIrreversibleSystemActions frequency 1"
        }
        check(metrics.ruleTriggerFrequencies[Rules.BOUNDED_PLAN_SIZE] == 0) {
            "expected boundedPlanSize frequency 0"
        }
    }

    private fun handlesEmptyLog() {
        val metrics = GovernanceMetrics.from(DecisionLog(emptyList()))

        check(metrics.totalDecisions == 0) { "empty log total should be 0" }
        check(metrics.approvals == 0) { "empty log approvals should be 0" }
        check(metrics.rejections == 0) { "empty log rejections should be 0" }
        check(close(metrics.approvalRate, 0.0)) { "empty log approvalRate should be 0.0" }
        check(close(metrics.rejectionRate, 0.0)) { "empty log rejectionRate should be 0.0" }
        check(close(metrics.averagePlanSize, 0.0)) { "empty log averagePlanSize should be 0.0" }
    }

    private fun ignoresApprovedRecordRuleMetadata() {
        val log = DecisionLog(
            records = listOf(
                DecisionRecord(
                    id = "approved-with-debug-rule",
                    decision = GovernanceDecision.APPROVED,
                    planSize = 1,
                    rejectedByRuleIds = listOf(Rules.NO_ROOT_SYSTEM_ACTIONS),
                ),
            ),
        )

        val metrics = GovernanceMetrics.from(log)

        check(metrics.ruleTriggerFrequencies[Rules.NO_ROOT_SYSTEM_ACTIONS] == 0) {
            "approved records must not increment rejection rule frequencies"
        }
    }

    private fun ignoresUnknownRuleIds() {
        val log = DecisionLog(
            records = listOf(
                DecisionRecord(
                    id = "unknown-rule",
                    decision = GovernanceDecision.REJECTED,
                    planSize = 1,
                    rejectedByRuleIds = listOf("not_in_standard_rules"),
                ),
            ),
        )

        val metrics = GovernanceMetrics.from(log)

        check(metrics.ruleTriggerFrequencies.values.all { it == 0 }) {
            "unknown rule IDs should be ignored"
        }
    }

    private fun keepsZeroFrequencyStandardRules() {
        val metrics = GovernanceMetrics.from(DecisionLog(emptyList()))

        Rules.standard.forEach { rule ->
            check(metrics.ruleTriggerFrequencies.containsKey(rule.id)) {
                "missing zero-frequency standard rule: ${rule.id}"
            }
        }
    }

    private fun rendererIncludesCoreFields() {
        val metrics = GovernanceMetrics.from(demoDecisionLog())
        val rendered = GovernanceMetricsCli.render(metrics)

        check("Governance Metrics" in rendered) { "renderer missing title" }
        check("Total decisions" in rendered) { "renderer missing total decisions" }
        check("Approvals" in rendered) { "renderer missing approvals" }
        check("Rejections" in rendered) { "renderer missing rejections" }
        check("Rule-trigger frequencies" in rendered) { "renderer missing rule frequency section" }
        check(Rules.NO_IRREVERSIBLE_SYSTEM_ACTIONS in rendered) {
            "renderer missing the longest rule ID — alignment risk"
        }
    }

    fun demoDecisionLog(): DecisionLog {
        return DecisionLog(
            records = listOf(
                DecisionRecord(
                    id = "d-001",
                    decision = GovernanceDecision.APPROVED,
                    planSize = 3,
                ),
                DecisionRecord(
                    id = "d-002",
                    decision = GovernanceDecision.REJECTED,
                    planSize = 5,
                    rejectedByRuleIds = listOf(Rules.NO_ROOT_SYSTEM_ACTIONS),
                ),
                DecisionRecord(
                    id = "d-003",
                    decision = GovernanceDecision.REJECTED,
                    planSize = 2,
                    rejectedByRuleIds = listOf(Rules.NO_IRREVERSIBLE_SYSTEM_ACTIONS),
                ),
                DecisionRecord(
                    id = "d-004",
                    decision = GovernanceDecision.APPROVED,
                    planSize = 1,
                ),
                DecisionRecord(
                    id = "d-005",
                    decision = GovernanceDecision.REJECTED,
                    planSize = 4,
                    rejectedByRuleIds = listOf(Rules.NO_ROOT_SYSTEM_ACTIONS),
                ),
            ),
        )
    }

    private fun close(actual: Double, expected: Double, tolerance: Double = 0.000001): Boolean {
        return kotlin.math.abs(actual - expected) <= tolerance
    }
}
