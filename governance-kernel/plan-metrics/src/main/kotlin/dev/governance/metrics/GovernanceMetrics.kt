package dev.governance.metrics

/**
 * Typed aggregate metrics for governance observability.
 *
 * All rates are simple ratios in the range [0.0, 1.0].
 */
data class GovernanceMetrics(
    val totalDecisions: Int,
    val approvals: Int,
    val rejections: Int,
    val approvalRate: Double,
    val rejectionRate: Double,
    val averagePlanSize: Double,
    val ruleTriggerFrequencies: Map<String, Int>,
) {
    init {
        require(totalDecisions >= 0) { "totalDecisions must be non-negative" }
        require(approvals >= 0) { "approvals must be non-negative" }
        require(rejections >= 0) { "rejections must be non-negative" }
        require(approvalRate in 0.0..1.0) { "approvalRate must be between 0.0 and 1.0" }
        require(rejectionRate in 0.0..1.0) { "rejectionRate must be between 0.0 and 1.0" }
        require(averagePlanSize >= 0.0) { "averagePlanSize must be non-negative" }
        require(ruleTriggerFrequencies.values.all { it >= 0 }) {
            "ruleTriggerFrequencies must not contain negative counts"
        }
    }

    companion object {
        fun from(
            decisionLog: DecisionLog,
            standardRules: List<GovernanceRule> = Rules.standard,
        ): GovernanceMetrics {
            return GovernanceMetricsCalculator.compute(decisionLog, standardRules)
        }
    }
}
