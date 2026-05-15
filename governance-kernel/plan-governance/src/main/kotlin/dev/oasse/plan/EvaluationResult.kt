package dev.oasse.plan

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
sealed interface EvaluationResult {
    val plan: Plan
    val evaluatedAt: Instant

    @Serializable
    @SerialName("approved")
    data class Approved(
        override val plan: Plan,
        @Serializable(with = InstantSerializer::class) override val evaluatedAt: Instant,
    ) : EvaluationResult

    @Serializable
    @SerialName("rejected")
    data class Rejected(
        override val plan: Plan,
        @Serializable(with = InstantSerializer::class) override val evaluatedAt: Instant,
        val reason: String,
        val failedStep: StepIndex?,
    ) : EvaluationResult
}
