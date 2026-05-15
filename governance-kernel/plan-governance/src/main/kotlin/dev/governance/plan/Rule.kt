package dev.governance.plan

import dev.governance.core.Reversibility

fun interface Rule {
    fun check(plan: Plan): RuleOutcome
}

sealed interface RuleOutcome {
    data object Pass : RuleOutcome
    data class Fail(val reason: String, val step: StepIndex? = null) : RuleOutcome
}

object Rules {

    val noRootSystemActions = Rule { plan ->
        plan.steps.firstOrNull { it.tier == PlanTier.RootSystem }
            ?.let { RuleOutcome.Fail("RootSystem tier requires human approval", it.index) }
            ?: RuleOutcome.Pass
    }

    val noIrreversibleSystemActions = Rule { plan ->
        plan.steps.firstOrNull { it.tier == PlanTier.System && it.reversibility == Reversibility.Irreversible }
            ?.let { RuleOutcome.Fail("Irreversible System actions are not permitted", it.index) }
            ?: RuleOutcome.Pass
    }

    val boundedPlanSize: Rule = boundedPlanSize(maxSteps = 20)

    fun boundedPlanSize(maxSteps: Int) = Rule { plan ->
        if (plan.steps.size > maxSteps)
            RuleOutcome.Fail("Plan exceeds maximum of $maxSteps steps")
        else RuleOutcome.Pass
    }

    fun kindBlocklist(blocked: Set<String>) = Rule { plan ->
        plan.steps.firstOrNull { it.kind in blocked }
            ?.let { RuleOutcome.Fail("Step kind '${it.kind}' is blocked", it.index) }
            ?: RuleOutcome.Pass
    }

    /**
     * The default rule set, walked in declared order by [PlanEvaluator].
     *
     * Order is part of the contract: evaluation short-circuits on the first
     * [RuleOutcome.Fail], so rules earlier in this list dominate later ones.
     */
    val standard: List<Rule> = listOf(
        noRootSystemActions,
        noIrreversibleSystemActions,
        boundedPlanSize,
    )
}
