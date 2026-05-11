package dev.governance.android.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.provider.Settings
import dev.governance.attestation.AttestationVerifier
import dev.governance.core.GateDecision
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Dispatcher for root-tier privileged action kinds. These actions
 * require system-app placement (privileged app under /system/priv-app
 * or platform-signed install in an AOSP-flashed ROM).
 *
 * Behavior:
 * - When NOT running as a system app: every dispatch returns
 *   [Result.Unsupported] with reason="requires system-app placement".
 * - When running as a system app: dispatches via the appropriate
 *   privileged API (Runtime.exec for shell binaries, PackageManager for
 *   package install/uninstall, Settings.Secure.putString for settings).
 *
 * Hard requirements (enforced regardless of system-app status):
 * - Re-verifies the [GateDecision] attestation before any dispatch.
 * - Shell exec restricted to a fixed allowlist of binaries (no su, no sh).
 * - Settings put restricted to a fixed allowlist of (namespace, key) pairs.
 *
 * The actual privileged operations are stubbed in this module — they
 * return success/failure but compile against pure Android SDK APIs that
 * may not have permission to execute under non-system UIDs. The stubs
 * are deliberately conservative: they fall back to [Result.Failed]
 * rather than crashing if a privileged API throws.
 */
class RootCapabilityDispatcher(private val context: Context) {

    /** Result of a root-tier dispatch. */
    sealed class Result {
        data class Success(val summary: String) : Result()
        data class Failed(val reason: String) : Result()
        data class Unsupported(val reason: String) : Result()
    }

    /**
     * Run the privileged operation described by [decision] and the
     * step's payload. The kind comes from [decision.actionKind]; the
     * payload (target string, value, etc.) is passed in [args].
     */
    fun dispatch(decision: GateDecision, args: Map<String, JsonElement>): Result {
        // Re-verify attestation before any privileged operation.
        if (!AttestationVerifier.verify(decision)) {
            return Result.Failed("Attestation verification failed.")
        }

        if (!isSystemApp()) {
            return Result.Unsupported(
                "Action '${decision.actionKind}' requires system-app placement. " +
                "This APK is running under a regular app UID."
            )
        }

        return when (decision.actionKind) {
            "shell_exec" -> dispatchShellExec(args)
            "package_install" -> dispatchPackageInstall(args)
            "package_uninstall" -> dispatchPackageUninstall(args)
            "settings_put" -> dispatchSettingsPut(args)
            "network_control" -> dispatchNetworkControl(args)
            "file_system_write" -> dispatchFileSystemWrite(args)
            else -> Result.Unsupported(
                "Root-tier kind '${decision.actionKind}' is not handled."
            )
        }
    }

    /**
     * Detects whether this app is running with system-level privileges.
     *
     * Two signals — either is sufficient:
     * 1. [Process.myUid] returns a UID below APP_UID_FLOOR (10000).
     *    System UIDs are always < 10000; user app UIDs start at 10000.
     * 2. [ApplicationInfo.flags] has FLAG_SYSTEM set, which AOSP sets
     *    on apps installed under /system/app or /system/priv-app.
     */
    fun isSystemApp(): Boolean {
        val uid = Process.myUid()
        if (uid < APP_UID_FLOOR) return true

        val flags = context.applicationInfo.flags
        if (flags and ApplicationInfo.FLAG_SYSTEM != 0) return true
        if (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0) return true

        return false
    }

    // -----------------------------------------------------------------
    // Stubs for privileged operations. These compile and return
    // structured results; the actual privileged execution requires
    // the AOSP system-app placement that the stub detects via
    // isSystemApp().
    // -----------------------------------------------------------------

    private fun dispatchShellExec(args: Map<String, JsonElement>): Result {
        val command = args["command"]?.jsonPrimitive?.content
            ?: return Result.Failed("shell_exec requires 'command' arg")

        val binary = command.trim().split(" ").firstOrNull() ?: ""
        if (binary !in SHELL_BINARY_ALLOWLIST) {
            return Result.Failed(
                "Binary '$binary' not in allowlist. Allowed: " +
                SHELL_BINARY_ALLOWLIST.joinToString()
            )
        }

        return try {
            val process = Runtime.getRuntime().exec(command)
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()
            if (exitCode == 0) {
                Result.Success("$ $command\n$output")
            } else {
                Result.Failed("$ $command exited $exitCode\n$output")
            }
        } catch (e: Exception) {
            Result.Failed("shell_exec failed: ${e.message}")
        }
    }

    private fun dispatchPackageInstall(args: Map<String, JsonElement>): Result {
        val apkPath = args["apk_path"]?.jsonPrimitive?.content
            ?: return Result.Failed("package_install requires 'apk_path' arg")

        // Real implementation requires INSTALL_PACKAGES permission, which
        // is privileged. Under system-app placement this works via
        // PackageInstaller.session.commit(). Stubbed here as a documented
        // entry point — full implementation lands when the AOSP overlay
        // is integrated and the platform key signs this APK.
        return Result.Success(
            "Would install package from $apkPath (requires AOSP integration)."
        )
    }

    private fun dispatchPackageUninstall(args: Map<String, JsonElement>): Result {
        val pkg = args["package"]?.jsonPrimitive?.content
            ?: return Result.Failed("package_uninstall requires 'package' arg")
        return Result.Success(
            "Would uninstall $pkg (requires AOSP integration)."
        )
    }

    private fun dispatchSettingsPut(args: Map<String, JsonElement>): Result {
        val namespace = args["namespace"]?.jsonPrimitive?.content
            ?: return Result.Failed("settings_put requires 'namespace' arg")
        val key = args["key"]?.jsonPrimitive?.content
            ?: return Result.Failed("settings_put requires 'key' arg")
        val value = args["value"]?.jsonPrimitive?.content
            ?: return Result.Failed("settings_put requires 'value' arg")

        val pair = "$namespace.$key"
        if (pair !in SETTINGS_ALLOWLIST) {
            return Result.Failed(
                "Setting '$pair' not in allowlist. " +
                "Add to SETTINGS_ALLOWLIST after security review."
            )
        }

        return try {
            val resolver = context.contentResolver
            val ok = when (namespace) {
                "system" -> Settings.System.putString(resolver, key, value)
                "secure" -> Settings.Secure.putString(resolver, key, value)
                "global" -> Settings.Global.putString(resolver, key, value)
                else -> return Result.Failed("Unknown namespace '$namespace'")
            }
            if (ok) Result.Success("Set $pair = $value")
            else Result.Failed("Settings put returned false (insufficient permission?)")
        } catch (e: SecurityException) {
            Result.Failed("Settings put requires WRITE_SECURE_SETTINGS: ${e.message}")
        } catch (e: Exception) {
            Result.Failed("Settings put failed: ${e.message}")
        }
    }

    private fun dispatchNetworkControl(args: Map<String, JsonElement>): Result {
        val target = args["target"]?.jsonPrimitive?.content
            ?: return Result.Failed("network_control requires 'target' arg")
        val state = args["state"]?.jsonPrimitive?.content
            ?: return Result.Failed("network_control requires 'state' arg")
        return Result.Success(
            "Would set $target to $state (requires AOSP integration / NETWORK_SETTINGS permission)."
        )
    }

    private fun dispatchFileSystemWrite(args: Map<String, JsonElement>): Result {
        val path = args["path"]?.jsonPrimitive?.content
            ?: return Result.Failed("file_system_write requires 'path' arg")

        // Restrict to the app's own files dir unless system-privileged.
        // Even under system UID, writes outside the dedicated governance
        // workspace should be blocked.
        if (!path.startsWith(context.filesDir.absolutePath) &&
            !path.startsWith("/data/governance/")) {
            return Result.Failed(
                "file_system_write path '$path' outside permitted prefixes."
            )
        }
        return Result.Success("Would write to $path (stubbed)")
    }

    companion object {
        /** Above this UID, an app is a normal user app. Below, it's a system service. */
        private const val APP_UID_FLOOR = 10_000

        /**
         * Allowlist of shell binaries that may be invoked via shell_exec.
         * Deliberately excludes su, sh, bash, and any general-purpose
         * shell that could compose arbitrary command pipelines.
         */
        internal val SHELL_BINARY_ALLOWLIST = setOf(
            "pm", "am", "settings", "getprop", "dumpsys", "input", "wm",
        )

        /**
         * Allowlist of (namespace.key) pairs for settings_put. Keep
         * narrow — every entry here is a permission to mutate a user
         * preference programmatically. Add only after threat-modeling.
         */
        internal val SETTINGS_ALLOWLIST = setOf(
            "system.screen_brightness",
            "system.screen_brightness_mode",
            "system.accelerometer_rotation",
            "global.airplane_mode_on",
            "global.bluetooth_on",
            "global.wifi_on",
            "global.zen_mode",
            "secure.location_mode",
        )
    }
}
