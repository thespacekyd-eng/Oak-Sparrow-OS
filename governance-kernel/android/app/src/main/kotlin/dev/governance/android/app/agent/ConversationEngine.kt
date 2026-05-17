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
    private val model: String = "claude-haiku-4-5-20251001",
    private val maxTokens: Int = 300,
    var memoryBlock: String = "",
    var onRemember: ((String) -> Unit)? = null,
    var onForget: ((String) -> Unit)? = null,
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

        val systemPrompt = if (memoryBlock.isNotBlank()) {
            SYSTEM_PROMPT + "\n" + memoryBlock + "\n" + MEMORY_INSTRUCTIONS
        } else {
            SYSTEM_PROMPT
        }

        val requestBody = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", systemPrompt)
            put("messages", messagesArray)
        }.toString()

        val connection = (java.net.URL(API_URL).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", API_VERSION)
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 30_000
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

            // Extract and process memory commands before cleaning the response
            val memoryCommands = Regex("\\[REMEMBER:\\s*(.+?)]").findAll(restored)
            val forgetCommands = Regex("\\[FORGET:\\s*(.+?)]").findAll(restored)
            for (match in memoryCommands) {
                onRemember?.invoke(match.groupValues[1].trim())
            }
            for (match in forgetCommands) {
                onForget?.invoke(match.groupValues[1].trim())
            }

            // Strip memory tags from visible response
            val cleanResponse = restored
                .replace(Regex("\\[REMEMBER:\\s*.+?]"), "")
                .replace(Regex("\\[FORGET:\\s*.+?]"), "")
                .trim()

            history.add(Message("assistant", cleanResponse))

            Log.i(TAG, "Conversation turn ${history.size / 2}: ${cleanResponse.length} chars")
            cleanResponse
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

        private val MEMORY_INSTRUCTIONS = """
            When the user tells you personal information (their name, preferences, important people,
            routines, etc.), remember it. If you learn something new worth remembering, end your
            response with a line like: [REMEMBER: user's name is Josh] or [REMEMBER: user prefers dark mode]
            Only add [REMEMBER: ...] for genuinely useful persistent facts, not transient conversation details.
            If the user asks you to forget something: [FORGET: user's name is Josh]
        """.trimIndent()
    }
}
