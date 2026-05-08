package dev.governance.audit

import dev.governance.core.*
import dev.governance.attestation.DecisionSigner
import dev.governance.attestation.EphemeralKeyProvider
import dev.governance.attestation.AttestationVerifier
import dev.governance.testing.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Clock
import java.io.StringWriter

class AuditRoundTripTest : FunSpec({

    test("SystemEventRecord round-trips through JSONL") {
        val stringWriter = StringWriter()
        val auditWriter = JsonlAuditWriter(stringWriter)

        val event = dev.governance.core.SystemEventRecord(
            timestamp = Clock.System.now(),
            kind = "boot",
            message = "governance_boot: fresh defensive-prior state initialized",
            severity = dev.governance.core.SystemEventRecord.Severity.INFO,
        )
        auditWriter.writeSystemEvent(event)

        val entries = AuditReader.readAllEntries(stringWriter.toString().reader())
        entries.size shouldBe 1
        val entry = entries[0]
        (entry is AuditEntry.SystemEvent) shouldBe true
        val restored = (entry as AuditEntry.SystemEvent).event
        restored.kind shouldBe "boot"
        restored.message shouldBe event.message
        restored.severity shouldBe dev.governance.core.SystemEventRecord.Severity.INFO
    }

    test("mixed decision and system event records parse correctly") {
        val keyProvider = EphemeralKeyProvider()
        val signer = DecisionSigner(keyProvider)
        val stringWriter = StringWriter()
        val auditWriter = JsonlAuditWriter(stringWriter)

        val state = Fixtures.defaultState()
        val timestamp = Clock.System.now()

        // Write a decision
        val action = Fixtures.proposedAction(kind = "read_file")
        val decision = signer.sign(
            outcome = Outcome.PASS, actionId = action.id, actionKind = action.kind,
            gamma = 0.3, entropy = 0.2, divergence = 0.1,
            reversibility = Reversibility.FullyReversible, violatedBarriers = emptyList(),
            rationale = "PASS: test", timestamp = timestamp, sequenceNumber = 0L,
        )
        auditWriter.write(AuditRecord(
            auditId = decision.auditId, proposedAction = action,
            stateBefore = state, decision = decision, timestamp = timestamp,
        ))

        // Write a system event
        auditWriter.writeSystemEvent(dev.governance.core.SystemEventRecord(
            timestamp = timestamp, kind = "test",
            message = "test event",
            severity = dev.governance.core.SystemEventRecord.Severity.WARN,
        ))

        // readAllEntries returns both
        val allEntries = AuditReader.readAllEntries(stringWriter.toString().reader())
        allEntries.size shouldBe 2
        (allEntries[0] is AuditEntry.Decision) shouldBe true
        (allEntries[1] is AuditEntry.SystemEvent) shouldBe true

        // readAll returns only decisions
        val decisions = AuditReader.readAll(stringWriter.toString().reader())
        decisions.size shouldBe 1
        decisions[0].decision.outcome shouldBe Outcome.PASS
    }

    test("GovernanceState round-trips through serialize/deserialize under Locale.GERMANY") {
        val savedLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)

            val json = kotlinx.serialization.json.Json { prettyPrint = false; encodeDefaults = true }
            val state = Fixtures.defaultState(gamma = 0.123456789)

            val serialized = json.encodeToString(
                dev.governance.core.GovernanceState.serializer(), state)
            val deserialized = json.decodeFromString(
                dev.governance.core.GovernanceState.serializer(), serialized)

            deserialized shouldBe state
        } finally {
            java.util.Locale.setDefault(savedLocale)
        }
    }

    test("write JSONL and read back with matching hashes") {
        val keyProvider = EphemeralKeyProvider()
        val signer = DecisionSigner(keyProvider)
        val stringWriter = StringWriter()
        val auditWriter = JsonlAuditWriter(stringWriter)

        val state = Fixtures.defaultState()
        val action = Fixtures.proposedAction(kind = "read_file")
        val timestamp = Clock.System.now()

        val decision = signer.sign(
            outcome = Outcome.PASS,
            actionId = action.id,
            actionKind = action.kind,
            gamma = 0.3,
            entropy = 0.2,
            divergence = 0.1,
            reversibility = Reversibility.FullyReversible,
            violatedBarriers = emptyList(),
            rationale = "PASS: test",
            timestamp = timestamp,
            sequenceNumber = 0L,
        )

        val record = AuditRecord(
            auditId = decision.auditId,
            proposedAction = action,
            stateBefore = state,
            decision = decision,
            timestamp = timestamp,
        )

        auditWriter.write(record)

        // Read back
        val records = AuditReader.readAll(stringWriter.toString().reader())
        records shouldHaveSize 1

        val readBack = records[0]
        readBack.auditId shouldBe record.auditId
        readBack.decision.outcome shouldBe Outcome.PASS
        readBack.decision.attestation.contentHash shouldBe decision.attestation.contentHash

        // Verify content integrity
        val failures = AuditReader.verifyIntegrity(records)
        failures shouldHaveSize 0
    }

    test("multiple records round-trip correctly") {
        val keyProvider = EphemeralKeyProvider()
        val signer = DecisionSigner(keyProvider)
        val stringWriter = StringWriter()
        val auditWriter = JsonlAuditWriter(stringWriter)

        val state = Fixtures.defaultState()
        val timestamp = Clock.System.now()

        val outcomes = listOf(Outcome.PASS, Outcome.HOLD, Outcome.VETO)
        val records = outcomes.mapIndexed { i, outcome ->
            val action = Fixtures.proposedAction(id = "action-$i", kind = "action_$i")
            val decision = signer.sign(
                outcome = outcome,
                actionId = action.id,
                actionKind = action.kind,
                gamma = 0.3 + i * 0.2,
                entropy = 0.1 + i * 0.1,
                divergence = 0.05 + i * 0.05,
                reversibility = Reversibility.FullyReversible,
                violatedBarriers = emptyList(),
                rationale = "$outcome: test record $i",
                timestamp = timestamp,
                sequenceNumber = i.toLong(),
            )
            AuditRecord(
                auditId = decision.auditId,
                proposedAction = action,
                stateBefore = state,
                decision = decision,
                timestamp = timestamp,
            ).also { auditWriter.write(it) }
        }

        val readBack = AuditReader.readAll(stringWriter.toString().reader())
        readBack shouldHaveSize 3

        readBack.forEachIndexed { i, record ->
            record.auditId shouldBe records[i].auditId
            record.decision.outcome shouldBe outcomes[i]
        }

        AuditReader.verifyIntegrity(readBack) shouldHaveSize 0
    }

    test("attestation verifies on round-tripped records") {
        val keyProvider = EphemeralKeyProvider()
        val signer = DecisionSigner(keyProvider)
        val stringWriter = StringWriter()
        val auditWriter = JsonlAuditWriter(stringWriter)
        val state = Fixtures.defaultState()
        val timestamp = Clock.System.now()

        val action = Fixtures.proposedAction()
        val decision = signer.sign(
            outcome = Outcome.HOLD,
            actionId = action.id,
            actionKind = action.kind,
            gamma = 0.7,
            entropy = 0.5,
            divergence = 0.3,
            reversibility = Reversibility.PartiallyReversible,
            violatedBarriers = emptyList(),
            rationale = "HOLD: test",
            timestamp = timestamp,
            sequenceNumber = 0L,
        )

        val record = AuditRecord(
            auditId = decision.auditId,
            proposedAction = action,
            stateBefore = state,
            decision = decision,
            timestamp = timestamp,
        )
        auditWriter.write(record)

        val readBack = AuditReader.readAll(stringWriter.toString().reader())
        // The attestation should still verify after serialization round-trip
        AttestationVerifier.verify(readBack[0].decision) shouldBe true
    }
})
