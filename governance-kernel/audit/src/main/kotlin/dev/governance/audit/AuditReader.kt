package dev.governance.audit

import dev.governance.core.AuditRecord
import dev.governance.core.SystemEventRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.Reader
import java.nio.file.Path
import kotlin.io.path.bufferedReader

/**
 * Reads JSONL audit logs containing both [AuditRecord] decision entries
 * and [SystemEventRecord] system events.
 *
 * Decision records are identified by the absence of a `"type"` field
 * (backward compatible with pre-system-event logs). System events have
 * `"type": "system_event"`.
 */
object AuditReader {
    private val json = Json { ignoreUnknownKeys = true }

    /** Read all entries (decisions + system events) from a file path. */
    fun readAllEntries(path: Path): List<AuditEntry> =
        readAllEntries(path.bufferedReader())

    /** Read all entries (decisions + system events) from a [Reader]. */
    fun readAllEntries(reader: Reader): List<AuditEntry> {
        val buffered = if (reader is BufferedReader) reader else reader.buffered()
        return buffered.lineSequence()
            .filter { it.isNotBlank() }
            .map { line -> parseLine(line) }
            .toList()
    }

    /** Read only decision records, skipping system events. Backward compatible. */
    fun readAll(path: Path): List<AuditRecord> =
        readAll(path.bufferedReader())

    /** Read only decision records, skipping system events. Backward compatible. */
    fun readAll(reader: Reader): List<AuditRecord> =
        readAllEntries(reader).filterIsInstance<AuditEntry.Decision>().map { it.record }

    private fun parseLine(line: String): AuditEntry {
        val obj = json.parseToJsonElement(line).jsonObject
        val type = obj["type"]?.jsonPrimitive?.content
        return if (type == "system_event") {
            AuditEntry.SystemEvent(json.decodeFromString(SystemEventRecord.serializer(), line))
        } else {
            AuditEntry.Decision(json.decodeFromString(AuditRecord.serializer(), line))
        }
    }

    /**
     * Verify that every decision record's audit ID matches the content hash
     * in its decision attestation. Returns a list of (index, record)
     * pairs for records that fail verification.
     */
    fun verifyIntegrity(records: List<AuditRecord>): List<Pair<Int, AuditRecord>> {
        return records.mapIndexedNotNull { index, record ->
            if (record.auditId.value != record.decision.attestation.contentHash) {
                index to record
            } else {
                null
            }
        }
    }
}
