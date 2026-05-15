package dev.governance.perception

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Typed action vocabulary for UI interactions. Each variant maps
 * directly to an Android accessibility primitive:
 *
 * - [Tap], [LongPress] → `dispatchGesture()` at element coordinates
 * - [TapAt] → `dispatchGesture()` at raw coordinates
 * - [TapText] → `findAccessibilityNodeInfosByText()` + `ACTION_CLICK`
 * - [Scroll] → `ACTION_SCROLL_FORWARD/BACKWARD` or gesture swipe
 * - [Swipe] → `dispatchGesture()` with path
 * - [SetText] → `ACTION_SET_TEXT` on focused/target node
 * - [Global] → `performGlobalAction()`
 * - [Wait] → delay for UI to settle
 *
 * The agent loop parses LLM output into these typed actions, and the
 * executor dispatches them. Every action is auditable because it
 * serializes deterministically.
 */
@Serializable
sealed interface NodeAction {

    @Serializable @SerialName("tap")
    data class Tap(val elementIndex: Int) : NodeAction

    @Serializable @SerialName("tap_at")
    data class TapAt(val x: Int, val y: Int) : NodeAction

    @Serializable @SerialName("tap_text")
    data class TapText(val text: String) : NodeAction

    @Serializable @SerialName("long_press")
    data class LongPress(val elementIndex: Int) : NodeAction

    @Serializable @SerialName("scroll")
    data class Scroll(val direction: ScrollDirection) : NodeAction

    @Serializable @SerialName("swipe")
    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long = 300,
    ) : NodeAction

    @Serializable @SerialName("set_text")
    data class SetText(val elementIndex: Int, val text: String) : NodeAction

    @Serializable @SerialName("global")
    data class Global(val action: GlobalActionType) : NodeAction

    @Serializable @SerialName("wait")
    data object Wait : NodeAction
}

@Serializable
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }

@Serializable
enum class GlobalActionType(val androidConstant: Int) {
    BACK(1),         // AccessibilityService.GLOBAL_ACTION_BACK
    HOME(2),         // AccessibilityService.GLOBAL_ACTION_HOME
    RECENTS(3),      // AccessibilityService.GLOBAL_ACTION_RECENTS
    NOTIFICATIONS(4), // AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
    QUICK_SETTINGS(5), // AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
}
