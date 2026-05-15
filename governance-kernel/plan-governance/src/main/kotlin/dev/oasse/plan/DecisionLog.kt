package dev.oasse.plan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Append-only log of [SignedDecision]s, one JSON object per line.
 *
 * **Wire-format invariant:** the [json] config here must match the canonical
 * `Json` used internally by the [DecisionSigner] (defaults are aligned for
 * this reason — `classDiscriminator = "kind"`, `encodeDefaults = true`).
 * If a future maintainer changes one config without the other, the on-disk
 * representation drifts from the signed canonical form and verification
 * semantics break. Change both or neither.
 */
class DecisionLog(
    private val path: Path,
    private val signer: DecisionSigner,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "kind"
    },
) {
    suspend fun record(result: EvaluationResult): SignedDecision = withContext(Dispatchers.IO) {
        val signed = signer.sign(result)
        val line = json.encodeToString(SignedDecision.serializer(), signed) + "\n"
        Files.write(
            path,
            line.toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        )
        signed
    }

    suspend fun verifyAll(): List<VerificationResult> = withContext(Dispatchers.IO) {
        if (!Files.exists(path)) return@withContext emptyList()
        Files.readAllLines(path).map { line ->
            val signed = json.decodeFromString(SignedDecision.serializer(), line)
            VerificationResult(signed.result.plan.id, signer.verify(signed))
        }
    }
}

data class VerificationResult(val planId: PlanId, val valid: Boolean)
