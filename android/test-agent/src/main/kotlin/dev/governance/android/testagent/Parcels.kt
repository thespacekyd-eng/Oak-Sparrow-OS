@file:Suppress("PackageDirectoryMismatch")
// Parcelable wrappers must be in the AIDL-declared packages to match the
// service's class names. The test agent reimplements them identically
// (JSON string carrier) to prove the IPC boundary without depending on
// :android-platform.

package dev.governance.android.platform.parcel

import android.os.Parcel
import android.os.Parcelable

data class ProposedActionParcel(val serialized: String) : Parcelable {
    override fun writeToParcel(dest: Parcel, flags: Int) { dest.writeString(serialized) }
    override fun describeContents(): Int = 0
    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<ProposedActionParcel> {
            override fun createFromParcel(source: Parcel) = ProposedActionParcel(source.readString()!!)
            override fun newArray(size: Int) = arrayOfNulls<ProposedActionParcel>(size)
        }
    }
}

data class GateDecisionParcel(val serialized: String) : Parcelable {
    override fun writeToParcel(dest: Parcel, flags: Int) { dest.writeString(serialized) }
    override fun describeContents(): Int = 0
    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<GateDecisionParcel> {
            override fun createFromParcel(source: Parcel) = GateDecisionParcel(source.readString()!!)
            override fun newArray(size: Int) = arrayOfNulls<GateDecisionParcel>(size)
        }
    }
}

data class GovernanceSnapshotParcel(val serialized: String) : Parcelable {
    override fun writeToParcel(dest: Parcel, flags: Int) { dest.writeString(serialized) }
    override fun describeContents(): Int = 0
    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<GovernanceSnapshotParcel> {
            override fun createFromParcel(source: Parcel) = GovernanceSnapshotParcel(source.readString()!!)
            override fun newArray(size: Int) = arrayOfNulls<GovernanceSnapshotParcel>(size)
        }
    }
}

data class ResolvedOutcomeParcel(val serialized: String) : Parcelable {
    override fun writeToParcel(dest: Parcel, flags: Int) { dest.writeString(serialized) }
    override fun describeContents(): Int = 0
    companion object {
        @JvmField val CREATOR = object : Parcelable.Creator<ResolvedOutcomeParcel> {
            override fun createFromParcel(source: Parcel) = ResolvedOutcomeParcel(source.readString()!!)
            override fun newArray(size: Int) = arrayOfNulls<ResolvedOutcomeParcel>(size)
        }
    }
}
