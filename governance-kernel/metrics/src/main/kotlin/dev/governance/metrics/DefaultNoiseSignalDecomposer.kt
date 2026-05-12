package dev.governance.metrics

import dev.governance.core.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Default noise-signal decomposer: classifies telemetry entries as signal
 * (structured, known keys) or noise (unknown/malformed keys).
 *
 * **Placeholder; replace with framework formalization before deployment.**
 *
 * Limitations:
 * - Uses a simple allowlist of known signal keys. Any key not in the allowlist
 *   is classified as noise.
 * - Does not perform statistical decomposition, spectral analysis, or learned
 *   separation.
 * - The allowlist is hardcoded; a real implementation should be configurable
 *   or learned from a training phase.
 *
 * @param signalKeys the set of telemetry keys considered structured signal
 */
class DefaultNoiseSignalDecomposer(
    private val signalKeys: Set<String> = DEFAULT_SIGNAL_KEYS,
) : NoiseSignalDecomposer {

    override fun split(telemetry: AgentTelemetry): DecomposedTelemetry {
        val signal = mutableMapOf<String, JsonElement>()
        val noise = mutableMapOf<String, JsonElement>()

        for ((key, value) in telemetry.entries) {
            if (key in signalKeys && isWellFormed(value)) {
                signal[key] = value
            } else {
                noise[key] = value
            }
        }

        return DecomposedTelemetry(signal = signal, noise = noise)
    }

    /** Reject obviously malformed values (nulls, empty strings, extreme numbers). */
    private fun isWellFormed(element: JsonElement): Boolean {
        if (element !is JsonPrimitive) return true // objects/arrays pass through
        if (element.isString && element.content.isEmpty()) return false
        val doubleValue = element.content.toDoubleOrNull()
        if (doubleValue != null && (doubleValue.isNaN() || doubleValue.isInfinite())) return false
        return true
    }

    companion object {
        val DEFAULT_SIGNAL_KEYS = setOf(
            "action_type", "target", "confidence", "intent",
            "context", "priority", "source", "destination",
        )
    }
}
