package dev.governance.android.testagent

import kotlinx.datetime.Clock
import kotlinx.serialization.json.*

/**
 * Pre-built JSON payloads for test scenarios. Each returns the
 * serialized ProposedAction JSON that the AIDL accepts.
 *
 * These are built by hand as JSON strings to prove the IPC
 * boundary works without importing kernel types.
 */
object Scenarios {

    private val json = Json { encodeDefaults = true }

    fun readCalendar(): String = buildAction(
        id = "test-read-${Clock.System.now().toEpochMilliseconds()}",
        kind = "read_file",
        reversibility = "FullyReversible",
    )

    fun sendEmail(): String = buildAction(
        id = "test-email-${Clock.System.now().toEpochMilliseconds()}",
        kind = "send_email",
        reversibility = "OneShot",
    )

    fun postPhotos(): String = buildAction(
        id = "test-post-${Clock.System.now().toEpochMilliseconds()}",
        kind = "post_social",
        reversibility = "Irreversible",
    )

    fun rateLimiterBurst(index: Int): String = buildAction(
        id = "test-burst-$index-${Clock.System.now().toEpochMilliseconds()}",
        kind = "read_file",
        reversibility = "FullyReversible",
    )

    fun escalation(level: Int): String {
        val tiers = listOf("FullyReversible", "PartiallyReversible", "OneShot", "Irreversible")
        val kinds = listOf("read_file", "write_file", "send_message", "make_payment")
        return buildAction(
            id = "test-escalate-$level-${Clock.System.now().toEpochMilliseconds()}",
            kind = kinds[level.coerceIn(0, 3)],
            reversibility = tiers[level.coerceIn(0, 3)],
        )
    }

    fun benignOutcome(): String =
        """{"type":"dev.governance.core.ResolvedOutcome.BenignSuccess"}"""

    /**
     * Takes a real GateDecision JSON from the kernel and corrupts its
     * attestation signature by flipping one character. Used to demonstrate
     * that downstream verification rejects tampered decisions.
     *
     * Returns a pair of (original signature prefix, tampered signature prefix)
     * along with the tampered JSON.
     */
    fun tamperedAttestation(originalDecisionJson: String): Triple<String, String, String> {
        val parsed = Json.parseToJsonElement(originalDecisionJson).jsonObject
        val attestation = parsed["attestation"]!!.jsonObject
        val sig = attestation["signature"]!!.jsonPrimitive.content
        val tamperedSig = sig.toCharArray().also {
            val mid = it.size / 2
            it[mid] = if (it[mid] == 'a') 'b' else 'a'
        }.concatToString()

        val tamperedJson = buildJsonObject {
            parsed.forEach { (key, value) ->
                if (key == "attestation") {
                    put("attestation", buildJsonObject {
                        attestation.forEach { (ak, av) ->
                            if (ak == "signature") put(ak, JsonPrimitive(tamperedSig))
                            else put(ak, av)
                        }
                    })
                } else {
                    put(key, value)
                }
            }
        }.toString()

        return Triple(sig.take(16), tamperedSig.take(16), tamperedJson)
    }

    private fun buildAction(id: String, kind: String, reversibility: String): String {
        val obj = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("kind", JsonPrimitive(kind))
            put("reversibility", JsonPrimitive(reversibility))
            put("payload", buildJsonObject {})
        }
        return obj.toString()
    }
}
