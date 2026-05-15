package dev.governance.android.platform

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.governance.perception.NodeRole
import dev.governance.perception.ScreenNode
import dev.governance.perception.ScreenRect
import dev.governance.perception.ScreenTree
import dev.governance.perception.TreeCapture
import kotlinx.datetime.Clock

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
class AccessibilityObservationService : AccessibilityService(), TreeCapture {

    /**
     * Callback for delivering resolved outcomes to the kernel service.
     * Set by the hosting application when the service connects.
     */
    var outcomeCallback: OutcomeCallback? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // PHASE2B-FOLLOWUP: Implement real outcome resolution heuristics.
        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val text = event.text?.joinToString(" ") ?: ""
                if (text.contains("error", ignoreCase = true) ||
                    text.contains("failed", ignoreCase = true)
                ) {
                    outcomeCallback?.onErrorDetected(text)
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val className = event.className?.toString() ?: ""
                outcomeCallback?.onWindowChanged(className)
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                outcomeCallback?.onScrollCompleted()
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val text = event.text?.joinToString(" ") ?: event.contentDescription?.toString() ?: ""
                outcomeCallback?.onClickConfirmed(text)
            }
        }
    }

    // -- Phase A: Tree capture for structured perception -----------------

    /**
     * Captures the current screen as a serializable [ScreenTree].
     * Returns null if the service is not connected or the window is unavailable.
     *
     * The tree contains only visible nodes that have text, content description,
     * or interactive properties (clickable, scrollable, editable). Depth is
     * limited to 12 levels to prevent stack overflow on deeply nested layouts.
     */
    override fun captureTree(): ScreenTree? {
        val root = rootInActiveWindow ?: return null
        val nodes = mutableListOf<ScreenNode>()
        var index = 0
        var totalCount = 0
        try {
            collectScreenNodes(root, nodes, indexer = { index++ }, counter = { totalCount++ }, depth = 0)
        } catch (_: Exception) { }
        val pkg = root.packageName?.toString() ?: "unknown"
        return ScreenTree(
            packageName = pkg,
            timestamp = Clock.System.now(),
            nodes = nodes,
            totalNodeCount = totalCount,
        )
    }

    private fun collectScreenNodes(
        node: AccessibilityNodeInfo,
        out: MutableList<ScreenNode>,
        indexer: () -> Int,
        counter: () -> Unit,
        depth: Int,
    ) {
        if (depth > 12) return
        counter()

        val hasText = !node.text.isNullOrBlank()
        val hasDesc = !node.contentDescription.isNullOrBlank()
        val isInteractive = node.isClickable || node.isScrollable || node.isEditable

        if (hasText || hasDesc || isInteractive) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val role = when {
                node.isScrollable -> NodeRole.ScrollView
                else -> NodeRole.fromClassName(node.className?.toString())
            }
            out.add(
                ScreenNode(
                    index = indexer(),
                    className = node.className?.toString() ?: "",
                    role = role,
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    isClickable = node.isClickable,
                    isScrollable = node.isScrollable,
                    isEditable = node.isEditable,
                    isCheckable = node.isCheckable,
                    isChecked = node.isChecked,
                    isEnabled = node.isEnabled,
                    bounds = ScreenRect(rect.left, rect.top, rect.right, rect.bottom),
                    depth = depth,
                )
            )
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectScreenNodes(it, out, indexer, counter, depth + 1) }
        }
    }

    override fun onInterrupt() {
        // Required override — nothing to clean up in Phase 2A
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Wire callback from the static locator set by GovernanceKernelService
        outcomeCallback = callbackLocator?.invoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        outcomeCallback = null
    }

    /**
     * Callback interface for delivering observation events to the kernel service.
     */
    interface OutcomeCallback {
        fun onErrorDetected(text: String)
        fun onWindowChanged(className: String)
        fun onScrollCompleted() {}
        fun onClickConfirmed(text: String) {}
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

        @Volatile
        private var instance: AccessibilityObservationService? = null

        /** Returns the live service instance, or null if not connected. */
        fun getInstance(): AccessibilityObservationService? = instance

        /**
         * Captures the current screen as a serializable [ScreenTree].
         * Convenience static accessor — delegates to the live service instance.
         */
        fun captureTree(): ScreenTree? = instance?.captureTree()

        /**
         * Finds a clickable element by visible text and clicks it.
         * Used by the action dispatcher for auto-send in email, etc.
         * Returns true if a clickable element was found and clicked.
         */
        fun clickByText(text: String): Boolean {
            val service = instance ?: return false
            val root = service.rootInActiveWindow ?: return false
            try {
                val nodes = root.findAccessibilityNodeInfosByText(text)
                for (node in nodes) {
                    if (node.isClickable) {
                        node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                        return true
                    }
                    // Check parent chain for a clickable ancestor
                    var parent = node.parent
                    while (parent != null) {
                        if (parent.isClickable) {
                            parent.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                            return true
                        }
                        parent = parent.parent
                    }
                }
            } catch (_: Exception) { }
            return false
        }

        /**
         * Reads text content from the current active window.
         * Returns concatenated text from all text nodes, or null.
         */
        fun readCurrentWindowText(): String? {
            val service = instance ?: return null
            val root = service.rootInActiveWindow ?: return null
            val texts = mutableListOf<String>()
            try {
                collectText(root, texts, depth = 0)
            } catch (_: Exception) { }
            return if (texts.isNotEmpty()) texts.joinToString(" ") else null
        }

        private fun collectText(
            node: android.view.accessibility.AccessibilityNodeInfo,
            out: MutableList<String>,
            depth: Int,
        ) {
            if (depth > 10) return
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { collectText(it, out, depth + 1) }
            }
        }
    }
}
