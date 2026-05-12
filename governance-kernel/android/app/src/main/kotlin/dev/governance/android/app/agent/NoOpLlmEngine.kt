package dev.governance.android.app.agent

import java.io.File

/**
 * An [LlmEngine] that is never loaded. The planner falls back to its
 * keyword router on every call.
 *
 * Wired in production as a safe default before the native llama.cpp
 * engine is built — keeps the chat surface working (with reduced
 * intent coverage) on a debug build that doesn't have the .so yet.
 *
 * Also useful in Compose previews and Paparazzi snapshots where you
 * need a Planner instance but don't want to load a real model.
 */
object NoOpLlmEngine : LlmEngine {
    override val isLoaded: Boolean = false
    override fun modelPath(): File? = null
    override fun isModelAvailable(): Boolean = true
    override suspend fun loadModel(): String? =
        "LLM not configured on this build — planner is using keyword fallback."
    override suspend fun generate(prompt: String): String =
        throw IllegalStateException("NoOpLlmEngine cannot generate. Check isLoaded before calling.")
    override fun close() = Unit
}
