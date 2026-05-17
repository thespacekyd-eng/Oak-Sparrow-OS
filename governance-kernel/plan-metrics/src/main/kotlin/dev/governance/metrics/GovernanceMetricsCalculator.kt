package dev.governance.metrics

/**
 * Read-only metrics calculator over a [DecisionLog].
 *
 * This module does not mutate the log, classify new decisions, or enforce rules.
 */
object GovernanceMetricsCalculator {

    fun compute(
        decisionLog: DecisionLog,
        standardRules: List<GovernanceRule> = Rules.standard,
    ): GovernanceMetrics {
        val records = decisionLog.records
        val total = records.size

        val approvals = records.count { it.decision == GovernanceDecision.APPROVED }
        val rejections = records.count { it.decision == GovernanceDecision.REJECTED }

        val approvalRate = ratio(approvals, total)
        val rejectionRate = ratio(rejections, total)

        val averagePlanSize = if (total == 0) {
            0.0
        } else {
            records.sumOf { it.planSize }.toDouble() / total.toDouble()
        }

        val standardRuleIds = standardRules.map { it.id }.toSet()
        val frequencies = standardRules.associate { it.id to 0 }.toMutableMap()

        records
            .asSequence()
            .filter { it.decision == GovernanceDecision.REJECTED }
            .flatMap { it.rejectedByRuleIds.asSequence() }
            .filter { it in standardRuleIds }
            .forEach { ruleId ->
                frequencies[ruleId] = frequencies.getValue(ruleId) + 1
            }

        return GovernanceMetrics(
            totalDecisions = total,
            approvals = approvals,
            rejections = rejections,
            approvalRate = approvalRate,
            rejectionRate = rejectionRate,
            averagePlanSize = averagePlanSize,
            ruleTriggerFrequencies = frequencies.toMap(),
        )
    }

    private fun ratio(numerator: Int, denominator: Int): Double {
        return if (denominator == 0) 0.0 else numerator.toDouble() / denominator.toDouble()
    }
}
