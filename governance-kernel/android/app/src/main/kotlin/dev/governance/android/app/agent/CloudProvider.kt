package dev.governance.android.app.agent

import android.content.Context

/**
 * Which cloud LLM provider to use for reasoning, conversation, and vision.
 *
 * - [CLAUDE]: Anthropic — Haiku for planning/conversation, Opus for vision.
 * - [GEMINI]: Google — Flash for planning/conversation, Pro for vision.
 *
 * Persisted alongside [LlmMode] in SharedPreferences.
 */
enum class CloudProvider {
    CLAUDE,
    GEMINI,
}

object CloudProviderPreference {
    private const val PREFS_NAME = "oak_sparrow_settings"
    private const val KEY_CLOUD_PROVIDER = "cloud_provider"

    fun get(context: Context): CloudProvider {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CLOUD_PROVIDER, null)
        return CloudProvider.entries.firstOrNull { it.name == stored } ?: CloudProvider.GEMINI
    }

    fun set(context: Context, provider: CloudProvider) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CLOUD_PROVIDER, provider.name).apply()
    }
}
