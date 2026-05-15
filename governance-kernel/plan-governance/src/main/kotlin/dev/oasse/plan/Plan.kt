package dev.oasse.plan

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
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

@Serializable
enum class Reversibility { FullyReversible, PartiallyReversible, Irreversible }

@Serializable
enum class Tier { App, System, RootSystem }

@Serializable
data class PlanStep(
    val index: StepIndex,
    val kind: String,
    val target: String,
    val reversibility: Reversibility,
    val tier: Tier,
)

@Serializable
data class Plan(
    val id: PlanId,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
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

internal object InstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}
