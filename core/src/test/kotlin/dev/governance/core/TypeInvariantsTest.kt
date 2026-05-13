package dev.governance.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.datetime.Instant

class TypeInvariantsTest : FunSpec({

    val ts = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    val envelope = ReferenceEnvelope(listOf(0.5, 0.3, 0.2), 1.0, "test")

    test("GovernanceState rejects gamma outside [0, 1]") {
        checkAll(200, Arb.double()) { g ->
            val valid = g in 0.0..1.0
            val result = runCatching {
                GovernanceState(g, envelope, emptyList(), 0, ts)
            }
            result.isSuccess shouldBe valid
        }
    }

    test("GovernanceSnapshot rejects gamma outside [0, 1]") {
        val outcomes = OutcomeCounts(0, 0, 0, kotlin.time.Duration.ZERO)
        checkAll(200, Arb.double()) { g ->
            val valid = g in 0.0..1.0
            val result = runCatching {
                GovernanceSnapshot(g, 0.0, 0.0, outcomes, "d", false, ts)
            }
            result.isSuccess shouldBe valid
        }
    }

    test("ProposedAction rejects empty id") {
        val result = runCatching {
            ProposedAction(ActionId(""), "kind", Reversibility.FullyReversible)
        }
        result.isFailure shouldBe true
    }

    test("ProposedAction accepts non-empty id") {
        checkAll(50, Arb.string(1..20)) { id ->
            val result = runCatching {
                ProposedAction(ActionId(id), "kind", Reversibility.FullyReversible)
            }
            result.isSuccess shouldBe true
        }
    }

    test("DecisionAttestation rejects empty fields") {
        listOf(
            Triple("", "sig", "key"),
            Triple("hash", "", "key"),
            Triple("hash", "sig", ""),
        ).forEach { (h, s, k) ->
            val result = runCatching { DecisionAttestation(h, s, k) }
            result.isFailure shouldBe true
        }
    }

    test("DecisionAttestation accepts non-empty fields") {
        val result = runCatching { DecisionAttestation("h", "s", "k") }
        result.isSuccess shouldBe true
    }
})
