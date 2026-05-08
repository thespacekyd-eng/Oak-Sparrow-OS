package dev.governance.android.app

import android.app.Application
import android.content.Intent

/**
 * Application class for the governance host app.
 * Starts the [GovernanceKernelService] on app launch.
 */
class GovernanceApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // The service will be started explicitly when the user completes onboarding.
        // We don't auto-start here to avoid running before permissions are granted.
    }
}
