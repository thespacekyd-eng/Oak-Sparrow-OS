package dev.governance.android.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.governance.android.platform.AccessibilityObservationService
import dev.governance.attestation.AttestationVerifier
import dev.governance.core.GateDecision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Dispatches approved actions to the device. Verifies the
 * decision's attestation before every dispatch.
 *
 * Supported kinds:
 * - `read_calendar` — opens Calendar, returns event text if readable
 * - `send_email` — opens Gmail compose, auto-taps Send via accessibility
 * - `share_to_social_app` — opens the system share sheet
 *
 * All other kinds return [DispatchResult.Unsupported].
 */
class ActionDispatcher(private val context: Context) {

    suspend fun dispatch(decision: GateDecision, step: PlannedStep): DispatchResult =
        withContext(Dispatchers.Main) {
            // Re-verify attestation before every dispatch
            if (!AttestationVerifier.verify(decision)) {
                return@withContext DispatchResult.Failed(
                    "Attestation verification failed. Action not executed."
                )
            }

            dispatchByKind(step)
        }

    /**
     * Speculative dispatch — fires the reversible work for a step WITHOUT
     * requiring a signed [GateDecision]. Used by [SpeculativeOrchestrator]
     * to launch the intent immediately while the kernel decides in parallel.
     *
     * Hard requirements:
     * - Caller must verify the step is FullyReversible AND App-tier before
     *   calling this. Speculative dispatch on OneShot/Irreversible/RootSystem
     *   actions is forbidden — those must wait for the kernel decision.
     * - The audit log is NOT written by this method. The real signed decision
     *   that arrives from the kernel produces the audit record.
     */
    suspend fun dispatchSpeculative(step: PlannedStep): DispatchResult =
        withContext(Dispatchers.Main) {
            require(step.reversibility == dev.governance.core.Reversibility.FullyReversible) {
                "Speculative dispatch only allowed for FullyReversible steps; got ${step.reversibility}"
            }
            require(dev.governance.core.ActionTier.classify(step.kind) ==
                dev.governance.core.ActionTier.App) {
                "Speculative dispatch only allowed for App-tier kinds; '${step.kind}' is RootSystem"
            }
            dispatchByKind(step)
        }

    private suspend fun dispatchByKind(step: PlannedStep): DispatchResult =
        when (step.kind) {
            "read_calendar" -> dispatchReadCalendar()
            "send_email" -> dispatchSendEmail(step.target ?: "")
            "share_to_social_app" -> dispatchShareToSocial(step.target ?: "")
            "open_app" -> dispatchOpenApp(step.target ?: "")
            else -> DispatchResult.Unsupported(
                "Action '${step.kind}' isn't yet supported. The agent will skip it."
            )
        }

    private suspend fun dispatchReadCalendar(): DispatchResult {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_CALENDAR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            delay(2000) // wait for calendar to render

            // Try to read event text via accessibility
            val text = AccessibilityObservationService.readCurrentWindowText()
            return if (text != null && text.length > 10) {
                DispatchResult.Success("Calendar: $text")
            } else {
                DispatchResult.Success("Opened Calendar app.")
            }
        } catch (e: Exception) {
            return DispatchResult.Failed("Could not open Calendar: ${e.message}")
        }
    }

    private suspend fun dispatchSendEmail(target: String): DispatchResult {
        try {
            val mailto = if (target.contains("@")) target else "$target@example.com"
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$mailto")
                putExtra(Intent.EXTRA_SUBJECT, "")
                putExtra(Intent.EXTRA_TEXT, "")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            delay(3000) // wait for compose to render

            // Try to find and click Send button via accessibility
            val sent = AccessibilityObservationService.clickByText("Send")
            return if (sent) {
                delay(1000)
                DispatchResult.Success("Email sent to $target.")
            } else {
                DispatchResult.Success("Email compose opened for $target. Tap Send to complete.")
            }
        } catch (e: Exception) {
            return DispatchResult.Failed("Could not compose email: ${e.message}")
        }
    }

    private suspend fun dispatchOpenApp(target: String): DispatchResult {
        if (target.isBlank()) {
            return DispatchResult.Failed("No app name provided.")
        }
        return try {
            // Resolve human-friendly app names to launch intents.
            val pm = context.packageManager
            val candidate = APP_NAME_TO_PACKAGE[target.lowercase()]
                ?: target // user may have typed a package name directly

            val launchIntent = pm.getLaunchIntentForPackage(candidate)
                ?: pm.getLaunchIntentForPackage(target.lowercase())

            if (launchIntent == null) {
                // Fall back to a query intent that lets the system resolve.
                val queryIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(queryIntent)
                return DispatchResult.Failed("Could not find $target on this device.")
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            DispatchResult.Success("Opened $target.")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not open $target: ${e.message}")
        }
    }

    private fun dispatchShareToSocial(target: String): DispatchResult {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, target)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Share via").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            DispatchResult.Success("Share sheet opened. Choose an app to share.")
        } catch (e: Exception) {
            DispatchResult.Failed("Could not open share sheet: ${e.message}")
        }
    }

    companion object {
        /**
         * Common app names → package names. Used by [dispatchOpenApp] to
         * resolve human-friendly names like "instagram" to package ids
         * like "com.instagram.android". Not exhaustive — falls back to
         * treating the user's input as a package name directly.
         */
        internal val APP_NAME_TO_PACKAGE = mapOf(
            "instagram" to "com.instagram.android",
            "ig" to "com.instagram.android",
            "gmail" to "com.google.android.gm",
            "mail" to "com.google.android.gm",
            "calendar" to "com.google.android.calendar",
            "messages" to "com.google.android.apps.messaging",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "youtube" to "com.google.android.youtube",
            "yt" to "com.google.android.youtube",
            "maps" to "com.google.android.apps.maps",
            "settings" to "com.android.settings",
            "spotify" to "com.spotify.music",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "whatsapp" to "com.whatsapp",
            "tiktok" to "com.zhiliaoapp.musically",
            "discord" to "com.discord",
            "slack" to "com.Slack",
            "photos" to "com.google.android.apps.photos",
            "camera" to "com.android.camera",
            "files" to "com.google.android.apps.nbu.files",
        )
    }
}
