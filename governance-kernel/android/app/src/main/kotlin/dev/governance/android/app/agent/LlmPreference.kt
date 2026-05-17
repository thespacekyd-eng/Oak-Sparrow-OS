package dev.governance.android.app.agent

import android.content.Context
import android.content.SharedPreferences

/**
 * User-selectable LLM routing mode. Persisted in SharedPreferences
 * so it survives across sessions.
 *
 * - [ON_DEVICE]: All inference stays local (Qwen3-4B). No network.
 * - [CLOUD]: Always use Claude API for reasoning + conversation.
 * - [AUTO]: Cloud for conversation/complex, on-device for simple commands.
 */
enum class LlmMode {
    ON_DEVICE,
    CLOUD,
    AUTO,
}

object LlmPreference {
    private const val PREFS_NAME = "oak_sparrow_settings"
    private const val KEY_LLM_MODE = "llm_mode"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLlmMode(context: Context): LlmMode {
        val stored = prefs(context).getString(KEY_LLM_MODE, null)
        return LlmMode.entries.firstOrNull { it.name == stored } ?: LlmMode.AUTO
    }

    fun setLlmMode(context: Context, mode: LlmMode) {
        prefs(context).edit().putString(KEY_LLM_MODE, mode.name).apply()
    }
}
