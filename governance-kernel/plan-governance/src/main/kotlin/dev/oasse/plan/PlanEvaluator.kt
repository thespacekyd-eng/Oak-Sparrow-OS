package dev.oasse.plan

import java.time.Clock

class PlanEvaluator(
    private val rules: List<Rule> = Rules.standard,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun evaluate(plan: Plan): EvaluationResult {
        val now = clock.instant()
        for (rule in rules) {
            when (val outcome = rule.check(plan)) {
                is RuleOutcome.Pass -> continue
                is RuleOutcome.Fail -> return EvaluationResult.Rejected(
                    plan = plan,
                    evaluatedAt = now,
                    reason = outcome.reason,
                    failedStep = outcome.step,
                )
            }
        }
        return EvaluationResult.Approved(plan, now)
    }
}
