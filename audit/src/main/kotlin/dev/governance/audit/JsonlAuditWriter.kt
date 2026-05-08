package dev.governance.audit

import dev.governance.core.AuditRecord
import dev.governance.core.AuditWriter
import dev.governance.core.SystemEventRecord
import dev.governance.attestation.CanonicalJson
import kotlinx.serialization.json.Json
import java.io.Writer
import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createFile
import kotlin.io.path.exists

/**
 * Writes [AuditRecord] entries as JSONL (one JSON object per line) to a file.
 * Each line is canonical JSON with sorted keys for replay determinism.
 */
class JsonlAuditWriter private constructor(
    private val writer: (String) -> Unit,
) : AuditWriter {

    /** Write to a file path. Creates the file if it doesn't exist. */
    constructor(path: Path) : this({ line ->
        if (!path.exists()) path.createFile()
        path.appendText(line + "\n")
    })

    /** Write to a [Writer] (for testing). */
    constructor(writer: Writer) : this({ line ->
        writer.write(line)
        writer.write("\n")
        writer.flush()
    })

    override fun write(record: AuditRecord) {
        val json = CanonicalJson.encode(AuditRecord.serializer(), record)
        writer(json)
    }

    override fun writeSystemEvent(event: SystemEventRecord) {
        val json = CanonicalJson.encode(SystemEventRecord.serializer(), event)
        writer(json)
    }
}
