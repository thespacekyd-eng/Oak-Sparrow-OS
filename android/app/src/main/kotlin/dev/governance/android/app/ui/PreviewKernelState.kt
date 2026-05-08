package dev.governance.android.app.ui

import dev.governance.attestation.DecisionSigner
import dev.governance.attestation.EphemeralKeyProvider
import dev.governance.audit.AuditEntry
import dev.governance.core.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Preview-friendly fakes for Compose previews and Paparazzi snapshots.
 * All attestations are real (signed with an ephemeral key) so
 * `AttestationVerifier.verify` returns true.
 */
object PreviewKernelState {

    private val keyProvider = EphemeralKeyProvider()
    private val signer = DecisionSigner(keyProvider)
    // Fixed timestamp for deterministic Paparazzi snapshots (2023-11-14 09:46:40 UTC)
    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    val snapshot = GovernanceSnapshot(
        gamma = 0.37,
        recentEntropyAverage = 0.12,
        recentDivergenceAverage = 0.08,
        recentOutcomes = OutcomeCounts(pass = 42, hold = 5, veto = 1, window = 24.hours),
        referenceEnvelopeDescription = "Defensive-prior envelope (steady state)",
        warmupComplete = true,
        timestamp = now,
    )

    fun makeDecision(
        kind: String = "send_email",
        outcome: Outcome = Outcome.PASS,
        reversibility: Reversibility = Reversibility.FullyReversible,
        minutesAgo: Int = 0,
        seq: Long = 0L,
    ): GateDecision {
        val ts = now - minutesAgo.minutes
        return signer.sign(
            outcome = outcome,
            actionId = ActionId("preview-${kind}-$minutesAgo"),
            actionKind = kind,
            gamma = 0.37,
            entropy = 0.12,
            divergence = 0.08,
            reversibility = reversibility,
            violatedBarriers = emptyList(),
            rationale = "Preview decision",
            timestamp = ts,
            sequenceNumber = seq,
        )
    }

    val recentDecisions: List<GateDecision> = listOf(
        makeDecision("send_email", Outcome.PASS, minutesAgo = 2, seq = 0),
        makeDecision("delete_file", Outcome.VETO, Reversibility.Irreversible, minutesAgo = 5, seq = 1),
        makeDecision("post_social", Outcome.HOLD, Reversibility.OneShot, minutesAgo = 12, seq = 2),
        makeDecision("read_file", Outcome.PASS, minutesAgo = 18, seq = 3),
        makeDecision("send_message", Outcome.PASS, minutesAgo = 30, seq = 4),
    )

    val auditRecords: List<AuditRecord> = recentDecisions.mapIndexed { i, d ->
        AuditRecord(
            auditId = d.auditId,
            proposedAction = ProposedAction(d.actionId, d.actionKind, d.reversibility),
            stateBefore = GovernanceState(
                gamma = 0.37,
                referenceEnvelope = ReferenceEnvelope(listOf(0.7, 0.2, 0.1), 2.0, "preview"),
                recentHistory = emptyList(),
                decisionsObserved = i.toLong(),
                timestamp = d.timestamp,
            ),
            decision = d,
            timestamp = d.timestamp,
        )
    }

    val systemEvents: List<AuditEntry.SystemEvent> = listOf(
        AuditEntry.SystemEvent(SystemEventRecord(
            timestamp = now - 10.minutes,
            kind = "boot",
            message = "Fresh defensive-prior state initialized",
            severity = SystemEventRecord.Severity.INFO,
        )),
        AuditEntry.SystemEvent(SystemEventRecord(
            timestamp = now - 5.minutes,
            kind = "accessibility_window",
            message = "Window changed: com.google.android.gm.ComposeActivityGmail",
            severity = SystemEventRecord.Severity.INFO,
        )),
    )
}
