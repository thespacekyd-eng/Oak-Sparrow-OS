package dev.governance.android.app.agent

import android.content.Context
import dev.governance.android.app.ui.screens.ChatMessage
import dev.governance.android.app.ui.screens.ChatRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists chat conversations to SharedPreferences as JSON.
 * Each conversation has an id, title (first user message), and messages.
 */
class ConversationStore(context: Context) {

    private val prefs = context.getSharedPreferences("conversations", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun listConversations(): List<ConversationSummary> {
        val raw = prefs.getString("conversation_list", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<ConversationSummary>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveConversation(id: String, messages: List<ChatMessage>) {
        val userMessages = messages.filter { it.role == ChatRole.USER }
        if (userMessages.isEmpty()) return

        val title = userMessages.first().text.take(50)
        val preview = messages.lastOrNull { it.role == ChatRole.AGENT }?.text?.take(80) ?: ""

        // Save messages
        val serializable = messages.map { it.toSerializable() }
        prefs.edit().putString("conv_$id", json.encodeToString(serializable)).apply()

        // Update conversation list
        val list = listConversations().toMutableList()
        val existing = list.indexOfFirst { it.id == id }
        val summary = ConversationSummary(
            id = id,
            title = title,
            preview = preview,
            timestamp = System.currentTimeMillis(),
            messageCount = messages.size,
        )
        if (existing >= 0) {
            list[existing] = summary
        } else {
            list.add(0, summary)
        }
        // Keep at most 50 conversations
        val trimmed = list.take(50)
        prefs.edit().putString("conversation_list", json.encodeToString(trimmed)).apply()
    }

    fun loadConversation(id: String): List<ChatMessage>? {
        val raw = prefs.getString("conv_$id", null) ?: return null
        return try {
            json.decodeFromString<List<SerializableChatMessage>>(raw).map { it.toChatMessage() }
        } catch (_: Exception) {
            null
        }
    }

    fun deleteConversation(id: String) {
        prefs.edit().remove("conv_$id").apply()
        val list = listConversations().filterNot { it.id == id }
        prefs.edit().putString("conversation_list", json.encodeToString(list)).apply()
    }
}

@Serializable
data class ConversationSummary(
    val id: String,
    val title: String,
    val preview: String = "",
    val timestamp: Long = 0L,
    val messageCount: Int = 0,
)

@Serializable
private data class SerializableChatMessage(
    val id: String,
    val role: String,
    val text: String,
)

private fun ChatMessage.toSerializable() = SerializableChatMessage(
    id = id,
    role = role.name,
    text = text,
)

private fun SerializableChatMessage.toChatMessage() = ChatMessage(
    id = id,
    role = ChatRole.valueOf(role),
    text = text,
)
