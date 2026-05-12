package dev.governance.audit

import dev.governance.core.AuditRecord
import dev.governance.core.SystemEventRecord

/**
 * A single entry in the audit log — either a kernel [Decision] record
 * or a [SystemEvent] that bypassed the kernel.
 */
sealed interface AuditEntry {
    data class Decision(val record: AuditRecord) : AuditEntry
    data class SystemEvent(val event: SystemEventRecord) : AuditEntry
}
