package dev.governance.android.app

import android.content.Context
import dev.governance.android.platform.RootCapabilityDispatcher

/**
 * Detects whether this APK is running as a regular sideloaded app or
 * as a privileged system app installed under /system/priv-app via an
 * AOSP-flashed ROM.
 *
 * The detection delegates to [RootCapabilityDispatcher.isSystemApp]
 * which checks both the UID floor (system UIDs are < 10000) and the
 * ApplicationInfo flags (FLAG_SYSTEM is set on /system/app placements).
 *
 * The two modes have very different capability profiles:
 *
 * | Mode | UID range | FLAG_SYSTEM | Root dispatchers |
 * |------|-----------|-------------|------------------|
 * | App  | 10000+    | unset       | Return Unsupported(needs system uid) |
 * | System | <10000  | set         | Execute privileged ops via the AOSP overlay |
 *
 * The UI uses [BuildMode] to render a banner on Home / Technical Detail
 * showing the current mode. App mode is the default for development
 * (sideload via `adb install`); System mode requires a custom ROM built
 * from `aosp/Android.bp`.
 */
object BuildModeDetector {

    /** Snapshot the current build mode. Cheap — no I/O. */
    fun detect(context: Context): BuildMode {
        val dispatcher = RootCapabilityDispatcher(context.applicationContext)
        return if (dispatcher.isSystemApp()) BuildMode.System else BuildMode.App
    }

    /** Human-readable label for the UI banner. */
    fun label(mode: BuildMode): String = when (mode) {
        BuildMode.App -> "App mode — root capabilities disabled"
        BuildMode.System -> "System mode — full governance kernel active"
    }

    /** Short label for tight surfaces. */
    fun shortLabel(mode: BuildMode): String = when (mode) {
        BuildMode.App -> "App mode"
        BuildMode.System -> "System mode"
    }
}

enum class BuildMode { App, System }
