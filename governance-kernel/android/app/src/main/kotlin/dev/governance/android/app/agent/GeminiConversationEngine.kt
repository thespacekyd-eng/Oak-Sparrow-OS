package dev.governance.android.app.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/**
 * Multi-turn conversation engine backed by the Google Gemini API.
 *
 * Drop-in replacement for [ConversationEngine] — same contract,
 * different provider. Uses Gemini Flash for fast conversational
 * responses (analogous to Claude Haiku).
 *
 * Privacy: PII is sanitized before each message leaves the device.
 */
class GeminiConversationEngine(
    private val apiKey: String,
    private val model: String = GeminiLlmEngine.DEFAULT_MODEL,
    private val maxTokens: Int = 1024,
    var memoryBlock: String = "",
    var onRemember: ((String) -> Unit)? = null,
    var onForget: ((String) -> Unit)? = null,
    var webSearch: WebSearchEngine? = null,
) : ConversationProvider {
    private val history = mutableListOf<Message>()

    override val isAvailable: Boolean get() = apiKey.isNotBlank()

    data class Message(val role: String, val content: String)

    override fun reset() {
        history.clear()
    }

    override val turnCount: Int get() = history.size

    override suspend fun converse(userMessage: String): String = withContext(Dispatchers.IO) {
        check(isAvailable) { "GeminiConversationEngine: no API key configured" }

        val sanitized = PiiSanitizer.sanitize(userMessage)

        // Web search integration (same logic as ConversationEngine)
        val needsSearch = needsWebSearch(sanitized.sanitized)
        val urlToFetch = extractUrl(sanitized.sanitized)
        var searchContext = ""
        Log.i(TAG, "needsSearch=$needsSearch, urlToFetch=$urlToFetch, webSearch=${if (webSearch != null) "SET" else "NULL"}")

        if (urlToFetch != null && webSearch != null) {
            val content = webSearch!!.fetchUrl(urlToFetch)
            searchContext = "\n<web_content url=\"$urlToFetch\">\n$content\n</web_content>\n"
            Log.i(TAG, "Fetched URL: $urlToFetch (${content.length} chars)")
        } else if (needsSearch && webSearch != null) {
            val query = extractSearchQuery(sanitized.sanitized)
            val results = webSearch!!.search(query)
            if (results.results.isNotEmpty()) {
                searchContext = "\n<web_search query=\"$query\">\n${results.summary}\n</web_search>\n"
                Log.i(TAG, "Web search: '$query' -> ${results.results.size} results")
            }
        }

        val messageContent = if (searchContext.isNotBlank()) {
            sanitized.sanitized + searchContext
        } else {
            sanitized.sanitized
        }
        history.add(Message("user", messageContent))

        // Trim history (keep last 20 turns)
        while (history.size > 40) {
            history.removeAt(0)
        }

        // Build Gemini contents array — role is "user" or "model"
        val contentsArray = buildJsonArray {
            for (msg in history) {
                addJsonObject {
                    put("role", if (msg.role == "assistant") "model" else msg.role)
                    putJsonArray("parts") {
                        addJsonObject { put("text", msg.content) }
                    }
                }
            }
        }

        val systemPrompt = if (memoryBlock.isNotBlank()) {
            SYSTEM_PROMPT + "\n" + memoryBlock + "\n" + MEMORY_INSTRUCTIONS
        } else {
            SYSTEM_PROMPT
        }

        val requestBody = buildJsonObject {
            putJsonObject("system_instruction") {
                putJsonArray("parts") {
                    addJsonObject { put("text", systemPrompt) }
                }
            }
            put("contents", contentsArray)
            putJsonObject("generationConfig") {
                put("maxOutputTokens", maxTokens)
            }
        }.toString()

        val url = "${GeminiLlmEngine.API_BASE}/models/$model:generateContent?key=$apiKey"

        // Retry on transient errors (503 overloaded, 429 rate limit)
        var content: String? = null
        var lastError: Exception? = null
        for (attempt in 0 until MAX_RETRIES) {
            if (attempt > 0) {
                val delayMs = RETRY_BASE_MS * (1 shl (attempt - 1))
                Log.i(TAG, "Retry $attempt after ${delayMs}ms")
                kotlinx.coroutines.delay(delayMs)
            }

            val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 8_000
                readTimeout = 30_000
            }

            try {
                connection.outputStream.use { it.write(requestBody.toByteArray()) }

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val responseStr = connection.inputStream.bufferedReader().readText()
                    content = GeminiLlmEngine.extractTextFromResponse(responseStr)
                    break
                }

                val error = connection.errorStream?.bufferedReader()?.readText() ?: "unknown"
                Log.e(TAG, "Gemini conversation API error $responseCode: $error")

                if (responseCode in RETRYABLE_CODES && attempt < MAX_RETRIES - 1) {
                    lastError = RuntimeException("Gemini API returned $responseCode")
                    continue
                }
                history.removeAt(history.lastIndex)
                throw RuntimeException("Gemini API returned $responseCode: ${error.take(200)}")
            } finally {
                connection.disconnect()
            }
        }

        if (content == null) {
            history.removeAt(history.lastIndex)
            throw lastError ?: RuntimeException("Gemini API failed after $MAX_RETRIES attempts")
        }

        val restored = PiiSanitizer.restore(content, sanitized.mappings)

        // Extract memory commands
        val memoryCommands = Regex("\\[REMEMBER:\\s*(.+?)]").findAll(restored)
        val forgetCommands = Regex("\\[FORGET:\\s*(.+?)]").findAll(restored)
        for (match in memoryCommands) {
            onRemember?.invoke(match.groupValues[1].trim())
        }
        for (match in forgetCommands) {
            onForget?.invoke(match.groupValues[1].trim())
        }

        // Strip memory tags and leaked XML from visible response
        val cleanResponse = restored
            .replace(Regex("\\[REMEMBER:\\s*.+?]"), "")
            .replace(Regex("\\[FORGET:\\s*.+?]"), "")
            .replace(Regex("</?web_search[^>]*>"), "")
            .replace(Regex("</?web_content[^>]*>"), "")
            .trim()

        history.add(Message("assistant", cleanResponse))

        Log.i(TAG, "Conversation turn ${history.size / 2}: ${cleanResponse.length} chars")
        cleanResponse
    }

    companion object {
        private const val TAG = "OakGeminiConversation"
        private const val MAX_RETRIES = 3
        private const val RETRY_BASE_MS = 1000L
        private val RETRYABLE_CODES = setOf(429, 503)

        // Same system prompt as ConversationEngine — Oak's personality is
        // provider-agnostic.
        private val SYSTEM_PROMPT = """
            You are Oak, a friendly and knowledgeable AI assistant that lives on the user's phone.
            You speak naturally, like a real person talking — warm, direct, and conversational.

            CRITICAL rules:
            - NEVER use emojis, emoticons, or special symbols. Your response will be read aloud by TTS.
            - NEVER use markdown, bullet points, numbered lists, asterisks, or formatting.
            - Keep responses short and conversational (1-3 sentences for simple questions).
            - Speak in complete natural sentences, the way you'd talk to a friend.
            - For complex topics, give thorough but focused answers in flowing paragraphs.
            - Never mention being Gemini or Google — you are Oak.
            - You can discuss anything: trivia, advice, brainstorming, explanations, opinions.
            - If the user asks you to do something on their phone (open apps, send messages, etc),
              tell them to say a command like "open instagram" or "text mom" and you'll handle it.
            - Don't say "sure!" or "of course!" before every response — just answer naturally.

            WEB SEARCH: When you see <web_search> or <web_content> tags in the user message,
            use that live data to answer the question. NEVER output these tags yourself — they are
            internal system tags and must never appear in your response. Always cite your sources naturally.
            The web data is live and current — trust it over your training data for recent events.

            URL CONTENT: When you see <web_content> tags, summarize the article clearly and
            concisely. Cite the source URL at the end.
        """.trimIndent()

        private fun needsWebSearch(message: String): Boolean {
            val lower = message.lowercase()
            val searchTriggers = listOf(
                "what's the weather", "what is the weather", "weather in",
                "latest news", "current news", "recent news",
                "who won", "who is winning", "score of",
                "stock price", "how much is", "price of",
                "what happened", "what's happening",
                "search for", "look up", "find me",
                "what time is it in", "time zone",
                "how tall is", "how old is", "when was",
                "what is the capital", "population of",
                "tell me about", "what do you know about",
                "news about", "updates on",
                "best restaurants", "near me",
                "flights from", "hotels in",
                "recipe for", "how to make",
                "current", "today", "tonight", "this week",
                "2025", "2026",
            )
            return searchTriggers.any { lower.contains(it) }
        }

        private fun extractSearchQuery(message: String): String {
            return message
                .replace(Regex("^(hey oak,?|oak,?|can you|could you|please|what's|what is|who is|tell me about|search for|look up|find me)\\s*", RegexOption.IGNORE_CASE), "")
                .replace("?", "")
                .trim()
                .ifBlank { message.take(80) }
        }

        private fun extractUrl(message: String): String? {
            val urlPattern = Regex("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+")
            return urlPattern.find(message)?.value
        }

        private val MEMORY_INSTRUCTIONS = """
            When the user tells you personal information (their name, preferences, important people,
            routines, etc.), remember it. If you learn something new worth remembering, end your
            response with a line like: [REMEMBER: user's name is Josh] or [REMEMBER: user prefers dark mode]
            Only add [REMEMBER: ...] for genuinely useful persistent facts, not transient conversation details.
            If the user asks you to forget something: [FORGET: user's name is Josh]
        """.trimIndent()
    }
}
