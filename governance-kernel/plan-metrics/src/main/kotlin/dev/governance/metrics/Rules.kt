package dev.governance.metrics

/**
 * The three real rule IDs that plan-metrics aggregates over.
 *
 * Names mirror the val names in [dev.governance.plan.Rules.standard]
 * so the metrics view lines up with what PlanEvaluator actually emits.
 */
data class GovernanceRule(
    val id: String,
    val label: String,
) {
    init {
        require(id.isNotBlank()) { "rule id must not be blank" }
        require(label.isNotBlank()) { "rule label must not be blank" }
    }
}

object Rules {
    const val NO_ROOT_SYSTEM_ACTIONS = "noRootSystemActions"
    const val NO_IRREVERSIBLE_SYSTEM_ACTIONS = "noIrreversibleSystemActions"
    const val BOUNDED_PLAN_SIZE = "boundedPlanSize"

    val standard: List<GovernanceRule> = listOf(
        GovernanceRule(NO_ROOT_SYSTEM_ACTIONS, "No RootSystem actions without approval"),
        GovernanceRule(NO_IRREVERSIBLE_SYSTEM_ACTIONS, "No irreversible System actions"),
        GovernanceRule(BOUNDED_PLAN_SIZE, "Plan size bounded"),
    )
}
