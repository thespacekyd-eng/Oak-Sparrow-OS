package dev.governance.android.app.agent

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import dev.governance.android.platform.AccessibilityObservationService
import kotlinx.coroutines.delay

/**
 * Executes UI interactions on any app via the Accessibility Service.
 * This is the "hands" of the agent — the LLM reasons, UiInteractor acts.
 *
 * Every interaction is logged and goes through the governance kernel
 * as an audited action. The LLM specifies interactions by element
 * index (from [ScreenReader]) or by text/description content.
 */
object UiInteractor {

    private const val TAG = "UiInteractor"

    sealed class InteractionResult {
        data class Success(val description: String) : InteractionResult()
        data class Failed(val reason: String) : InteractionResult()
    }

    /**
     * Taps the element at the given index from the current screen state.
     * Reads the screen first to get the current element list, then
     * finds and clicks the element at that index.
     */
    suspend fun tapByIndex(index: Int): InteractionResult {
        val screen = ScreenReader.read()
            ?: return InteractionResult.Failed("Accessibility service not connected")
        val element = screen.elements.getOrNull(index)
            ?: return InteractionResult.Failed("Element [$index] not found (${screen.elements.size} elements on screen)")

        return tapByBounds(element.bounds, element.text ?: element.contentDescription ?: "element $index")
    }

    /**
     * Taps an element by its visible text content. Searches the current
     * screen for a clickable element containing the text.
     */
    suspend fun tapByText(text: String): InteractionResult {
        val clicked = AccessibilityObservationService.clickByText(text)
        return if (clicked) {
            Log.i(TAG, "Tapped by text: \"$text\"")
            delay(500) // Wait for UI to settle
            InteractionResult.Success("Tapped \"$text\"")
        } else {
            InteractionResult.Failed("Could not find clickable element with text \"$text\"")
        }
    }

    /**
     * Taps at exact screen coordinates. Used by the vision-based agent
     * loop where Claude returns pixel coordinates from screenshots.
     */
    suspend fun tapAtCoordinates(x: Int, y: Int): InteractionResult {
        return tapByBounds("$x,$y", "($x, $y)")
    }

    /**
     * Long-presses at exact screen coordinates. Used for context menus,
     * text selection, copy operations.
     */
    suspend fun longPressAtCoordinates(x: Int, y: Int): InteractionResult {
        val service = AccessibilityObservationService.getInstance()
            ?: return InteractionResult.Failed("Accessibility service not connected")

        try {
            val path = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(
                    android.accessibilityservice.GestureDescription.StrokeDescription(
                        android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) },
                        0, 800 // 800ms hold = long press
                    )
                )
                .build()
            service.dispatchGesture(path, null, null)
            delay(1200) // Wait for long-press result + menu
            Log.i(TAG, "Long-pressed at ($x, $y)")
            return InteractionResult.Success("Long-pressed at ($x, $y)")
        } catch (e: Exception) {
            return InteractionResult.Failed("Long press failed: ${e.message}")
        }
    }

    /**
     * Taps at screen coordinates (parsed from element bounds).
     */
    private suspend fun tapByBounds(bounds: String, description: String): InteractionResult {
        val service = AccessibilityObservationService.getInstance()
            ?: return InteractionResult.Failed("Accessibility service not connected")

        try {
            val parts = bounds.split(",")
            val x = parts[0].toFloat()
            val y = parts[1].toFloat()

            // Use gesture dispatch for precise tapping
            val path = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(
                    android.accessibilityservice.GestureDescription.StrokeDescription(
                        android.graphics.Path().apply { moveTo(x, y) },
                        0, 50
                    )
                )
                .build()

            var success = false
            service.dispatchGesture(path, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    success = true
                }
            }, null)

            delay(600) // Wait for gesture + UI update
            Log.i(TAG, "Tapped at ($x, $y): \"$description\"")
            return InteractionResult.Success("Tapped \"$description\"")
        } catch (e: Exception) {
            return InteractionResult.Failed("Tap failed: ${e.message}")
        }
    }

    /**
     * Scrolls the current screen in the given direction.
     */
    /**
     * Scrolls the current screen using a vertical gesture swipe.
     *
     * Always uses gesture-based scrolling instead of ACTION_SCROLL_FORWARD
     * on accessibility nodes. The node-based approach causes horizontal
     * swiping in apps like Instagram where ViewPager and horizontal
     * RecyclerViews intercept the scroll action. A vertical finger gesture
     * always scrolls the visible content vertically.
     */
    suspend fun scroll(direction: String): InteractionResult {
        val service = AccessibilityObservationService.getInstance()
            ?: return InteractionResult.Failed("Accessibility service not connected")

        try {
            val displayMetrics = android.content.res.Resources.getSystem().displayMetrics
            val centerX = displayMetrics.widthPixels / 2f
            val startY: Float
            val endY: Float
            if (direction.lowercase() in listOf("down", "forward")) {
                startY = displayMetrics.heightPixels * 0.7f
                endY = displayMetrics.heightPixels * 0.3f
            } else {
                startY = displayMetrics.heightPixels * 0.3f
                endY = displayMetrics.heightPixels * 0.7f
            }

            val path = android.graphics.Path().apply {
                moveTo(centerX, startY)
                lineTo(centerX, endY)
            }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 300))
                .build()
            service.dispatchGesture(gesture, null, null)
            delay(1200) // Wait for scroll animation + content load
            Log.i(TAG, "Scrolled $direction (gesture)")
            return InteractionResult.Success("Scrolled $direction")
        } catch (e: Exception) {
            return InteractionResult.Failed("Scroll failed: ${e.message}")
        }
    }

    /**
     * Types text into the currently focused input field.
     */
    suspend fun typeText(text: String): InteractionResult {
        val service = AccessibilityObservationService.getInstance()
            ?: return InteractionResult.Failed("Accessibility service not connected")

        val root = service.rootInActiveWindow
            ?: return InteractionResult.Failed("No active window")

        try {
            val focused = findFocusedInput(root)
            if (focused != null) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                delay(300)
                Log.i(TAG, "Typed: \"${text.take(20)}...\"")
                return InteractionResult.Success("Typed \"${text.take(30)}\"")
            }
            return InteractionResult.Failed("No focused input field found")
        } catch (e: Exception) {
            return InteractionResult.Failed("Type failed: ${e.message}")
        }
    }

    /**
     * Waits for the screen to change (e.g., after a tap triggers navigation).
     */
    suspend fun waitForScreenChange(timeoutMs: Long = 3000): InteractionResult {
        val before = ScreenReader.read()?.packageName
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            delay(300)
            val current = ScreenReader.read()?.packageName
            if (current != before) {
                return InteractionResult.Success("Screen changed to $current")
            }
        }
        return InteractionResult.Success("Waited ${timeoutMs}ms (no screen change)")
    }

    /**
     * Finds the best scrollable container for vertical scrolling.
     * Prefers RecyclerView/ListView/ScrollView (vertical content) over
     * ViewPager (horizontal tabs). This prevents the agent from swiping
     * between Instagram's Feed/Reels/DMs tabs instead of scrolling the feed.
     */
    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectScrollables(node, candidates, depth = 0)
        if (candidates.isEmpty()) return null

        // Exclude ViewPager (horizontal tab swiping)
        val nonPager = candidates.filter { n ->
            val cls = n.className?.toString() ?: ""
            !cls.contains("ViewPager")
        }
        val pool = nonPager.ifEmpty { candidates }

        // Prefer vertical scroll containers
        val preferred = pool.firstOrNull { n ->
            val cls = n.className?.toString() ?: ""
            cls.contains("RecyclerView") || cls.contains("ListView") ||
                cls.contains("ScrollView") || cls.contains("NestedScrollView")
        }
        return preferred ?: pool.last() // deepest scrollable as fallback
    }

    private fun collectScrollables(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (depth > 15) return
        if (node.isScrollable) out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectScrollables(it, out, depth + 1) }
        }
    }

    private fun findFocusedInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.className?.toString()?.contains("EditText") == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedInput(child)
            if (result != null) return result
        }
        return null
    }
}
