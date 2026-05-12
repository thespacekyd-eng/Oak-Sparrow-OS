package dev.governance.attestation

import kotlinx.serialization.json.*

/**
 * Produces deterministic canonical JSON for content-addressing and attestation.
 * All object keys are sorted lexicographically at every nesting level.
 * Output uses no whitespace beyond what JSON syntax requires.
 */
object CanonicalJson {
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    /** Recursively sort all object keys in a [JsonElement] tree. */
    fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { it.key }
                .associate { (k, v) -> k to canonicalize(v) }
        )
        is JsonArray -> JsonArray(element.map { canonicalize(it) })
        is JsonPrimitive -> element
    }

    /**
     * Serialize [value] to canonical JSON string with sorted keys.
     * The output is deterministic across runs for identical inputs.
     */
    fun <T> encode(serializer: kotlinx.serialization.KSerializer<T>, value: T): String {
        val element = json.encodeToJsonElement(serializer, value)
        return canonicalize(element).toString()
    }

    /** Serialize a [JsonElement] to canonical JSON string. */
    fun encodeElement(element: JsonElement): String {
        return canonicalize(element).toString()
    }
}
