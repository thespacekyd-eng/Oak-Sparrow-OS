package dev.governance.android.app.agent

import java.io.File

/**
 * Runtime-agnostic surface for the on-device LLM that powers the planner.
 *
 * The Planner depends on this interface, not on a specific runtime.
 * Production wires [LlamaCppLlmEngine] (llama.cpp via JNI). Tests wire
 * [StubLlmEngine] with canned responses. A "no model loaded" fallback
 * is signalled by [isLoaded] returning false; in that case the Planner
 * routes to its keyword fallback.
 *
 * ## Lifecycle
 *
 * 1. Construct the engine (cheap — no I/O).
 * 2. Call [loadModel] before the first [generate]. Loading is potentially
 *    expensive (model file mmap + GPU buffer setup); per the project's
 *    perf budget it must be lazy — done on first chat use, not at app
 *    start.
 * 3. Call [generate] one or more times. Returns the raw model output
 *    string; the planner is responsible for parsing it.
 * 4. Call [close] to release native resources when the engine is no
 *    longer needed (e.g., when the chat surface is destroyed).
 *
 * Engines are not required to be thread-safe across [generate] calls;
 * callers should serialize generation through a single coroutine
 * dispatcher (the Planner uses Dispatchers.IO).
 *
 * ## Why an interface
 *
 * - Lets us swap MediaPipe/Gemma → llama.cpp/Qwen3 without touching
 *   planner logic.
 * - Lets the JVM unit tests exercise the full prompt-build / parse-
 *   response pipeline against a deterministic stub, no Android Context
 *   or model file required.
 * - Lets the chat UI render a "model loading" indicator without
 *   coupling to a specific runtime's async model.
 */
interface LlmEngine {

    /**
     * Whether the engine has a model loaded and is ready to [generate].
     * Cheap call — no I/O.
     */
    val isLoaded: Boolean

    /**
     * Absolute path the engine expects the model file at, if applicable.
     * Used by the chat UI to surface a "place model file at: <path>"
     * message when [isModelAvailable] returns false. Returns `null`
     * for engines that don't load from disk (e.g., the stub).
     */
    fun modelPath(): File?

    /**
     * Whether the model file is present at [modelPath]. Returns `true`
     * for engines that don't load from disk.
     */
    fun isModelAvailable(): Boolean

    /**
     * Loads the model into memory. Idempotent — returns immediately
     * with `null` if already loaded. On failure, returns a non-null
     * human-readable error message and leaves [isLoaded] as `false`.
     *
     * This is a `suspend` function to allow runtime-specific I/O
     * dispatchers; implementations should not block the main thread.
     */
    suspend fun loadModel(): String?

    /**
     * Generates a response for the given prompt. Caller must ensure
     * [isLoaded] is `true` first; otherwise this throws
     * [IllegalStateException].
     *
     * The returned string is the raw model output. Parsing is the
     * planner's responsibility.
     */
    suspend fun generate(prompt: String): String

    /**
     * Releases native resources. After [close], [isLoaded] must return
     * `false`. Calling [close] on an already-closed engine is a no-op.
     */
    fun close()
}
