package dev.governance.perception

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Result of executing a [NodeAction]. Serializable for audit logging —
 * every action outcome is recorded alongside the signed governance
 * decision that authorized it.
 */
@Serializable
sealed interface ActionOutcome {

    @Serializable @SerialName("success")
    data class Success(val description: String) : ActionOutcome

    @Serializable @SerialName("failed")
    data class Failed(val reason: String) : ActionOutcome

    @Serializable @SerialName("node_not_found")
    data class NodeNotFound(val elementIndex: Int) : ActionOutcome

    @Serializable @SerialName("service_unavailable")
    data object ServiceUnavailable : ActionOutcome
}
