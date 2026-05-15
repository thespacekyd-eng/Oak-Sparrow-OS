package dev.governance.perception

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json

class ScreenTreeTest : StringSpec({

    val json = Json { encodeDefaults = true }

    fun sampleTree() = ScreenTree(
        packageName = "com.instagram.android",
        activityName = "MainActivity",
        timestamp = Instant.parse("2026-05-15T12:00:00Z"),
        nodes = listOf(
            ScreenNode(
                index = 0,
                className = "android.widget.ImageView",
                role = NodeRole.Image,
                contentDescription = "Profile photo",
                bounds = ScreenRect(0, 0, 100, 100),
            ),
            ScreenNode(
                index = 1,
                className = "android.widget.TextView",
                role = NodeRole.Text,
                text = "musiclover99",
                bounds = ScreenRect(100, 0, 300, 50),
            ),
            ScreenNode(
                index = 2,
                className = "android.widget.Button",
                role = NodeRole.Button,
                contentDescription = "Like",
                isClickable = true,
                bounds = ScreenRect(0, 200, 50, 250),
            ),
            ScreenNode(
                index = 3,
                className = "android.widget.ScrollView",
                role = NodeRole.ScrollView,
                isScrollable = true,
                bounds = ScreenRect(0, 0, 1080, 2400),
            ),
        ),
        totalNodeCount = 42,
    )

    "serialization round-trip preserves all fields" {
        val tree = sampleTree()
        val encoded = json.encodeToString(ScreenTree.serializer(), tree)
        val decoded = json.decodeFromString(ScreenTree.serializer(), encoded)
        decoded shouldBe tree
    }

    "ScreenNode serialization round-trip" {
        val node = ScreenNode(
            index = 5,
            className = "android.widget.EditText",
            role = NodeRole.Input,
            text = "Type here...",
            isEditable = true,
            isClickable = true,
            bounds = ScreenRect(10, 500, 1070, 550),
            depth = 3,
        )
        val encoded = json.encodeToString(ScreenNode.serializer(), node)
        val decoded = json.decodeFromString(ScreenNode.serializer(), encoded)
        decoded shouldBe node
    }

    "ScreenRect computes center and dimensions" {
        val rect = ScreenRect(100, 200, 300, 600)
        rect.centerX shouldBe 200
        rect.centerY shouldBe 400
        rect.width shouldBe 200
        rect.height shouldBe 400
    }

    "toPrompt renders indexed element list" {
        val prompt = sampleTree().toPrompt()
        prompt shouldContain "[Screen: com.instagram.android]"
        prompt shouldContain "[0] Image: \"Profile photo\""
        prompt shouldContain "[1] Text: \"musiclover99\""
        prompt shouldContain "[2] Button: \"Like\" (clickable)"
        prompt shouldContain "[3] ScrollView: \"(no text)\" (scrollable)"
    }

    "toPrompt truncates at maxElements" {
        val prompt = sampleTree().toPrompt(maxElements = 2)
        prompt shouldContain "[0] Image"
        prompt shouldContain "[1] Text"
        prompt shouldNotContain "[2] Button"
        prompt shouldContain "...2 more elements"
    }

    "NodeRole.fromClassName classifies known widget types" {
        NodeRole.fromClassName("android.widget.Button") shouldBe NodeRole.Button
        NodeRole.fromClassName("android.widget.EditText") shouldBe NodeRole.Input
        NodeRole.fromClassName("android.widget.ImageView") shouldBe NodeRole.Image
        NodeRole.fromClassName("android.widget.CheckBox") shouldBe NodeRole.Checkbox
        NodeRole.fromClassName("androidx.appcompat.widget.SwitchCompat") shouldBe NodeRole.Switch
        NodeRole.fromClassName("android.widget.LinearLayout") shouldBe NodeRole.Unknown
        NodeRole.fromClassName(null) shouldBe NodeRole.Unknown
    }

    "empty tree serializes and renders" {
        val tree = ScreenTree(
            packageName = "com.test",
            timestamp = Instant.parse("2026-05-15T12:00:00Z"),
            nodes = emptyList(),
        )
        val encoded = json.encodeToString(ScreenTree.serializer(), tree)
        val decoded = json.decodeFromString(ScreenTree.serializer(), encoded)
        decoded shouldBe tree
        decoded.toPrompt() shouldBe "[Screen: com.test]\n"
    }
})
