package dev.governance.android.app.agent

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import dev.governance.android.platform.AccessibilityObservationService

/**
 * Reads the current screen's UI tree and produces a structured summary
 * for the LLM to reason about. The LLM never sees raw accessibility
 * node IDs — it gets a simplified text representation of what's visible.
 *
 * Example output for an Instagram feed:
 * ```
 * [Screen: com.instagram.android]
 * [0] Image (no text)
 * [1] Text: "musiclover99"
 * [2] Text: "New track dropping tonight 🎵"
 * [3] Button: "♡" (clickable)
 * [4] Button: "💬" (clickable)
 * [5] Text: "523 likes"
 * [6] Image (no text)
 * [7] Text: "djsmith"
 * ...
 * ```
 *
 * The LLM can then say: "tap element [3] to like the music post"
 */
object ScreenReader {

    data class ScreenElement(
        val index: Int,
        val type: String,
        val text: String?,
        val contentDescription: String?,
        val isClickable: Boolean,
        val isScrollable: Boolean,
        val className: String,
        val bounds: String,
    )

    data class ScreenState(
        val packageName: String,
        val elements: List<ScreenElement>,
    ) {
        fun toPrompt(maxElements: Int = 40): String {
            val sb = StringBuilder()
            sb.appendLine("[Screen: $packageName]")
            for (el in elements.take(maxElements)) {
                val clickTag = if (el.isClickable) " (clickable)" else ""
                val scrollTag = if (el.isScrollable) " (scrollable)" else ""
                val displayText = el.text ?: el.contentDescription ?: "(no text)"
                sb.appendLine("[${el.index}] ${el.type}: \"$displayText\"$clickTag$scrollTag")
            }
            if (elements.size > maxElements) {
                sb.appendLine("[...${elements.size - maxElements} more elements]")
            }
            return sb.toString()
        }
    }

    /**
     * Reads the current screen and returns a structured representation.
     * Returns null if the accessibility service isn't connected.
     *
     * When Oak's own overlay is in the foreground, [rootInActiveWindow]
     * returns Oak's UI tree instead of the app behind it. To fix this,
     * we scan all windows and pick the one belonging to the target app
     * (not our own package).
     */
    fun read(): ScreenState? {
        val service = AccessibilityObservationService.getInstance() ?: return null

        // Collect elements from ALL app windows, not just rootInActiveWindow.
        // rootInActiveWindow often returns a truncated tree (especially for
        // complex apps like Instagram), while iterating service.windows gives
        // access to each window's full node tree.
        val ownPkg = "dev.governance.android"
        val skipPackages = setOf(
            ownPkg,
            "com.android.systemui",
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.google.android.inputmethod.latin",
        )

        // Try windows API first for richer tree
        var root: AccessibilityNodeInfo? = null
        val allRoots = mutableListOf<AccessibilityNodeInfo>()
        try {
            service.windows?.forEach { w ->
                val r = w.root ?: return@forEach
                val pkg = r.packageName?.toString() ?: return@forEach
                if (pkg !in skipPackages) {
                    allRoots.add(r)
                    if (root == null) root = r  // first non-skip window
                }
            }
        } catch (_: Exception) { }

        // Fallback to rootInActiveWindow
        if (root == null) {
            root = service.rootInActiveWindow ?: return null
            allRoots.clear()
            allRoots.add(root!!)
        }

        val elements = mutableListOf<ScreenElement>()
        var index = 0
        var totalNodes = 0

        for (r in allRoots) {
            try {
                collectElements(r, elements, index = { index++ }, counter = { totalNodes++ }, depth = 0)
            } catch (_: Exception) { }
        }

        val pkg = root!!.packageName?.toString() ?: "unknown"
        Log.i("ScreenReader", "Read $pkg: ${elements.size} elements (${elements.count { it.isClickable }} clickable) from $totalNodes total nodes, ${allRoots.size} windows")
        return ScreenState(pkg, elements)
    }

    private fun collectElements(
        node: AccessibilityNodeInfo,
        out: MutableList<ScreenElement>,
        index: () -> Int,
        counter: () -> Unit,
        depth: Int,
    ) {
        if (depth > 15) return
        counter()

        val hasText = !node.text.isNullOrBlank()
        val hasDesc = !node.contentDescription.isNullOrBlank()
        val isInteractive = node.isClickable || node.isScrollable

        // Only include nodes that are visible and have text or are interactive
        if (hasText || hasDesc || isInteractive) {
            val type = when {
                node.className?.toString()?.contains("Button") == true -> "Button"
                node.className?.toString()?.contains("EditText") == true -> "Input"
                node.className?.toString()?.contains("Image") == true -> "Image"
                node.className?.toString()?.contains("CheckBox") == true -> "Checkbox"
                node.className?.toString()?.contains("Switch") == true -> "Switch"
                node.isScrollable -> "ScrollView"
                else -> "Text"
            }

            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)

            out.add(ScreenElement(
                index = index(),
                type = type,
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                isClickable = node.isClickable,
                isScrollable = node.isScrollable,
                className = node.className?.toString() ?: "",
                bounds = "${rect.centerX()},${rect.centerY()}",
            ))
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectElements(it, out, index, counter, depth + 1) }
        }
    }
}
