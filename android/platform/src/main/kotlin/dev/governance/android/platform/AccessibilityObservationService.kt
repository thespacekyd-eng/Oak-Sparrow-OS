package dev.governance.android.platform

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Observes outcomes of agent actions via the accessibility framework.
 *
 * This service does NOT propose actions — proposals come from the agent
 * process via the AIDL interface. The accessibility service's job is
 * post-hoc outcome verification: when the agent reports "I just sent an
 * email," accessibility events confirm whether the send actually happened.
 *
 * ## Phase 2A stub
 *
 * The current implementation uses a minimal outcome-resolution policy:
 * - If the agent self-reports success and no error toast appeared within
 *   [OUTCOME_TIMEOUT_MS], mark BenignSuccess.
 * - If an error toast or dialog is detected, mark HarmlessFailure.
 * - The accessibility service does not currently detect harmful outcomes
 *   that succeed silently (e.g., data exfiltration via a legitimate-looking
 *   network request).
 *
 * // PHASE2B-FOLLOWUP: Real outcome resolution is its own design problem.
 * // The stub above overestimates BenignSuccess (same limitation as the
 * // adversarial runner's UserAttentionModel gap from Phase 1). Phase 2B
 * // should introduce:
 * // - App-specific outcome heuristics (e.g., email app: check sent folder)
 * // - Timeout-based escalation (no confirmation after N seconds → Flagged)
 * // - Integration with app-level callbacks where available
 *
 * // PHASE2C-FOLLOWUP: When AppFunctions API (Android 16+) becomes stable,
 * // the observation service should integrate with it for structured outcome
 * // reporting from supported apps, replacing heuristic accessibility scraping.
 */
class AccessibilityObservationService : AccessibilityService() {

    /**
     * Callback for delivering resolved outcomes to the kernel service.
     * Set by the hosting application when the service connects.
     */
    var outcomeCallback: OutcomeCallback? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // PHASE2B-FOLLOWUP: Implement real outcome resolution heuristics.
        // For now, log event types for debugging.
        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val text = event.text?.joinToString(" ") ?: ""
                // Stub: detect error-like notifications
                if (text.contains("error", ignoreCase = true) ||
                    text.contains("failed", ignoreCase = true)
                ) {
                    outcomeCallback?.onErrorDetected(text)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Stub: track window transitions for outcome correlation
                val className = event.className?.toString() ?: ""
                outcomeCallback?.onWindowChanged(className)
            }
        }
    }

    override fun onInterrupt() {
        // Required override — nothing to clean up in Phase 2A
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Wire callback from the static locator set by GovernanceKernelService
        outcomeCallback = callbackLocator?.invoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        outcomeCallback = null
    }

    /**
     * Callback interface for delivering observation events to the kernel service.
     */
    interface OutcomeCallback {
        fun onErrorDetected(text: String)
        fun onWindowChanged(className: String)
    }

    companion object {
        const val OUTCOME_TIMEOUT_MS = 30_000L

        /**
         * Static service-locator: [GovernanceKernelService] sets this to a
         * factory that returns an [OutcomeCallback] wired to the system event
         * audit channel. When the accessibility service connects, it calls
         * this to obtain its callback.
         */
        @Volatile
        var callbackLocator: (() -> OutcomeCallback)? = null
    }
}
