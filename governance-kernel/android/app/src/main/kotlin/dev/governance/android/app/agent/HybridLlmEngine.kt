package dev.governance.android.app.agent

import android.util.Log
import java.io.File

/**
 * Wraps a [CloudLlmEngine] and a local [LlmEngine] (llama.cpp).
 * Tries cloud first for better reasoning quality; falls back to
 * on-device if the cloud is unavailable, the API key is missing,
 * or the network call fails.
 *
 * The governance kernel controls whether cloud is enabled via
 * [cloudEnabled]. When disabled, all inference stays on-device
 * exactly as before — no INTERNET egress.
 *
 * Negative latency is preserved: the [Planner]'s keyword router
 * runs before this engine is ever called. Common requests like
 * "open camera", "set alarm for 7am" never hit the cloud at all.
 */
class HybridLlmEngine(
    private val cloud: CloudLlmEngine,
    private val local: LlmEngine,
    var cloudEnabled: Boolean = true,
) : LlmEngine {

    override val isLoaded: Boolean
        get() = (cloudEnabled && cloud.isLoaded) || local.isLoaded

    override fun modelPath(): File? = local.modelPath()

    override fun isModelAvailable(): Boolean =
        (cloudEnabled && cloud.isModelAvailable()) || local.isModelAvailable()

    override suspend fun loadModel(): String? {
        val errors = mutableListOf<String>()

        // Load cloud engine if enabled
        if (cloudEnabled) {
            val cloudErr = cloud.loadModel()
            if (cloudErr != null) {
                Log.w(TAG, "Cloud engine unavailable: $cloudErr")
                errors.add("Cloud: $cloudErr")
            }
        }

        // Always load local as fallback
        val localErr = local.loadModel()
        if (localErr != null) {
            Log.w(TAG, "Local engine unavailable: $localErr")
            errors.add("Local: $localErr")
        }

        // Success if at least one engine loaded
        return if (isLoaded) null
        else errors.joinToString("; ")
    }

    override suspend fun generate(prompt: String): String {
        check(isLoaded) { "HybridLlmEngine: no engine available" }

        // Try cloud first if enabled and loaded
        if (cloudEnabled && cloud.isLoaded) {
            try {
                return cloud.generate(prompt)
            } catch (e: Exception) {
                Log.w(TAG, "Cloud generation failed, falling back to local: ${e.message}")
            }
        }

        // Fall back to local
        if (local.isLoaded) {
            return local.generate(prompt)
        }

        throw IllegalStateException("Both cloud and local engines failed")
    }

    override fun close() {
        cloud.close()
        local.close()
    }

    companion object {
        private const val TAG = "OakSparrowHybrid"
    }
}
