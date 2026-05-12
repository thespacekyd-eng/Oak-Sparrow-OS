package dev.governance.android.app.agent

import android.content.Context
import android.util.Log
import dev.governance.android.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Production [LlmEngine] that runs the planner LLM via llama.cpp through
 * a JNI native library. Loads a GGUF model from
 * `context.filesDir/models/{BuildConfig.MODEL_FILENAME}`.
 *
 * Key behaviors:
 * - Lazy model load — does no I/O at construction. Per the project's
 *   perf budget, model load happens on the first [loadModel] call,
 *   typically triggered when the chat surface becomes visible.
 * - Mutex-serialized — llama.cpp contexts are not thread-safe across
 *   concurrent decode calls. The Mutex enforces single-flight.
 * - Fail-soft on missing native lib — if `liboaksparrow_llm.so` isn't
 *   in the APK (debug builds where the NDK setup wasn't run), the
 *   engine reports a friendly error from [loadModel] and stays
 *   `isLoaded = false`, which makes the planner fall back to keywords.
 *   The app does not crash.
 *
 * @param context used for `filesDir` to locate the model file
 * @param nCtx context window in tokens. 4096 is the conservative default
 *             for phone RAM; Qwen3-4B supports up to 32k+
 * @param nGpuLayers layers to offload to GPU. 0 = CPU only (most compatible).
 *             Set higher (e.g. 28 for full Qwen3-4B) on Pixel 8+ class GPUs.
 */
class LlamaCppLlmEngine(
    private val context: Context,
    private val nCtx: Int = DEFAULT_CONTEXT,
    private val nGpuLayers: Int = DEFAULT_GPU_LAYERS,
) : LlmEngine {

    private val mutex = Mutex()
    @Volatile private var handle: Long = 0L

    override val isLoaded: Boolean get() = handle != 0L

    override fun modelPath(): File =
        File(context.filesDir, "models/${BuildConfig.MODEL_FILENAME}")

    override fun isModelAvailable(): Boolean = modelPath().exists()

    override suspend fun loadModel(): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (handle != 0L) return@withLock null
            if (!LlamaCppNative.isAvailable) {
                Log.w(TAG, "Native library not loaded: ${LlamaCppNative.loadError}")
                return@withLock LlamaCppNative.loadError ?: "Native library failed to load."
            }
            val path = modelPath()
            if (!path.exists()) {
                Log.w(TAG, "Model file missing at ${path.absolutePath}")
                return@withLock "Model file not found at ${path.absolutePath}.\n" +
                    "Run android/setup-model.sh, or adb push the GGUF to that location."
            }
            try {
                Log.i(TAG, "Loading model: path=${path.absolutePath} nCtx=$nCtx nGpuLayers=$nGpuLayers")
                Log.i(TAG, "Native version: ${LlamaCppNative.nativeVersion()}")
                val t0 = System.currentTimeMillis()
                val h = LlamaCppNative.nativeInit(
                    modelPath = path.absolutePath,
                    nCtx = nCtx,
                    nGpuLayers = nGpuLayers,
                )
                if (h == 0L) {
                    Log.e(TAG, "nativeInit returned 0 — see native logs (logcat tag: OakSparrowLLM)")
                    return@withLock "Failed to initialize the model. " +
                        "Check `adb logcat -s OakSparrowLLM` for native diagnostics."
                }
                handle = h
                val elapsedMs = System.currentTimeMillis() - t0
                Log.i(TAG, "Model loaded in ${elapsedMs}ms (handle=0x${h.toString(16)})")
                null
            } catch (e: Throwable) {
                Log.e(TAG, "Model load threw", e)
                "Model load failed: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(handle != 0L) { "LlamaCppLlmEngine.generate called before successful loadModel" }
            val t0 = System.currentTimeMillis()
            val response = LlamaCppNative.nativeGenerate(
                handle = handle,
                prompt = prompt,
                maxTokens = MAX_TOKENS,
                temperature = TEMPERATURE,
                topP = TOP_P,
                topK = TOP_K,
                repeatPenalty = REPEAT_PENALTY,
                seed = -1,
            )
            val elapsedMs = System.currentTimeMillis() - t0
            Log.i(TAG, "generate: ${response.length} chars in ${elapsedMs}ms")
            response
        }
    }

    override fun close() {
        if (handle != 0L) {
            try {
                LlamaCppNative.nativeFree(handle)
                Log.i(TAG, "Engine closed (freed handle=0x${handle.toString(16)})")
            } catch (e: Throwable) {
                Log.e(TAG, "nativeFree threw", e)
            } finally {
                handle = 0L
            }
        }
    }

    companion object {
        private const val TAG = "OakSparrowLLM"

        /** Phone-conservative context window. Qwen3-4B supports much more. */
        const val DEFAULT_CONTEXT = 4096

        /** CPU-only by default. Set higher on flagship phones with capable GPUs. */
        const val DEFAULT_GPU_LAYERS = 0

        /** Max output tokens per generate call. Plans are short; conversations kept moderate. */
        const val MAX_TOKENS = 512

        const val TEMPERATURE = 0.7f
        const val TOP_P = 0.9f
        const val TOP_K = 40
        const val REPEAT_PENALTY = 1.1f
    }
}
