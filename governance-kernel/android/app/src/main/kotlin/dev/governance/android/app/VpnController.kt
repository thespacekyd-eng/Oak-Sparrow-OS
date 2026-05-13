package dev.governance.android.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log

/**
 * Controls the governance VPN lifecycle. Handles the Android VPN
 * consent dialog (one-time user approval) and start/stop.
 */
object VpnController {

    private const val TAG = "VpnController"

    /** Request code for the VPN consent activity result. */
    const val VPN_REQUEST_CODE = 9001

    /**
     * Starts the governance VPN. If Android hasn't approved the VPN
     * yet, launches the system consent dialog (returns false — caller
     * should handle the activity result). If already approved, starts
     * the service directly and returns true.
     */
    fun start(activity: Activity): Boolean {
        val intent = VpnService.prepare(activity)
        if (intent != null) {
            // User hasn't approved VPN yet — launch system dialog
            Log.i(TAG, "VPN consent needed — launching system dialog")
            activity.startActivityForResult(intent, VPN_REQUEST_CODE)
            return false
        }
        // Already approved — start directly
        startService(activity)
        return true
    }

    /** Starts the VPN service (call after consent is granted). */
    fun startService(context: Context) {
        val intent = Intent(context, GovernanceVpnService::class.java)
        context.startService(intent)
        Log.i(TAG, "VPN service started")
    }

    /** Stops the governance VPN. */
    fun stop(context: Context) {
        val intent = Intent(context, GovernanceVpnService::class.java).apply {
            action = GovernanceVpnService.ACTION_STOP
        }
        context.startService(intent)
        Log.i(TAG, "VPN service stop requested")
    }

    /** Whether the VPN is currently prepared (user has approved). */
    fun isPrepared(context: Context): Boolean {
        return VpnService.prepare(context) == null
    }
}
