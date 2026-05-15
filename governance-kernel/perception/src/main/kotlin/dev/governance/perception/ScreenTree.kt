package dev.governance.perception

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Serializable representation of the Android accessibility tree.
 * Pure JVM — no Android dependencies. Android modules map
 * [AccessibilityNodeInfo] to [ScreenNode] and produce this structure.
 *
 * This is the perception contract: the agent sees the screen through
 * this type, and the audit log records it for traceability.
 */
@Serializable
data class ScreenTree(
    val packageName: String,
    val activityName: String? = null,
    val timestamp: Instant,
    val nodes: List<ScreenNode>,
    val totalNodeCount: Int = nodes.size,
) {
    /**
     * Renders the tree as a numbered element list for LLM consumption.
     * Example:
     * ```
     * [Screen: com.instagram.android]
     * [0] Button: "Like" (clickable)
     * [1] Text: "523 likes"
     * [2] ScrollView: (no text) (scrollable)
     * ```
     */
    fun toPrompt(maxElements: Int = 40): String = buildString {
        appendLine("[Screen: $packageName]")
        for (node in nodes.take(maxElements)) {
            val clickTag = if (node.isClickable) " (clickable)" else ""
            val scrollTag = if (node.isScrollable) " (scrollable)" else ""
            val editTag = if (node.isEditable) " (editable)" else ""
            val displayText = node.text ?: node.contentDescription ?: "(no text)"
            appendLine("[${node.index}] ${node.role.label}: \"$displayText\"$clickTag$scrollTag$editTag")
        }
        if (nodes.size > maxElements) {
            appendLine("[...${nodes.size - maxElements} more elements]")
        }
    }
}

@Serializable
data class ScreenNode(
    val index: Int,
    val className: String,
    val role: NodeRole,
    val text: String? = null,
    val contentDescription: String? = null,
    val isClickable: Boolean = false,
    val isScrollable: Boolean = false,
    val isEditable: Boolean = false,
    val isCheckable: Boolean = false,
    val isChecked: Boolean = false,
    val isEnabled: Boolean = true,
    val bounds: ScreenRect,
    val depth: Int = 0,
)

@Serializable
data class ScreenRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

@Serializable
enum class NodeRole(val label: String) {
    Button("Button"),
    Input("Input"),
    Image("Image"),
    Checkbox("Checkbox"),
    Switch("Switch"),
    ScrollView("ScrollView"),
    Text("Text"),
    Container("Container"),
    Unknown("Unknown");

    companion object {
        fun fromClassName(className: String?): NodeRole = when {
            className == null -> Unknown
            className.contains("Button") -> Button
            className.contains("EditText") -> Input
            className.contains("Image") -> Image
            className.contains("CheckBox") -> Checkbox
            className.contains("Switch") -> Switch
            else -> Unknown
        }
    }
}
