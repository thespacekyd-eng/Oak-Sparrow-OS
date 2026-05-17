package dev.governance.metrics

/**
 * Internal carrier types fed into [GovernanceMetricsCalculator].
 *
 * These are deliberately decoupled from [dev.governance.plan]'s
 * [EvaluationResult] / [SignedDecision] shape so the calculator stays
 * a pure aggregation primitive — adapters project external decision
 * sources into this shape via [PlanGovernanceAdapter].
 */
data class DecisionLog(
    val records: List<DecisionRecord>,
)

data class DecisionRecord(
    val id: String,
    val decision: GovernanceDecision,
    val planSize: Int,
    val rejectedByRuleIds: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(planSize >= 0) { "planSize must be non-negative" }
    }
}

enum class GovernanceDecision {
    APPROVED,
    REJECTED,
}
