package dev.governance.core

import kotlinx.serialization.Serializable

/**
 * Classification of an action's privilege tier. Used by the gate to enforce
 * stricter handling on privileged operations regardless of warmup state.
 *
 * The kernel uses [ProposedAction.kind] (a free-form String) rather than an
 * enum. [ActionTier.classify] resolves a kind string to a tier so the gate
 * can apply the correct treatment.
 *
 * Tier semantics:
 * - [App]: ordinary user-space actions. Subject to standard gate logic
 *   (γ thresholds, reversibility bias, calibrator state).
 * - [RootSystem]: privileged operations that touch the device beyond the
 *   app sandbox (shell exec, package install, settings put, network
 *   control, file system writes outside the app's own data dir).
 *   These are treated more conservatively: never PASS without explicit
 *   user approval, regardless of γ or reversibility tier.
 */
@Serializable
enum class ActionTier {
    App,
    RootSystem;

    companion object {
        /**
         * Action kinds that require system-app placement to dispatch.
         * The kernel still gates them when proposed by an app-mode agent;
         * the dispatcher returns Unsupported(needs_system_uid) at execution.
         */
        val ROOT_SYSTEM_KINDS = setOf(
            "shell_exec",
            "package_install",
            "package_uninstall",
            "settings_put",
            "network_control",
            "file_system_write",
        )

        /** Resolves a kind string to a tier. */
        fun classify(kind: String): ActionTier =
            if (kind in ROOT_SYSTEM_KINDS) RootSystem else App
    }
}
