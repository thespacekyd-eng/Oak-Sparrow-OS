package dev.governance.android.app.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Multi-turn conversation engine backed by Claude API.
 *
 * Unlike the single-turn [CloudLlmEngine] used by the Planner for
 * action planning, this engine maintains conversation history across
 * turns. It sends full message history to Claude for context-aware
 * responses — enabling natural back-and-forth conversation.
 *
 * Privacy: PII is sanitized before each message leaves the device.
 * The governance kernel controls whether cloud is enabled at all.
 */
class ConversationEngine(
    private val apiKey: String,
    private val model: String = "claude-opus-4-6",
    private val maxTokens: Int = 512,
) {
    private val history = mutableListOf<Message>()

    val isAvailable: Boolean get() = apiKey.isNotBlank()

    data class Message(val role: String, val content: String)

    /** Clears conversation history. Call when starting a new session. */
    fun reset() {
        history.clear()
    }

    /** Number of messages in the current conversation. */
    val turnCount: Int get() = history.size

    /**
     * Sends a user message and returns the assistant's response.
     * Maintains full conversation history for context.
     */
    suspend fun converse(userMessage: String): String = withContext(Dispatchers.IO) {
        check(isAvailable) { "ConversationEngine: no API key configured" }

        val sanitized = PiiSanitizer.sanitize(userMessage)
        history.add(Message("user", sanitized.sanitized))

        // Trim history if it gets too long (keep last 20 turns)
        while (history.size > 40) {
            history.removeAt(0)
        }

        val messagesArray = buildJsonArray {
            for (msg in history) {
                addJsonObject {
                    put("role", msg.role)
                    put("content", msg.content)
                }
            }
        }

        val requestBody = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", SYSTEM_PROMPT)
            put("messages", messagesArray)
        }.toString()

        val connection = (java.net.URL(API_URL).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", API_VERSION)
            doOutput = true
            connectTimeout = 10_000
            readTimeout = 60_000
        }

        try {
            connection.outputStream.use { it.write(requestBody.toByteArray()) }

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "unknown"
                Log.e(TAG, "Conversation API error $responseCode: $error")
                history.removeAt(history.lastIndex) // remove failed user message
                throw RuntimeException("API returned $responseCode: ${error.take(200)}")
            }

            val responseStr = connection.inputStream.bufferedReader().readText()
            val json = Json.parseToJsonElement(responseStr).jsonObject
            val content = json["content"]?.jsonArray
                ?.firstOrNull()?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?: throw RuntimeException("No content in API response")

            val restored = PiiSanitizer.restore(content, sanitized.mappings)
            history.add(Message("assistant", restored))

            Log.i(TAG, "Conversation turn ${history.size / 2}: ${restored.length} chars")
            restored
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TAG = "OakConversation"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"

        private val SYSTEM_PROMPT = """
            You are Oak, a friendly and knowledgeable AI assistant that lives on the user's phone.
            You speak naturally, like a real person talking — warm, direct, and conversational.

            CRITICAL rules for voice:
            - NEVER use emojis, emoticons, or special symbols. Your response will be read aloud by TTS.
            - NEVER use markdown, bullet points, numbered lists, asterisks, or formatting.
            - Keep responses short and conversational (1-3 sentences for simple questions).
            - Speak in complete natural sentences, the way you'd talk to a friend.
            - For complex topics, give thorough but focused answers in flowing paragraphs.
            - Never mention being Claude or Anthropic — you are Oak.
            - You can discuss anything: trivia, advice, brainstorming, explanations, opinions.
            - If the user asks you to do something on their phone (open apps, send messages, etc),
              tell them to say a command like "open instagram" or "text mom" and you'll handle it.
            - Don't say "sure!" or "of course!" before every response — just answer naturally.
        """.trimIndent()
    }
}
