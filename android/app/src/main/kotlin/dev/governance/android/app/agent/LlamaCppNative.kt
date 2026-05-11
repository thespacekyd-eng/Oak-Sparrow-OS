package dev.governance.android.app.agent

/**
 * JNI surface to the bundled `liboaksparrow_llm.so`, which wraps llama.cpp.
 *
 * Do not call these directly from app code — go through [LlamaCppLlmEngine]
 * so you get the Mutex serialization, error handling, and the clean
 * [LlmEngine] contract.
 *
 * If `System.loadLibrary` throws [UnsatisfiedLinkError], the native build
 * never produced the `.so` for the running ABI. See
 * `android/MODEL_SETUP.md` (step 1: install NDK + run setup-llama-cpp.sh).
 */
internal object LlamaCppNative {

    /** True if the native library loaded successfully at class-init time. */
    val isAvailable: Boolean

    /** If [isAvailable] is false, this holds the load failure message. */
    val loadError: String?

    init {
        var ok = false
        var err: String? = null
        try {
            System.loadLibrary("oaksparrow_llm")
            ok = true
        } catch (e: UnsatisfiedLinkError) {
            err = "liboaksparrow_llm.so not found — native build was not run. " +
                "See android/MODEL_SETUP.md."
        } catch (e: SecurityException) {
            err = "Security manager blocked native library load: ${e.message}"
        } catch (e: Throwable) {
            err = "Unexpected failure loading native library: ${e.message}"
        }
        isAvailable = ok
        loadError = err
    }

    /** Returns a short version string from the native side. Useful for logging. */
    external fun nativeVersion(): String

    /**
     * Loads a GGUF model. Returns a non-zero opaque handle on success, 0 on
     * failure. The handle is a pointer to a heap-allocated llama context
     * struct; pass it to [nativeGenerate] and [nativeFree].
     *
     * @param modelPath absolute path to the .gguf file
     * @param nCtx context window size in tokens (e.g., 4096)
     * @param nGpuLayers number of model layers to offload to GPU; 0 = CPU only
     */
    external fun nativeInit(modelPath: String, nCtx: Int, nGpuLayers: Int): Long

    /**
     * Synchronously generates a response. Resets KV cache before each call —
     * this is a single-turn API, not a streaming chat session.
     *
     * @param handle from [nativeInit]
     * @param prompt the full prompt text (planner builds it via buildPrompt)
     * @param maxTokens hard cap on generation
     * @param temperature sampling temperature; 0.7 is a good default
     * @param topP nucleus sampling threshold; 0.9 default
     * @param topK top-k sampling cutoff; 40 default
     * @param repeatPenalty discourages exact repeats; 1.1 default
     * @param seed RNG seed; -1 uses time-based seed
     * @return generated text (does not include the prompt)
     */
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        seed: Int,
    ): String

    /** Releases the native handle. Calling with handle=0 is a safe no-op. */
    external fun nativeFree(handle: Long)
}
