package dev.governance.core

import kotlinx.serialization.Serializable
import kotlin.math.ln

/**
 * Real-world cost estimate for an action kind. The gate applies a gamma bias
 * proportional to [impactWeight] so expensive or high-blast-radius actions
 * face stricter scrutiny even at the same reversibility tier.
 *
 * Ref: von Neumann & Morgenstern, Theory of Games (1944) — expected utility
 *      Kahneman & Tversky, Econometrica 47:263 (1979) — loss aversion
 */
@Serializable
data class ActionCost(
    /** Dimensionless scalar >= 1.0. 1.0 = baseline, higher = more scrutiny. */
    val impactWeight: Double,
    val description: String,
) {
    init {
        require(impactWeight >= 1.0) { "impactWeight must be >= 1.0, got $impactWeight" }
    }
}

/**
 * Maps action kinds to their estimated real-world cost. The gate queries
 * [costBias] during evaluation to add a cost-proportional gamma adjustment
 * alongside the existing reversibility bias.
 *
 * Unknown kinds receive a cautious default (weight 1.5).
 */
object ActionCostRegistry {

    private val DEFAULT_COST = ActionCost(1.5, "Unknown action kind (cautious default)")

    private val COSTS = mapOf(
        // Low-impact: read-only or trivially reversible
        "read_calendar"       to ActionCost(1.0, "Read-only calendar access"),
        "open_app"            to ActionCost(1.0, "Launch an application"),
        "read_file"           to ActionCost(1.0, "Read-only file access"),

        // Medium-impact: directed communication
        "send_email"          to ActionCost(2.5, "Send message to specific recipient"),

        // High-impact: broad or public exposure
        "share_to_social_app" to ActionCost(4.0, "Public or semi-public social post"),

        // Privileged system operations (also gated by ActionTier.RootSystem -> HOLD)
        "shell_exec"          to ActionCost(6.0, "Arbitrary shell command execution"),
        "package_install"     to ActionCost(5.0, "Install software on device"),
        "package_uninstall"   to ActionCost(4.5, "Remove installed software"),
        "settings_put"        to ActionCost(3.5, "Modify device settings"),
        "network_control"     to ActionCost(5.0, "Modify network configuration"),
        "file_system_write"   to ActionCost(4.0, "Write outside app sandbox"),
    )

    /** Look up the cost for an action kind. Unknown kinds get [DEFAULT_COST]. */
    fun lookup(kind: String): ActionCost = COSTS[kind] ?: DEFAULT_COST

    /** The impact weight for an action kind. */
    fun impactWeight(kind: String): Double = lookup(kind).impactWeight

    /**
     * Compute a gamma bias contribution from the action's cost.
     *
     * Uses log-scaling so high-cost actions increase scrutiny without
     * overwhelming the reversibility signal:
     *
     *   costBias = ln(impactWeight) * sensitivity
     *
     * With default sensitivity 0.05:
     *   weight 1.0 -> bias 0.000
     *   weight 2.5 -> bias 0.046
     *   weight 4.0 -> bias 0.069
     *   weight 6.0 -> bias 0.090
     */
    fun costBias(kind: String, sensitivity: Double = 0.05): Double {
        val weight = impactWeight(kind)
        return ln(weight) * sensitivity
    }
}
