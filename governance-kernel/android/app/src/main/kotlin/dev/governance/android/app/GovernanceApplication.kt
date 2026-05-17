package dev.governance.android.app

import android.app.Application
import android.provider.Settings
import dev.governance.android.platform.AccessibilityObservationService

/**
 * Application class for the governance host app.
 * Wires the accessibility service connection listener to auto-start
 * the floating mic overlay when accessibility is enabled.
 */
class GovernanceApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // When the accessibility service connects, start the floating
        // overlay bubble so the user can talk to Oak from any app.
        AccessibilityObservationService.onConnectedListener = { service ->
            if (Settings.canDrawOverlays(service)) {
                FloatingOakService.start(service)
            }
        }
    }
}
