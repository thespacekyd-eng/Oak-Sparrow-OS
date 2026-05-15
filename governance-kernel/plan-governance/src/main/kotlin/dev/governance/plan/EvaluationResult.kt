package dev.governance.plan

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface EvaluationResult {
    val plan: Plan
    val evaluatedAt: Instant

    @Serializable
    @SerialName("approved")
    data class Approved(
        override val plan: Plan,
        override val evaluatedAt: Instant,
    ) : EvaluationResult

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        override val plan: Plan,
        override val evaluatedAt: Instant,
        val reason: String,
        val failedStep: StepIndex?,
    ) : EvaluationResult
}
