package dev.governance.metrics

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Simple CLI visualization for stage/demo explainability.
 *
 * Label column is padded to 28 chars to fit the longest real rule ID
 * (`noIrreversibleSystemActions`, 27 chars) without truncation.
 */
object GovernanceMetricsCli {

    private const val LABEL_PAD = 28

    fun render(
        metrics: GovernanceMetrics,
        standardRules: List<GovernanceRule> = Rules.standard,
        width: Int = 32,
    ): String {
        val safeWidth = width.coerceAtLeast(8)
        val lines = mutableListOf<String>()

        lines += "Governance Metrics"
        lines += "=================="
        lines += ""
        lines += "Total decisions:     ${metrics.totalDecisions}"
        lines += "Approvals:           ${metrics.approvals} (${percent(metrics.approvalRate)})"
        lines += "Rejections:          ${metrics.rejections} (${percent(metrics.rejectionRate)})"
        lines += "Average plan size:   ${formatDouble(metrics.averagePlanSize)}"
        lines += ""
        lines += "Decision outcomes"
        lines += bar("Approved", metrics.approvals, metrics.totalDecisions, safeWidth)
        lines += bar("Rejected", metrics.rejections, metrics.totalDecisions, safeWidth)
        lines += ""
        lines += "Rule-trigger frequencies"

        if (standardRules.isEmpty()) {
            lines += "(no standard rules configured)"
        } else {
            val maxRuleCount = metrics.ruleTriggerFrequencies.values.maxOrNull()?.coerceAtLeast(1) ?: 1
            standardRules.forEach { rule ->
                val count = metrics.ruleTriggerFrequencies[rule.id] ?: 0
                lines += bar(rule.id, count, maxRuleCount, safeWidth)
            }
        }

        return lines.joinToString(separator = "\n")
    }

    private fun bar(label: String, value: Int, max: Int, width: Int): String {
        val filled = if (max <= 0) 0 else ((value.toDouble() / max.toDouble()) * width).roundToInt()
        val empty = width - filled
        return "${label.padEnd(LABEL_PAD)} | ${"█".repeat(filled)}${" ".repeat(empty)} | $value"
    }

    private fun percent(value: Double): String {
        return "${formatDouble(value * 100.0)}%"
    }

    private fun formatDouble(value: Double): String {
        return String.format(Locale.ROOT, "%.2f", value)
    }
}
