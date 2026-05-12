package dev.governance.android.testagent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.governance.android.app.AgentKernelInterface
import dev.governance.android.platform.parcel.GateDecisionParcel
import dev.governance.android.platform.parcel.GovernanceSnapshotParcel
import dev.governance.android.platform.parcel.ProposedActionParcel
import dev.governance.android.platform.parcel.ResolvedOutcomeParcel

/**
 * Binds to the governance kernel service via AIDL and exposes
 * typed wrappers that serialize/deserialize JSON over the Binder.
 */
class KernelClient(private val context: Context) {

    private var kernel: AgentKernelInterface? = null
    var connected by mutableStateOf(false)
        private set

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            kernel = AgentKernelInterface.Stub.asInterface(service)
            connected = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            kernel = null
            connected = false
        }
    }

    fun bind() {
        val intent = Intent().apply {
            component = ComponentName(
                "dev.governance.android",
                "dev.governance.android.app.GovernanceKernelService",
            )
        }
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        android.util.Log.i("KernelClient", "bindService returned: $bound")
    }

    fun unbind() {
        context.unbindService(connection)
        connected = false
    }

    fun decide(actionJson: String): String {
        val result = kernel?.decide(ProposedActionParcel(actionJson))
            ?: throw IllegalStateException("Not connected to kernel service")
        return result.serialized
    }

    fun resolve(auditId: String, outcomeJson: String) {
        kernel?.resolve(auditId, ResolvedOutcomeParcel(outcomeJson))
            ?: throw IllegalStateException("Not connected to kernel service")
    }

    fun snapshot(): String {
        val result = kernel?.snapshot()
            ?: throw IllegalStateException("Not connected to kernel service")
        return result.serialized
    }
}
