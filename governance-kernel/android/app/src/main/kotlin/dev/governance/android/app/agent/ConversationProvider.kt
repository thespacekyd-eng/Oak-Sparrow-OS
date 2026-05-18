package dev.governance.android.app.agent

/**
 * Common interface for multi-turn conversation engines.
 *
 * Both [ConversationEngine] (Claude) and [GeminiConversationEngine]
 * implement this so the [Planner] can use either without knowing
 * which provider is behind it.
 */
interface ConversationProvider {
    val isAvailable: Boolean
    val turnCount: Int
    fun reset()
    suspend fun converse(userMessage: String): String
}
