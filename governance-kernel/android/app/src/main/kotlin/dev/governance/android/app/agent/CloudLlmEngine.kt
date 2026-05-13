package dev.governance.android.app.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * [LlmEngine] backed by the Anthropic Messages API (Claude).
 *
 * Privacy guarantees:
 * - [PiiSanitizer] scrubs phone numbers, emails, SSNs, credit cards
 *   from every prompt before it leaves the device.
 * - The governance kernel decides whether cloud inference is allowed
 *   at all (user opt-in during onboarding).
 * - No phone data (calendar contents, contacts, messages) is ever
 *   sent — only the sanitized user instruction + system prompt.
 *
 * Falls back gracefully: if the API key is missing, the network is
 * down, or the call fails, [generate] throws and the [HybridLlmEngine]
 * falls through to the on-device engine.
 */
class CloudLlmEngine(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) : LlmEngine {

    @Volatile private var ready = false

    override val isLoaded: Boolean get() = ready && apiKey.isNotBlank()

    override fun modelPath(): File? = null

    override fun isModelAvailable(): Boolean = apiKey.isNotBlank()

    override suspend fun loadModel(): String? {
        if (apiKey.isBlank()) return "No API key configured. Go to Settings to add your Anthropic API key."
        ready = true
        return null
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        check(isLoaded) { "CloudLlmEngine.generate called before loadModel" }

        val t0 = System.currentTimeMillis()

        // The prompt from Planner.buildPrompt uses ChatML format.
        // Extract the system message and user message for the Messages API.
        val (systemMsg, userMsg) = parseChatMl(prompt)

        // Sanitize the user message before it leaves the device
        val sanitized = PiiSanitizer.sanitize(userMsg)
        Log.i(TAG, "Cloud request: ${sanitized.sanitized.length} chars (${sanitized.mappings.size} PII redactions)")

        val requestBody = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", systemMsg)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", sanitized.sanitized)
                }
            }
        }.toString()

        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", API_VERSION)
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 30_000
        }

        try {
            connection.outputStream.use { it.write(requestBody.toByteArray()) }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "unknown"
                Log.e(TAG, "API error $responseCode: $error")
                throw RuntimeException("Cloud API returned $responseCode: ${error.take(200)}")
            }

            val responseStr = connection.inputStream.bufferedReader().readText()
            val json = Json.parseToJsonElement(responseStr).jsonObject
            val content = json["content"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?: throw RuntimeException("No content in API response")

            // Restore any PII placeholders the model echoed back
            val restored = PiiSanitizer.restore(content, sanitized.mappings)

            val elapsedMs = System.currentTimeMillis() - t0
            Log.i(TAG, "Cloud response: ${restored.length} chars in ${elapsedMs}ms")
            restored
        } finally {
            connection.disconnect()
        }
    }

    override fun close() {
        ready = false
    }

    companion object {
        private const val TAG = "OakSparrowCloud"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        const val DEFAULT_MODEL = "claude-opus-4-6"
        const val DEFAULT_MAX_TOKENS = 512

        /**
         * Extracts system and user content from the ChatML-formatted
         * prompt that [Planner.buildPrompt] produces. The cloud API
         * uses structured messages, not raw ChatML.
         */
        internal fun parseChatMl(prompt: String): Pair<String, String> {
            // Extract system block
            val sysStart = prompt.indexOf("<|im_start|>system\n")
            val sysEnd = prompt.indexOf("<|im_end|>", sysStart)
            val system = if (sysStart >= 0 && sysEnd > sysStart) {
                prompt.substring(sysStart + "<|im_start|>system\n".length, sysEnd).trim()
            } else ""

            // Extract the LAST user block (the actual instruction)
            val lastUserStart = prompt.lastIndexOf("<|im_start|>user\n")
            val lastUserEnd = prompt.indexOf("<|im_end|>", lastUserStart)
            val user = if (lastUserStart >= 0 && lastUserEnd > lastUserStart) {
                prompt.substring(lastUserStart + "<|im_start|>user\n".length, lastUserEnd).trim()
            } else prompt

            // Build a richer system prompt for the cloud model
            val cloudSystem = """
                |$system
                |
                |You are the reasoning engine for Oak & Sparrow, a governance-gated phone assistant.
                |You control the phone through structured action plans. Think step-by-step about
                |what the user wants, then emit the right actions.
                |
                |RULES:
                |1. For ANY phone action: respond with ONLY JSON, no explanation, no markdown.
                |   Format: {"summary":"...","steps":[{"kind":"...","target":"...","rationale":"...","reversibility":"..."}]}
                |2. For greetings/questions/conversation: respond in plain text only, NO JSON.
                |3. You MUST only use action kinds from the list above. Map creative requests:
                |   - "download/install X" → open_app, target "play store"
                |   - "check email/inbox" → open_app, target "gmail"
                |   - "remind me about X" → set_alarm or create_event
                |   - "find X nearby" → search_web or get_directions
                |   - "order X" → open_app targeting the relevant app, or search_web
                |4. Multi-step plans: chain multiple steps when needed.
                |   Example: "email boss and add meeting to calendar" → two steps: send_email + create_event
                |5. Be concise. One sentence for conversation. Minimal JSON for actions.
                |6. Reversibility: FullyReversible (read/open), PartiallyReversible (alarm/event), OneShot (send), Irreversible (delete).
            """.trimMargin()

            return cloudSystem to user
        }
    }
}
