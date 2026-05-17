package dev.governance.metrics

import dev.governance.plan.EvaluationResult
import dev.governance.plan.PlanDecisionSigner
import dev.governance.plan.SignedDecision
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Read-only adapter from a real [dev.governance.plan.PlanDecisionLog] JSONL file
 * to the internal [DecisionLog] shape consumed by [GovernanceMetricsCalculator].
 *
 * Synchronous on purpose: bypasses [dev.governance.plan.PlanDecisionLog.record]'s
 * suspend wrapper by reading the JSONL file directly with the same [Json] config
 * plan-governance uses, then verifying via [PlanDecisionSigner] before projecting
 * into metric records.
 */
class PlanGovernanceAdapter(
    private val signer: PlanDecisionSigner,
    private val verifySignatures: Boolean = true,
    private val json: Json = DEFAULT_JSON,
) {

    fun read(path: Path): DecisionLog {
        if (!Files.exists(path)) return DecisionLog(emptyList())

        val records = Files.readAllLines(path)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line -> json.decodeFromString(SignedDecision.serializer(), line) }
            .filter { !verifySignatures || signer.verify(it) }
            .mapIndexed { index, signed -> toRecord(index, signed) }
            .toList()

        return DecisionLog(records)
    }

    private fun toRecord(index: Int, signed: SignedDecision): DecisionRecord {
        return when (val result = signed.result) {
            is EvaluationResult.Approved -> DecisionRecord(
                id = recordId(index, result.plan.id.value.toString()),
                decision = GovernanceDecision.APPROVED,
                planSize = result.plan.steps.size,
                rejectedByRuleIds = emptyList(),
            )
            is EvaluationResult.Rejected -> DecisionRecord(
                id = recordId(index, result.plan.id.value.toString()),
                decision = GovernanceDecision.REJECTED,
                planSize = result.plan.steps.size,
                rejectedByRuleIds = listOfNotNull(ruleIdForReason(result.reason)),
            )
        }
    }

    private fun recordId(index: Int, planId: String): String = "$index-$planId"

    /**
     * Maps the reason string emitted by PlanEvaluator back to a rule ID.
     *
     * Coupling: the substrings below MUST stay aligned with the literal reason
     * strings in `dev.governance.plan.Rules` (see plan-governance/src/main/kotlin/
     * dev/governance/plan/Rules.kt — `noRootSystemActions`,
     * `noIrreversibleSystemActions`, and `boundedPlanSize`). If those reasons
     * are reworded, update the matchers here in lock-step or this adapter will
     * silently start producing unknown rule IDs (which the calculator drops).
     */
    private fun ruleIdForReason(reason: String): String? = when {
        "RootSystem tier" in reason -> Rules.NO_ROOT_SYSTEM_ACTIONS
        "Irreversible System actions" in reason -> Rules.NO_IRREVERSIBLE_SYSTEM_ACTIONS
        "Plan exceeds maximum" in reason -> Rules.BOUNDED_PLAN_SIZE
        else -> null
    }

    companion object {
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
