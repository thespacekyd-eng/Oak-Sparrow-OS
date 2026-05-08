package dev.governance.android.platform

import dev.governance.android.platform.parcel.*
import dev.governance.core.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.hours

/**
 * Verifies that every kernel data class survives JSON-based parcel
 * round-trip with non-default values. This test runs on JVM (no Android
 * framework needed) because the wrappers use JSON serialization internally.
 *
 * NOTE: Actual Parcel read/write requires an Android environment and is
 * covered by instrumentation tests in :android-app. This test validates
 * the JSON serialization layer that the Parcel wrappers depend on.
 */
class ParcelRoundTripTest : FunSpec({

    val timestamp = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    test("ProposedAction round-trips through JSON parcel wrapper") {
        val original = ProposedAction(
            id = ActionId("test-action-42"),
            kind = "send_email",
            reversibility = Reversibility.OneShot,
            payload = mapOf("target" to JsonPrimitive("user@example.com")),
        )
        val parcel = ProposedActionParcel.from(original)
        val restored = parcel.toKernel()
        restored shouldBe original
    }

    test("GateDecision round-trips with full attestation") {
        val original = GateDecision(
            outcome = Outcome.HOLD,
            actionId = ActionId("decision-99"),
            actionKind = "transfer_funds",
            gamma = 0.73,
            entropy = 0.45,
            divergence = 0.22,
            reversibility = Reversibility.Irreversible,
            violatedBarriers = listOf("MonotonicityViolation", "TestBarrier"),
            auditId = AuditId("abcdef0123456789"),
            rationale = "HOLD: high gamma with irreversible action",
            attestation = DecisionAttestation(
                contentHash = "deadbeef",
                signature = "cafebabe",
                publicKey = "0011223344",
            ),
            timestamp = timestamp,
            sequenceNumber = 42L,
        )
        val parcel = GateDecisionParcel.from(original)
        val restored = parcel.toKernel()
        restored shouldBe original
        // Attestation must be preserved exactly — no re-signing
        restored.attestation shouldBe original.attestation
        restored.sequenceNumber shouldBe original.sequenceNumber
    }

    test("GovernanceSnapshot round-trips") {
        val original = GovernanceSnapshot(
            gamma = 0.37,
            recentEntropyAverage = 0.12,
            recentDivergenceAverage = 0.08,
            recentOutcomes = OutcomeCounts(
                pass = 15,
                hold = 7,
                veto = 2,
                window = 1.hours,
            ),
            referenceEnvelopeDescription = "test envelope description",
            warmupComplete = true,
            timestamp = timestamp,
        )
        val parcel = GovernanceSnapshotParcel.from(original)
        val restored = parcel.toKernel()
        restored shouldBe original
    }

    test("ResolvedOutcome.BenignSuccess round-trips") {
        val original = ResolvedOutcome.BenignSuccess
        val parcel = ResolvedOutcomeParcel.from(original)
        val restored = parcel.toKernel()
        restored shouldBe original
    }

    test("ResolvedOutcome.HarmlessFailure round-trips") {
        val original = ResolvedOutcome.HarmlessFailure
        val parcel = ResolvedOutcomeParcel.from(original)
        val restored = parcel.toKernel()
        restored shouldBe original
    }

    test("ResolvedOutcome.Flagged round-trips with reason") {
        val original = ResolvedOutcome.Flagged("suspicious network activity detected")
        val parcel = ResolvedOutcomeParcel.from(original)
        val restored = parcel.toKernel()
        restored shouldBe original
    }
})
