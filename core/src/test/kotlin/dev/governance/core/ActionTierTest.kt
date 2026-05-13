package dev.governance.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ActionTierTest : StringSpec({

    "classify returns RootSystem for shell_exec" {
        ActionTier.classify("shell_exec") shouldBe ActionTier.RootSystem
    }

    "classify returns RootSystem for package_install" {
        ActionTier.classify("package_install") shouldBe ActionTier.RootSystem
    }

    "classify returns RootSystem for package_uninstall" {
        ActionTier.classify("package_uninstall") shouldBe ActionTier.RootSystem
    }

    "classify returns RootSystem for settings_put" {
        ActionTier.classify("settings_put") shouldBe ActionTier.RootSystem
    }

    "classify returns RootSystem for network_control" {
        ActionTier.classify("network_control") shouldBe ActionTier.RootSystem
    }

    "classify returns RootSystem for file_system_write" {
        ActionTier.classify("file_system_write") shouldBe ActionTier.RootSystem
    }

    "classify returns App for read_calendar" {
        ActionTier.classify("read_calendar") shouldBe ActionTier.App
    }

    "classify returns App for send_email" {
        ActionTier.classify("send_email") shouldBe ActionTier.App
    }

    "classify returns App for share_to_social_app" {
        ActionTier.classify("share_to_social_app") shouldBe ActionTier.App
    }

    "classify returns App for open_app" {
        ActionTier.classify("open_app") shouldBe ActionTier.App
    }

    "classify returns App for unknown kinds" {
        ActionTier.classify("unknown_kind") shouldBe ActionTier.App
        ActionTier.classify("") shouldBe ActionTier.App
    }

    "ROOT_SYSTEM_KINDS contains exactly the six privileged kinds" {
        ActionTier.ROOT_SYSTEM_KINDS shouldBe setOf(
            "shell_exec",
            "package_install",
            "package_uninstall",
            "settings_put",
            "network_control",
            "file_system_write",
        )
    }
})
