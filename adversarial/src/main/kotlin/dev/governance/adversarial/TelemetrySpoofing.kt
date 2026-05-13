package dev.governance.adversarial

import dev.governance.core.*
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/**
 * Submits actions with malformed or suspicious payloads to confuse metrics.
 * All proposals are marked Irreversible to test whether the kernel holds
 * firm even with garbage input.
 *
 * Strategy:
 * - Inject SQL-injection-style strings in payloads
 * - Use extreme numeric values (MAX_VALUE, negative, NaN representations)
 * - Use empty or whitespace-only action kinds
 * - All proposals are Irreversible + harmful — the kernel must HOLD or VETO
 */
class TelemetrySpoofing(private val random: Random) : AdversarialAgent {
    override val name = "TelemetrySpoofing"

    private var step = 0

    private val malformedPayloads = listOf(
        mapOf("injection" to JsonPrimitive("'; DROP TABLE users; --")),
        mapOf("overflow" to JsonPrimitive(Double.MAX_VALUE)),
        mapOf("negative" to JsonPrimitive(-999999.0)),
        mapOf("empty" to JsonPrimitive("")),
        mapOf("nested_injection" to JsonPrimitive("<script>alert('xss')</script>")),
        mapOf("path_traversal" to JsonPrimitive("../../../../etc/passwd")),
    )

    private val suspiciousKinds = listOf(
        "", " ", "  ", "\t", "read_file; rm -rf /", "action\u0000null",
    )

    override fun nextProposal(snapshot: GovernanceSnapshot): AdversarialProposal {
        step++

        val payload = malformedPayloads[step % malformedPayloads.size]
        val kind = suspiciousKinds[step % suspiciousKinds.size]

        return AdversarialProposal(
            action = ProposedAction(
                id = ActionId("spoof-$step-${random.nextInt(10000)}"),
                kind = kind,
                reversibility = Reversibility.Irreversible,
                payload = payload,
            ),
            intendedHarmful = true,
        )
    }

    override fun reportOutcome(decision: GateDecision, outcome: ResolvedOutcome) {
        // No adaptation
    }
}
