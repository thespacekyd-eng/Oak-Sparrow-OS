package dev.governance.attestation

import java.util.Locale

// Locale.ROOT: %x is locale-independent, pinned for consistency with audit-output discipline
/** Encode a byte array as a lowercase hex string. */
internal fun ByteArray.toHex(): String = joinToString("") { String.format(Locale.ROOT, "%02x", it) }

/** Decode a hex string to a byte array. */
internal fun String.hexToBytes(): ByteArray {
    check(length % 2 == 0) { "Hex string must have even length, got $length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
