package dev.governance.android.platform.parcel

import android.os.Parcel
import android.os.Parcelable
import dev.governance.core.*
import kotlinx.serialization.json.Json

/**
 * Parcelable wrappers for kernel data classes, enabling IPC over Binder.
 *
 * Strategy: serialize kernel types to JSON strings for parceling. This
 * guarantees lossless round-trip because the kernel data classes are all
 * `@Serializable`. The JSON overhead is negligible for individual decisions.
 *
 * IMPORTANT: The wrappers include [DecisionAttestation] exactly as-is.
 * They do NOT re-sign on the wrapper boundary, because that would defeat
 * the attestation chain from kernel to consumer.
 */

private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

// ---------------------------------------------------------------------------
// ProposedActionParcel
// ---------------------------------------------------------------------------

data class ProposedActionParcel(val serialized: String) : Parcelable {

    fun toKernel(): ProposedAction =
        json.decodeFromString(ProposedAction.serializer(), serialized)

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(serialized)
    }

    override fun describeContents(): Int = 0

    companion object {
        fun from(action: ProposedAction): ProposedActionParcel =
            ProposedActionParcel(json.encodeToString(ProposedAction.serializer(), action))

        @JvmField
        val CREATOR = object : Parcelable.Creator<ProposedActionParcel> {
            override fun createFromParcel(source: Parcel) =
                ProposedActionParcel(source.readString()!!)
            override fun newArray(size: Int) = arrayOfNulls<ProposedActionParcel>(size)
        }
    }
}

// ---------------------------------------------------------------------------
// GateDecisionParcel
// ---------------------------------------------------------------------------

data class GateDecisionParcel(val serialized: String) : Parcelable {

    fun toKernel(): GateDecision =
        json.decodeFromString(GateDecision.serializer(), serialized)

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(serialized)
    }

    override fun describeContents(): Int = 0

    companion object {
        fun from(decision: GateDecision): GateDecisionParcel =
            GateDecisionParcel(json.encodeToString(GateDecision.serializer(), decision))

        @JvmField
        val CREATOR = object : Parcelable.Creator<GateDecisionParcel> {
            override fun createFromParcel(source: Parcel) =
                GateDecisionParcel(source.readString()!!)
            override fun newArray(size: Int) = arrayOfNulls<GateDecisionParcel>(size)
        }
    }
}

// ---------------------------------------------------------------------------
// GovernanceSnapshotParcel
// ---------------------------------------------------------------------------

data class GovernanceSnapshotParcel(val serialized: String) : Parcelable {

    fun toKernel(): GovernanceSnapshot =
        json.decodeFromString(GovernanceSnapshot.serializer(), serialized)

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(serialized)
    }

    override fun describeContents(): Int = 0

    companion object {
        fun from(snapshot: GovernanceSnapshot): GovernanceSnapshotParcel =
            GovernanceSnapshotParcel(json.encodeToString(GovernanceSnapshot.serializer(), snapshot))

        @JvmField
        val CREATOR = object : Parcelable.Creator<GovernanceSnapshotParcel> {
            override fun createFromParcel(source: Parcel) =
                GovernanceSnapshotParcel(source.readString()!!)
            override fun newArray(size: Int) = arrayOfNulls<GovernanceSnapshotParcel>(size)
        }
    }
}

// ---------------------------------------------------------------------------
// ResolvedOutcomeParcel
// ---------------------------------------------------------------------------

data class ResolvedOutcomeParcel(val serialized: String) : Parcelable {

    fun toKernel(): ResolvedOutcome =
        json.decodeFromString(ResolvedOutcome.serializer(), serialized)

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(serialized)
    }

    override fun describeContents(): Int = 0

    companion object {
        fun from(outcome: ResolvedOutcome): ResolvedOutcomeParcel =
            ResolvedOutcomeParcel(json.encodeToString(ResolvedOutcome.serializer(), outcome))

        @JvmField
        val CREATOR = object : Parcelable.Creator<ResolvedOutcomeParcel> {
            override fun createFromParcel(source: Parcel) =
                ResolvedOutcomeParcel(source.readString()!!)
            override fun newArray(size: Int) = arrayOfNulls<ResolvedOutcomeParcel>(size)
        }
    }
}
