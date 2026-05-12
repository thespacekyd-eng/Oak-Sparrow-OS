package dev.governance.android.app.agent

import java.io.File

/**
 * Test double for [LlmEngine] that returns canned responses.
 *
 * Construct with a map of (prompt-substring → canned response) plus
 * an optional default. The first entry whose key appears as a
 * substring of the incoming prompt wins; if no key matches, the
 * [defaultResponse] is returned. If [defaultResponse] is null and
 * no key matches, [generate] throws — surfacing test bugs where a
 * prompt was unexpectedly routed.
 *
 * Lives under main/ rather than test/ so instrumented tests can
 * also use it (androidTest sourceSet has its own classpath).
 *
 * ## Example
 *
 * ```
 * val stub = StubLlmEngine(
 *     responses = mapOf(
 *         "email" to """{"summary":"...","steps":[...]}""",
 *         "calendar" to """{"summary":"...","steps":[...]}""",
 *     ),
 *     defaultResponse = """{"summary":"unsupported","steps":[]}""",
 * )
 * val planner = Planner(stub)
 * stub.loadModel()       // sets isLoaded = true
 * planner.plan("email chen")  // returns parsed plan from the email canned response
 * ```
 */
class StubLlmEngine(
    private val responses: Map<String, String> = emptyMap(),
    private val defaultResponse: String? = null,
    private val loadDelayMs: Long = 0L,
    private val loadShouldFail: String? = null,
) : LlmEngine {

    private var loaded: Boolean = false
    /** History of every prompt passed to [generate]. Useful for assertions. */
    val promptLog: MutableList<String> = mutableListOf()
    /** Number of times [loadModel] was called. */
    var loadCallCount: Int = 0
        private set

    override val isLoaded: Boolean get() = loaded
    override fun modelPath(): File? = null
    override fun isModelAvailable(): Boolean = true

    override suspend fun loadModel(): String? {
        loadCallCount++
        if (loadDelayMs > 0L) kotlinx.coroutines.delay(loadDelayMs)
        if (loadShouldFail != null) {
            loaded = false
            return loadShouldFail
        }
        loaded = true
        return null
    }

    override suspend fun generate(prompt: String): String {
        check(loaded) { "StubLlmEngine.generate called before loadModel" }
        promptLog += prompt
        val match = responses.entries.firstOrNull { (k, _) -> prompt.contains(k, ignoreCase = true) }
        return match?.value
            ?: defaultResponse
            ?: error("StubLlmEngine has no canned response for prompt; configure responses or defaultResponse")
    }

    override fun close() {
        loaded = false
    }
}
