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

            when (step.kind) {
                "read_calendar" -> dispatchReadCalendar()
                "send_email" -> dispatchSendEmail(step.target ?: "")
                "share_to_social_app" -> dispatchShareToSocial(step.target ?: "")
                else -> DispatchResult.Unsupported(
                    "Action '${step.kind}' isn't yet supported. The agent will skip it."
                )
            }
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
}
