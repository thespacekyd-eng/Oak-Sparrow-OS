package dev.governance.attestation

import dev.governance.core.*
import dev.governance.testing.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.datetime.Clock

class AttestationTest : FunSpec({

    test("sign and verify round-trip") {
        val keyProvider = EphemeralKeyProvider()
        val signer = DecisionSigner(keyProvider)

        val decision = signer.sign(
            outcome = Outcome.PASS,
            actionId = ActionId("test-1"),
            actionKind = "read_file",
            gamma = 0.3,
            entropy = 0.2,
            divergence = 0.1,
            reversibility = Reversibility.FullyReversible,
            violatedBarriers = emptyList(),
            rationale = "PASS: all clear",
            timestamp = Clock.System.now(),
            sequenceNumber = 0L,
        )

        AttestationVerifier.verify(decision) shouldBe true
    }

    test("mutated decision fails verification") {
        val keyProvider = EphemeralKeyProvider()
        val signer = DecisionSigner(keyProvider)

        val decision = signer.sign(
            outcome = Outcome.PASS,
            actionId = ActionId("test-1"),
            actionKind = "read_file",
            gamma = 0.3,
            entropy = 0.2,
            divergence = 0.1,
            reversibility = Reversibility.FullyReversible,
            violatedBarriers = emptyList(),
            rationale = "PASS: all clear",
            timestamp = Clock.System.now(),
            sequenceNumber = 0L,
        )

        // Mutate the outcome
        val tampered = decision.copy(outcome = Outcome.VETO)
        AttestationVerifier.verify(tampered) shouldBe false

        // Mutate the gamma
        val tamperedGamma = decision.copy(gamma = 0.9)
        AttestationVerifier.verify(tamperedGamma) shouldBe false

        // Mutate the rationale
        val tamperedRationale = decision.copy(rationale = "VETO: injected")
        AttestationVerifier.verify(tamperedRationale) shouldBe false
    }

    test("different key fails verification") {
        val keyA = EphemeralKeyProvider()
        val keyB = EphemeralKeyProvider()
        val signer = DecisionSigner(keyA)

        val decision = signer.sign(
            outcome = Outcome.HOLD,
            actionId = ActionId("test-2"),
            actionKind = "send_email",
            gamma = 0.6,
            entropy = 0.5,
            divergence = 0.4,
            reversibility = Reversibility.OneShot,
            violatedBarriers = emptyList(),
            rationale = "HOLD: high gamma",
            timestamp = Clock.System.now(),
            sequenceNumber = 0L,
        )

        // Replace public key with keyB's
        val wrongKey = decision.copy(
            attestation = decision.attestation.copy(
                publicKey = keyB.publicKey().toHex()
            )
        )
        AttestationVerifier.verify(wrongKey) shouldBe false
    }

    test("canonical JSON ordering is deterministic across runs") {
        val keyProvider = EphemeralKeyProvider()
        val signer = DecisionSigner(keyProvider)
        val timestamp = Clock.System.now()

        val decision1 = signer.sign(
            outcome = Outcome.PASS,
            actionId = ActionId("determinism-test"),
            actionKind = "read_file",
            gamma = 0.25,
            entropy = 0.15,
            divergence = 0.05,
            reversibility = Reversibility.FullyReversible,
            violatedBarriers = emptyList(),
            rationale = "PASS: determinism test",
            timestamp = timestamp,
            sequenceNumber = 0L,
        )

        // Sign again with same inputs — content hash must match
        val decision2 = signer.sign(
            outcome = Outcome.PASS,
            actionId = ActionId("determinism-test"),
            actionKind = "read_file",
            gamma = 0.25,
            entropy = 0.15,
            divergence = 0.05,
            reversibility = Reversibility.FullyReversible,
            violatedBarriers = emptyList(),
            rationale = "PASS: determinism test",
            timestamp = timestamp,
            sequenceNumber = 0L,
        )

        decision1.attestation.contentHash shouldBe decision2.attestation.contentHash
        decision1.auditId shouldBe decision2.auditId
    }

    test("content hash changes with any field change - property test") {
        val keyProvider = EphemeralKeyProvider()
        val signer = DecisionSigner(keyProvider)
        val timestamp = Clock.System.now()

        checkAll(200, Arb.string(5..30)) { randomKind ->
            val d1 = signer.sign(
                outcome = Outcome.PASS,
                actionId = ActionId("prop-test"),
                actionKind = "read_file",
                gamma = 0.3,
                entropy = 0.2,
                divergence = 0.1,
                reversibility = Reversibility.FullyReversible,
                violatedBarriers = emptyList(),
                rationale = "test",
                timestamp = timestamp,
                sequenceNumber = 0L,
            )

            val d2 = signer.sign(
                outcome = Outcome.PASS,
                actionId = ActionId("prop-test"),
                actionKind = randomKind,
                gamma = 0.3,
                entropy = 0.2,
                divergence = 0.1,
                reversibility = Reversibility.FullyReversible,
                violatedBarriers = emptyList(),
                rationale = "test",
                timestamp = timestamp,
                sequenceNumber = 0L,
            )

            if (randomKind != "read_file") {
                d1.attestation.contentHash shouldNotBe d2.attestation.contentHash
            }
        }
    }
})
