package dev.governance.android.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.governance.android.platform.parcel.GovernanceSnapshotParcel
import dev.governance.core.GovernanceSnapshot
import java.util.Locale

/**
 * Debug scaffolding: single screen showing kernel state.
 * Phase 2B replaces this with the polished governance dashboard.
 */
class MainActivity : ComponentActivity() {

    private var kernelInterface: AgentKernelInterface? = null
    private var snapshot: GovernanceSnapshot? by mutableStateOf(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            kernelInterface = AgentKernelInterface.Stub.asInterface(service)
            refreshSnapshot()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            kernelInterface = null
            snapshot = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure service is running
        startForegroundService(Intent(this, GovernanceKernelService::class.java))

        setContent {
            MaterialTheme {
                GovernanceDashboard(
                    snapshot = snapshot,
                    onRefresh = { refreshSnapshot() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(
            Intent(this, GovernanceKernelService::class.java),
            connection,
            Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        super.onStop()
        unbindService(connection)
    }

    private fun refreshSnapshot() {
        try {
            val parcel = kernelInterface?.snapshot()
            snapshot = parcel?.toKernel()
        } catch (_: Exception) {
            snapshot = null
        }
    }
}

@Composable
private fun GovernanceDashboard(
    snapshot: GovernanceSnapshot?,
    onRefresh: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            Text(
                text = "Governance Kernel",
                style = MaterialTheme.typography.headlineMedium,
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (snapshot == null) {
                Text("Connecting to kernel service...")
            } else {
                StateCard("Current gamma (\u03b3)", String.format(Locale.ROOT, "%.4f", snapshot.gamma))
                StateCard(
                    "Calibrator mode",
                    if (snapshot.warmupComplete) "Steady" else "Warmup",
                )
                StateCard(
                    "Recent outcomes",
                    "PASS: ${snapshot.recentOutcomes.pass}  " +
                        "HOLD: ${snapshot.recentOutcomes.hold}  " +
                        "VETO: ${snapshot.recentOutcomes.veto}",
                )
                StateCard(
                    "Avg entropy",
                    String.format(Locale.ROOT, "%.4f", snapshot.recentEntropyAverage),
                )
                StateCard(
                    "Avg divergence",
                    String.format(Locale.ROOT, "%.4f", snapshot.recentDivergenceAverage),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onRefresh) {
                Text("Refresh")
            }
        }
    }
}

@Composable
private fun StateCard(label: String, value: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(text = value, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
