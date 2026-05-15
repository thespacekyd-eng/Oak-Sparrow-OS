package dev.governance.perception

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json

class NodeActionTest : StringSpec({

    val json = Json { encodeDefaults = true }

    "Tap serialization round-trip" {
        val action: NodeAction = NodeAction.Tap(elementIndex = 3)
        val encoded = json.encodeToString(NodeAction.serializer(), action)
        val decoded = json.decodeFromString(NodeAction.serializer(), encoded)
        decoded shouldBe action
        decoded.shouldBeInstanceOf<NodeAction.Tap>()
    }

    "TapAt serialization round-trip" {
        val action: NodeAction = NodeAction.TapAt(x = 540, y = 1200)
        val encoded = json.encodeToString(NodeAction.serializer(), action)
        val decoded = json.decodeFromString(NodeAction.serializer(), encoded)
        decoded shouldBe action
    }

    "TapText serialization round-trip" {
        val action: NodeAction = NodeAction.TapText(text = "Like")
        val encoded = json.encodeToString(NodeAction.serializer(), action)
        val decoded = json.decodeFromString(NodeAction.serializer(), encoded)
        decoded shouldBe action
    }

    "LongPress serialization round-trip" {
        val action: NodeAction = NodeAction.LongPress(elementIndex = 7)
        val encoded = json.encodeToString(NodeAction.serializer(), action)
        val decoded = json.decodeFromString(NodeAction.serializer(), encoded)
        decoded shouldBe action
    }

    "Scroll serialization round-trip for all directions" {
        for (dir in ScrollDirection.entries) {
            val action: NodeAction = NodeAction.Scroll(direction = dir)
            val encoded = json.encodeToString(NodeAction.serializer(), action)
            val decoded = json.decodeFromString(NodeAction.serializer(), encoded)
            decoded shouldBe action
        }
    }

    "Swipe serialization round-trip" {
        val action: NodeAction = NodeAction.Swipe(
            startX = 540, startY = 1800,
            endX = 540, endY = 600,
            durationMs = 500,
        )
        val encoded = json.encodeToString(NodeAction.serializer(), action)
        val decoded = json.decodeFromString(NodeAction.serializer(), encoded)
        decoded shouldBe action
    }

    "SetText serialization round-trip" {
        val action: NodeAction = NodeAction.SetText(elementIndex = 2, text = "Hello world")
        val encoded = json.encodeToString(NodeAction.serializer(), action)
        val decoded = json.decodeFromString(NodeAction.serializer(), encoded)
        decoded shouldBe action
    }

    "Global action serialization round-trip for all types" {
        for (type in GlobalActionType.entries) {
            val action: NodeAction = NodeAction.Global(action = type)
            val encoded = json.encodeToString(NodeAction.serializer(), action)
            val decoded = json.decodeFromString(NodeAction.serializer(), encoded)
            decoded shouldBe action
        }
    }

    "Wait serialization round-trip" {
        val action: NodeAction = NodeAction.Wait
        val encoded = json.encodeToString(NodeAction.serializer(), action)
        val decoded = json.decodeFromString(NodeAction.serializer(), encoded)
        decoded shouldBe action
    }

    "ActionOutcome.Success serialization round-trip" {
        val outcome: ActionOutcome = ActionOutcome.Success("Tapped Like button")
        val encoded = json.encodeToString(ActionOutcome.serializer(), outcome)
        val decoded = json.decodeFromString(ActionOutcome.serializer(), encoded)
        decoded shouldBe outcome
    }

    "ActionOutcome.Failed serialization round-trip" {
        val outcome: ActionOutcome = ActionOutcome.Failed("Element not clickable")
        val encoded = json.encodeToString(ActionOutcome.serializer(), outcome)
        val decoded = json.decodeFromString(ActionOutcome.serializer(), encoded)
        decoded shouldBe outcome
    }

    "ActionOutcome.NodeNotFound serialization round-trip" {
        val outcome: ActionOutcome = ActionOutcome.NodeNotFound(elementIndex = 99)
        val encoded = json.encodeToString(ActionOutcome.serializer(), outcome)
        val decoded = json.decodeFromString(ActionOutcome.serializer(), encoded)
        decoded shouldBe outcome
    }

    "ActionOutcome.ServiceUnavailable serialization round-trip" {
        val outcome: ActionOutcome = ActionOutcome.ServiceUnavailable
        val encoded = json.encodeToString(ActionOutcome.serializer(), outcome)
        val decoded = json.decodeFromString(ActionOutcome.serializer(), encoded)
        decoded shouldBe outcome
    }

    "Tap JSON includes type discriminator" {
        val encoded = json.encodeToString(NodeAction.serializer(), NodeAction.Tap(0))
        encoded.contains("\"type\":\"tap\"") shouldBe true
    }

    "GlobalActionType has correct Android constants" {
        GlobalActionType.BACK.androidConstant shouldBe 1
        GlobalActionType.HOME.androidConstant shouldBe 2
        GlobalActionType.RECENTS.androidConstant shouldBe 3
        GlobalActionType.NOTIFICATIONS.androidConstant shouldBe 4
        GlobalActionType.QUICK_SETTINGS.androidConstant shouldBe 5
    }
})
