package dev.governance.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class ActionCostTest : StringSpec({

    "lookup returns known cost for read_calendar" {
        ActionCostRegistry.lookup("read_calendar").impactWeight shouldBe 1.0
    }

    "lookup returns cautious default for unknown kind" {
        ActionCostRegistry.lookup("unknown_action").impactWeight shouldBe 1.5
    }

    "impactWeight is at least 1.0 for all registered kinds" {
        val kinds = listOf(
            "read_calendar", "open_app", "read_file",
            "send_email", "share_to_social_app",
            "shell_exec", "package_install", "package_uninstall",
            "settings_put", "network_control", "file_system_write",
        )
        for (kind in kinds) {
            ActionCostRegistry.impactWeight(kind) shouldBeGreaterThan 0.99
        }
    }

    "costBias is zero for weight-1.0 actions" {
        abs(ActionCostRegistry.costBias("read_calendar")) shouldBeLessThan 0.001
    }

    "costBias increases with impact weight" {
        val low = ActionCostRegistry.costBias("read_calendar")   // weight 1.0
        val mid = ActionCostRegistry.costBias("send_email")       // weight 2.5
        val high = ActionCostRegistry.costBias("shell_exec")      // weight 6.0
        mid shouldBeGreaterThan low
        high shouldBeGreaterThan mid
    }

    "costBias scales with sensitivity parameter" {
        val normal = ActionCostRegistry.costBias("send_email", sensitivity = 0.05)
        val double = ActionCostRegistry.costBias("send_email", sensitivity = 0.10)
        abs(double - 2.0 * normal) shouldBeLessThan 0.001
    }

    "share_to_social_app has higher cost than send_email" {
        ActionCostRegistry.impactWeight("share_to_social_app") shouldBeGreaterThan
            ActionCostRegistry.impactWeight("send_email")
    }
})
