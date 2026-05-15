package dev.governance.plan

import dev.governance.core.Reversibility
import kotlinx.datetime.Instant
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

@Serializable
@JvmInline
value class PlanId(@Serializable(with = UuidSerializer::class) val value: UUID) {
    companion object {
        fun new(): PlanId = PlanId(UUID.randomUUID())
    }
}

@Serializable
@JvmInline
value class StepIndex(val value: Int)

/**
 * Plan-level tier classification. Extends the kernel's two-tier [ActionTier]
 * model with an intermediate [System] tier for non-root system actions,
 * enabling finer-grained plan governance.
 */
@Serializable
enum class PlanTier { App, System, RootSystem }

@Serializable
data class PlanStep(
    val index: StepIndex,
    val kind: String,
    val target: String,
    val reversibility: Reversibility,
    val tier: PlanTier,
)

@Serializable
data class Plan(
    val id: PlanId,
    val createdAt: Instant,
    val intent: String,
    val steps: List<PlanStep>,
) {
    init {
        require(steps.isNotEmpty()) { "Plan must contain at least one step" }
        require(steps.mapIndexed { i, s -> s.index.value == i }.all { it }) {
            "Step indices must be contiguous starting at 0"
        }
    }
}

internal object UuidSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}
