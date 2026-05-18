package dev.governance.android.app.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * [LlmEngine] backed by the Google Gemini API.
 *
 * Drop-in replacement for [CloudLlmEngine] — same interface, different
 * provider. Uses Gemini Flash for fast planning (analogous to Claude
 * Haiku) or Gemini Pro for heavier tasks.
 *
 * Privacy: Same guarantees as [CloudLlmEngine] — [PiiSanitizer] scrubs
 * all prompts before they leave the device.
 */
class GeminiLlmEngine(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) : LlmEngine {

    @Volatile private var ready = false

    override val isLoaded: Boolean get() = ready && apiKey.isNotBlank()

    override fun modelPath(): File? = null

    override fun isModelAvailable(): Boolean = apiKey.isNotBlank()

    override suspend fun loadModel(): String? {
        if (apiKey.isBlank()) return "No Gemini API key configured. Add GEMINI_API_KEY to local.properties."
        ready = true
        return null
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        check(isLoaded) { "GeminiLlmEngine.generate called before loadModel" }

        val t0 = System.currentTimeMillis()

        // Parse ChatML from Planner.buildPrompt — extract system + user
        val (systemMsg, userMsg) = CloudLlmEngine.parseChatMl(prompt)

        val sanitized = PiiSanitizer.sanitize(userMsg)
        Log.i(TAG, "Gemini request: ${sanitized.sanitized.length} chars (${sanitized.mappings.size} PII redactions)")

        val requestBody = buildJsonObject {
            if (systemMsg.isNotBlank()) {
                putJsonObject("system_instruction") {
                    putJsonArray("parts") {
                        addJsonObject { put("text", systemMsg) }
                    }
                }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", sanitized.sanitized) }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("maxOutputTokens", maxTokens)
            }
        }.toString()

        val url = "$API_BASE/models/$model:generateContent?key=$apiKey"

        // Retry on transient errors (503 overloaded, 429 rate limit)
        var lastError: Exception? = null
        for (attempt in 0 until MAX_RETRIES) {
            if (attempt > 0) {
                val delayMs = RETRY_BASE_MS * (1 shl (attempt - 1))
                Log.i(TAG, "Retry $attempt after ${delayMs}ms")
                kotlinx.coroutines.delay(delayMs)
            }

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 30_000
            }

            try {
                connection.outputStream.use { it.write(requestBody.toByteArray()) }

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val responseStr = connection.inputStream.bufferedReader().readText()
                    val content = extractTextFromResponse(responseStr)
                    val restored = PiiSanitizer.restore(content, sanitized.mappings)
                    val elapsedMs = System.currentTimeMillis() - t0
                    Log.i(TAG, "Gemini response: ${restored.length} chars in ${elapsedMs}ms")
                    return@withContext restored
                }

                val error = connection.errorStream?.bufferedReader()?.readText() ?: "unknown"
                Log.e(TAG, "Gemini API error $responseCode: $error")

                if (responseCode in RETRYABLE_CODES && attempt < MAX_RETRIES - 1) {
                    lastError = RuntimeException("Gemini API returned $responseCode")
                    continue
                }
                throw RuntimeException("Gemini API returned $responseCode: ${error.take(200)}")
            } finally {
                connection.disconnect()
            }
        }
        throw lastError ?: RuntimeException("Gemini API failed after $MAX_RETRIES attempts")
    }

    override fun close() {
        ready = false
    }

    companion object {
        private const val TAG = "OakSparrowGemini"
        const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        const val DEFAULT_MAX_TOKENS = 512
        const val VISION_MODEL = "gemini-2.5-pro"
        private const val MAX_RETRIES = 3
        private const val RETRY_BASE_MS = 1000L
        private val RETRYABLE_CODES = setOf(429, 503)

        /**
         * Extracts text content from Gemini's response JSON.
         *
         * Response shape:
         * ```json
         * { "candidates": [{ "content": { "parts": [{ "text": "..." }] } }] }
         * ```
         */
        internal fun extractTextFromResponse(responseStr: String): String {
            val json = Json.parseToJsonElement(responseStr).jsonObject
            return json["candidates"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?: throw RuntimeException("No content in Gemini response")
        }
    }
}
