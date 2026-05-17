package dev.governance.android.app.agent

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistent memory for Oak across conversations.
 * Stores facts the LLM learns about the user (name, preferences, contacts, etc.)
 * and injects them into the system prompt so Oak remembers across sessions.
 */
class OakMemory(context: Context) {

    private val prefs = context.getSharedPreferences("oak_memory", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun getMemories(): List<MemoryEntry> {
        val raw = prefs.getString("memories", null) ?: return emptyList()
        return try {
            json.decodeFromString<List<MemoryEntry>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addMemory(fact: String, category: String = "general") {
        val memories = getMemories().toMutableList()
        // Don't add duplicates
        if (memories.any { it.fact.equals(fact, ignoreCase = true) }) return
        memories.add(MemoryEntry(
            fact = fact,
            category = category,
            timestamp = System.currentTimeMillis(),
        ))
        // Keep at most 100 memories
        val trimmed = memories.takeLast(100)
        prefs.edit().putString("memories", json.encodeToString(trimmed)).apply()
    }

    fun removeMemory(fact: String) {
        val memories = getMemories().filterNot { it.fact.equals(fact, ignoreCase = true) }
        prefs.edit().putString("memories", json.encodeToString(memories)).apply()
    }

    fun clearAll() {
        prefs.edit().remove("memories").apply()
    }

    /**
     * Formats memories into a string for injection into the LLM system prompt.
     */
    fun toPromptBlock(): String {
        val memories = getMemories()
        if (memories.isEmpty()) return ""
        val grouped = memories.groupBy { it.category }
        return buildString {
            appendLine("\n<user_memory>")
            appendLine("Things you know about the user from past conversations:")
            for ((category, entries) in grouped) {
                if (grouped.size > 1) appendLine("[$category]")
                for (entry in entries) {
                    appendLine("- ${entry.fact}")
                }
            }
            appendLine("</user_memory>")
        }
    }
}

@Serializable
data class MemoryEntry(
    val fact: String,
    val category: String = "general",
    val timestamp: Long = 0L,
)
