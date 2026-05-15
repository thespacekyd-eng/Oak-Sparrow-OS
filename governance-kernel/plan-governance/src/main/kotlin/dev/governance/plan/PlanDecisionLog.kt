package dev.governance.plan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Append-only JSONL log of [SignedDecision]s, following the same pattern
 * as the kernel's audit log but for plan-level governance decisions.
 */
class PlanDecisionLog(
    private val path: Path,
    private val signer: PlanDecisionSigner,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
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
