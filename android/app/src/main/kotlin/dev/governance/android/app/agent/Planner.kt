package dev.governance.android.app.agent

import dev.governance.core.Reversibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File

/**
 * Converts a user instruction into a structured [Plan].
 *
 * The Planner depends on an injected [LlmEngine] for the LLM path
 * and falls back to its keyword router when the engine is not loaded
 * or when generation fails. This split keeps the prompt-build / parse-
 * response pipeline pure JVM logic (testable without an Android
 * Context or a real model) and lets the production wiring swap the
 * runtime (today: llama.cpp + Qwen3) without touching planner logic.
 *
 * Lifecycle:
 * 1. Construct with the chosen engine. Cheap.
 * 2. Optionally call [loadModel] proactively (e.g., when the chat
 *    surface becomes visible). Otherwise the first [plan] call will
 *    just use the keyword fallback.
 * 3. Call [plan] per user instruction.
 * 4. Call [close] to release engine resources when the planner is no
 *    longer needed (e.g., when the chat surface is destroyed).
 */
class Planner(
    private val engine: LlmEngine,
) {

    /**
     * Where the engine expects the model file. `null` for engines
     * that don't load from disk. Exposed so the chat surface can
     * surface a "place model file at: X" message.
     */
    fun modelPath(): File? = engine.modelPath()

    /** Whether the engine reports a usable model file on disk. */
    fun isModelAvailable(): Boolean = engine.isModelAvailable()

    /**
     * Loads the underlying engine's model. Returns `null` on success
     * or a human-readable error message on failure. Idempotent.
     */
    suspend fun loadModel(): String? = withContext(Dispatchers.IO) {
        engine.loadModel()
    }

    /**
     * Plans the user's instruction. Routes through the engine when it
     * is loaded; otherwise falls back to the keyword router.
     */
    suspend fun plan(userInstruction: String): PlanResult = withContext(Dispatchers.IO) {
        if (engine.isLoaded) {
            planWithEngine(userInstruction)
        } else {
            planWithKeywords(userInstruction)
        }
    }

    /** Releases engine resources. Call when the planner is no longer needed. */
    fun close() = engine.close()

    private suspend fun planWithEngine(instruction: String): PlanResult {
        val prompt = buildPrompt(instruction)
        return try {
            val response = engine.generate(prompt)
            parseResponse(response)
        } catch (e: Exception) {
            PlanResult.Error("LLM inference failed: ${e.message}")
        }
    }

    companion object {
        /** Action kinds the dispatcher can actually execute today. */
        val SUPPORTED_KINDS = setOf(
            "read_calendar",
            "send_email",
            "share_to_social_app",
            "open_app",
        )

        private const val UNSUPPORTED_MSG =
            "I can only open apps, check your calendar, send email, or share to social media right now."

        /** Common app names users say in plain English — these route to open_app. */
        private val APP_TRIGGERS = setOf(
            "instagram", "ig", "gmail", "calendar", "messages", "chrome", "browser",
            "youtube", "yt", "maps", "settings", "spotify", "twitter", "whatsapp",
            "tiktok", "discord", "slack", "photos", "camera", "files",
        )

        /**
         * Keyword-based fallback planner for when the LLM isn't
         * available. Only emits kinds in [SUPPORTED_KINDS].
         */
        internal fun planWithKeywords(instruction: String): PlanResult {
            val lower = instruction.lowercase()

            // open_app routing: "open instagram", "launch chrome", "pull up gmail",
            // "show me youtube". Detected by an open-verb keyword PLUS a known app name.
            // Checked FIRST so "pull up instagram" doesn't get captured by the share-rule.
            val isOpenIntent = lower.contains("open ") || lower.contains("launch ") ||
                lower.contains("pull up") || lower.contains("show me ") ||
                lower.contains("start ") || lower.contains("bring up")
            if (isOpenIntent) {
                val app = APP_TRIGGERS.firstOrNull { lower.contains(it) }
                if (app != null) {
                    return PlanResult.Success(Plan(
                        summary = "Open ${app.replaceFirstChar { it.uppercase() }}",
                        steps = listOf(PlannedStep(
                            kind = "open_app",
                            target = app,
                            rationale = "Open the requested app",
                            reversibility = Reversibility.FullyReversible,
                        )),
                    ))
                }
            }

            return when {
                lower.contains("email") || lower.contains("mail") -> {
                    val target = extractTarget(instruction)
                    PlanResult.Success(Plan(
                        summary = "Send email",
                        steps = listOf(PlannedStep(
                            kind = "send_email",
                            target = target,
                            rationale = "Send an email as requested",
                            reversibility = Reversibility.OneShot,
                        )),
                    ))
                }
                lower.contains("calendar") || lower.contains("schedule") || lower.contains("event") -> {
                    PlanResult.Success(Plan(
                        summary = "Check calendar",
                        steps = listOf(PlannedStep(
                            kind = "read_calendar",
                            target = null,
                            rationale = "Check the calendar as requested",
                            reversibility = Reversibility.FullyReversible,
                        )),
                    ))
                }
                lower.contains("share") || lower.contains("instagram") || lower.contains("social") || lower.contains("post") -> {
                    val target = extractTarget(instruction)
                    PlanResult.Success(Plan(
                        summary = "Share to social app",
                        steps = listOf(PlannedStep(
                            kind = "share_to_social_app",
                            target = target,
                            rationale = "Open share sheet as requested",
                            reversibility = Reversibility.FullyReversible,
                        )),
                    ))
                }
                else -> PlanResult.Error(UNSUPPORTED_MSG)
            }
        }

        /** Extracts a name after "to" or "for". */
        internal fun extractTarget(instruction: String): String {
            val toMatch = Regex("(?:to|for)\\s+(\\w+)", RegexOption.IGNORE_CASE)
                .find(instruction)
            return toMatch?.groupValues?.get(1) ?: ""
        }

        internal fun buildPrompt(instruction: String): String = """
You are a phone assistant. Convert the user's request into a JSON action plan.

Supported actions ONLY: read_calendar, send_email, share_to_social_app, open_app.
Reject any other action.

Respond with valid JSON only:
{"summary":"brief description","steps":[{"kind":"action_kind","target":"who or what","rationale":"why","reversibility":"FullyReversible or OneShot"}]}

Reversibility: read_calendar=FullyReversible, send_email=OneShot, share_to_social_app=FullyReversible, open_app=FullyReversible.

Example:
User: email chen saying I'll be late
{"summary":"Send email to chen about being late","steps":[{"kind":"send_email","target":"chen","rationale":"User wants to notify chen","reversibility":"OneShot"}]}

User: pull up instagram
{"summary":"Open Instagram","steps":[{"kind":"open_app","target":"instagram","rationale":"User wants to open Instagram","reversibility":"FullyReversible"}]}

If the user asks for something outside supported actions, respond:
{"summary":"unsupported","steps":[]}

User: $instruction
""".trimIndent()

        /** Parses LLM JSON output into a [PlanResult]. */
        internal fun parseResponse(response: String): PlanResult {
            val jsonStr = Regex("\\{.*}", RegexOption.DOT_MATCHES_ALL)
                .find(response)?.value
                ?: return PlanResult.Error("I couldn't plan that. Can you rephrase?")

            return try {
                val json = Json.parseToJsonElement(jsonStr).jsonObject
                val summary = json["summary"]?.jsonPrimitive?.content ?: "Plan"

                if (summary == "unsupported") {
                    return PlanResult.Error(UNSUPPORTED_MSG)
                }

                val stepsArr = json["steps"]?.jsonArray
                    ?: return PlanResult.Error("No steps in plan. Can you rephrase?")

                val steps = stepsArr.map { stepEl ->
                    val step = stepEl.jsonObject
                    val kind = step["kind"]?.jsonPrimitive?.content ?: "unknown"
                    if (kind !in SUPPORTED_KINDS) {
                        return PlanResult.Error(
                            "I can't do '${kind.replace('_', ' ')}' yet. $UNSUPPORTED_MSG"
                        )
                    }
                    val target = step["target"]?.jsonPrimitive?.contentOrNull
                    val rationale = step["rationale"]?.jsonPrimitive?.content ?: ""
                    val rev = when (step["reversibility"]?.jsonPrimitive?.content) {
                        "OneShot" -> Reversibility.OneShot
                        "Irreversible" -> Reversibility.Irreversible
                        else -> Reversibility.FullyReversible
                    }
                    PlannedStep(kind, target, rationale, rev)
                }

                if (steps.isEmpty()) {
                    PlanResult.Error(UNSUPPORTED_MSG)
                } else {
                    PlanResult.Success(Plan(summary, steps))
                }
            } catch (e: Exception) {
                PlanResult.Error("I couldn't plan that. Can you rephrase?")
            }
        }
    }
}
