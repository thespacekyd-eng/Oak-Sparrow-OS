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
     * Plans the user's instruction. Tries the keyword router first
     * (instant — zero inference latency). Falls through to the LLM
     * only when keywords can't parse the instruction.
     *
     * This gives negative latency on all common instructions: the
     * keyword router matches in microseconds and the GatePredictor
     * can instant-dispatch before the governance round-trip. The LLM
     * is reserved for truly ambiguous or complex requests that the
     * keyword patterns don't cover.
     */
    suspend fun plan(userInstruction: String): PlanResult = withContext(Dispatchers.IO) {
        val keywordResult = planWithKeywords(userInstruction)
        if (keywordResult is PlanResult.Success) return@withContext keywordResult

        if (engine.isLoaded) {
            planWithEngine(userInstruction)
        } else {
            keywordResult
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
            "send_sms",
            "make_call",
            "set_alarm",
            "set_timer",
            "open_url",
            "search_web",
            "get_directions",
            "take_photo",
            "change_setting",
            "play_music",
            "create_event",
        )

        private const val UNSUPPORTED_MSG =
            "I can't do that yet. Try: open apps, email, text, call, calendar, " +
            "alarms, timers, search, directions, camera, settings, or music."

        /** Common app names users say in plain English — these route to open_app. */
        private val APP_TRIGGERS = setOf(
            "instagram", "ig", "gmail", "calendar", "messages", "chrome", "browser",
            "youtube", "yt", "maps", "settings", "spotify", "twitter", "whatsapp",
            "tiktok", "discord", "slack", "photos", "camera", "files",
            "clock", "calculator", "contacts", "phone", "notes", "drive",
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

            // --- SMS / Text ---
            if (lower.contains("text ") || lower.contains("sms") ||
                (lower.contains("message") && !lower.contains("email"))) {
                val target = extractTarget(instruction)
                val message = extractMessage(instruction)
                return PlanResult.Success(Plan(
                    summary = "Send text to $target",
                    steps = listOf(PlannedStep(
                        kind = "send_sms",
                        target = target,
                        rationale = "Send SMS as requested",
                        reversibility = Reversibility.OneShot,
                        message = message,
                    )),
                ))
            }

            // --- Call ---
            if (lower.contains("call ") || lower.contains("dial ") || lower.contains("ring ")) {
                val target = extractTarget(instruction)
                    .ifBlank { instruction.substringAfter("call ").substringAfter("dial ").trim() }
                return PlanResult.Success(Plan(
                    summary = "Call $target",
                    steps = listOf(PlannedStep(
                        kind = "make_call",
                        target = target,
                        rationale = "Dial as requested",
                        reversibility = Reversibility.FullyReversible,
                    )),
                ))
            }

            // --- Alarm ---
            if (lower.contains("alarm") || lower.contains("wake me")) {
                val time = Regex("\\d{1,2}(:\\d{2})?\\s*(am|pm)?", RegexOption.IGNORE_CASE)
                    .find(lower)?.value ?: "7:00 am"
                return PlanResult.Success(Plan(
                    summary = "Set alarm for $time",
                    steps = listOf(PlannedStep(
                        kind = "set_alarm",
                        target = time,
                        rationale = "Set alarm as requested",
                        reversibility = Reversibility.PartiallyReversible,
                    )),
                ))
            }

            // --- Timer ---
            if (lower.contains("timer") || lower.contains("countdown")) {
                val duration = Regex("\\d+\\s*(min|minute|sec|second|hour|h|m|s)")
                    .find(lower)?.value ?: "5 minutes"
                return PlanResult.Success(Plan(
                    summary = "Set timer for $duration",
                    steps = listOf(PlannedStep(
                        kind = "set_timer",
                        target = duration,
                        rationale = "Set timer as requested",
                        reversibility = Reversibility.PartiallyReversible,
                    )),
                ))
            }

            // --- Directions / Navigate ---
            if (lower.contains("direction") || lower.contains("navigate") ||
                lower.contains("how to get to") || lower.contains("take me to") ||
                lower.contains("route to")) {
                val dest = extractTarget(instruction)
                    .ifBlank { instruction.substringAfter("to ").trim() }
                return PlanResult.Success(Plan(
                    summary = "Get directions to $dest",
                    steps = listOf(PlannedStep(
                        kind = "get_directions",
                        target = dest,
                        rationale = "Navigate as requested",
                        reversibility = Reversibility.FullyReversible,
                    )),
                ))
            }

            // --- Search ---
            if (lower.contains("search") || lower.contains("look up") ||
                lower.contains("google") || lower.contains("find me")) {
                val query = instruction.replace(Regex("(?i)(search|look up|google|find me)\\s*(for)?\\s*"), "").trim()
                return PlanResult.Success(Plan(
                    summary = "Search for \"$query\"",
                    steps = listOf(PlannedStep(
                        kind = "search_web",
                        target = query,
                        rationale = "Web search as requested",
                        reversibility = Reversibility.FullyReversible,
                    )),
                ))
            }

            // --- URL ---
            if (lower.contains("go to ") && (lower.contains(".com") || lower.contains(".org") ||
                    lower.contains("http") || lower.contains("www"))) {
                val url = Regex("(https?://\\S+|www\\.\\S+|\\S+\\.(com|org|net|io|dev)\\S*)")
                    .find(lower)?.value ?: instruction.substringAfter("go to ").trim()
                return PlanResult.Success(Plan(
                    summary = "Open $url",
                    steps = listOf(PlannedStep(
                        kind = "open_url",
                        target = url,
                        rationale = "Open web page as requested",
                        reversibility = Reversibility.FullyReversible,
                    )),
                ))
            }

            // --- Camera / Photo ---
            if (lower.contains("photo") || lower.contains("picture") ||
                lower.contains("camera") || lower.contains("selfie")) {
                return PlanResult.Success(Plan(
                    summary = "Take a photo",
                    steps = listOf(PlannedStep(
                        kind = "take_photo",
                        target = null,
                        rationale = "Open camera as requested",
                        reversibility = Reversibility.FullyReversible,
                    )),
                ))
            }

            // --- Settings ---
            if (lower.contains("turn on") || lower.contains("turn off") ||
                lower.contains("enable") || lower.contains("disable") ||
                lower.contains("setting") || lower.contains("brightness") ||
                lower.contains("volume") || lower.contains("wifi") ||
                lower.contains("bluetooth") || lower.contains("airplane")) {
                val setting = when {
                    lower.contains("wifi") || lower.contains("wi-fi") -> "wifi"
                    lower.contains("bluetooth") || lower.contains("bt") -> "bluetooth"
                    lower.contains("airplane") -> "airplane"
                    lower.contains("brightness") || lower.contains("display") -> "brightness"
                    lower.contains("volume") || lower.contains("sound") -> "sound"
                    lower.contains("location") || lower.contains("gps") -> "location"
                    else -> ""
                }
                return PlanResult.Success(Plan(
                    summary = "Change $setting settings",
                    steps = listOf(PlannedStep(
                        kind = "change_setting",
                        target = setting,
                        rationale = "Open settings as requested",
                        reversibility = Reversibility.PartiallyReversible,
                    )),
                ))
            }

            // --- Music ---
            if (lower.contains("play ") || lower.contains("music") ||
                lower.contains("song") || lower.contains("listen to")) {
                val query = instruction.replace(Regex("(?i)(play|listen to|put on)\\s*"), "").trim()
                return PlanResult.Success(Plan(
                    summary = "Play \"$query\"",
                    steps = listOf(PlannedStep(
                        kind = "play_music",
                        target = query,
                        rationale = "Play music as requested",
                        reversibility = Reversibility.FullyReversible,
                    )),
                ))
            }

            // --- Create Event ---
            if (lower.contains("create event") || lower.contains("add event") ||
                lower.contains("schedule meeting") || lower.contains("add to calendar")) {
                val title = extractTarget(instruction)
                val message = extractMessage(instruction)
                return PlanResult.Success(Plan(
                    summary = "Create event: $title",
                    steps = listOf(PlannedStep(
                        kind = "create_event",
                        target = title,
                        rationale = "Create calendar event as requested",
                        reversibility = Reversibility.PartiallyReversible,
                        message = message,
                    )),
                ))
            }

            return when {
                lower.contains("email") || lower.contains("mail") -> {
                    val target = extractTarget(instruction)
                    val message = extractMessage(instruction)
                    PlanResult.Success(Plan(
                        summary = "Send email to $target",
                        steps = listOf(PlannedStep(
                            kind = "send_email",
                            target = target,
                            rationale = "Send an email as requested",
                            reversibility = Reversibility.OneShot,
                            message = message,
                        )),
                    ))
                }
                lower.contains("calendar") || lower.contains("schedule") -> {
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
                lower.contains("share") || lower.contains("social") || lower.contains("post") -> {
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

        /** Extracts a message body after "saying", "that", "with message". */
        internal fun extractMessage(instruction: String): String? {
            val patterns = listOf(
                Regex("(?:saying|say)\\s+(.+)", RegexOption.IGNORE_CASE),
                Regex("(?:with message|message)\\s+(.+)", RegexOption.IGNORE_CASE),
                Regex("(?:that)\\s+(.+)", RegexOption.IGNORE_CASE),
            )
            for (p in patterns) {
                val match = p.find(instruction)
                if (match != null) return match.groupValues[1].trim()
            }
            return null
        }

        internal fun buildPrompt(instruction: String): String = """
You are an AI phone assistant. Convert the user's request into a JSON action plan.

Supported actions:
- read_calendar (FullyReversible) — check calendar/schedule
- send_email (OneShot) — compose and send email. Use "message" field for body.
- share_to_social_app (FullyReversible) — open share sheet
- open_app (FullyReversible) — launch any app by name
- send_sms (OneShot) — send text message. Use "message" field for body.
- make_call (FullyReversible) — open dialer with number
- set_alarm (PartiallyReversible) — set alarm at a time
- set_timer (PartiallyReversible) — set countdown timer
- open_url (FullyReversible) — open a web page
- search_web (FullyReversible) — web search
- get_directions (FullyReversible) — navigate to a place
- take_photo (FullyReversible) — open camera
- change_setting (PartiallyReversible) — open device settings (wifi/bluetooth/brightness/volume/airplane/location)
- play_music (FullyReversible) — play a song or open music
- create_event (PartiallyReversible) — create calendar event

Respond with valid JSON only:
{"summary":"brief description","steps":[{"kind":"action_kind","target":"who/what","rationale":"why","reversibility":"...","message":"optional body text"}]}

Examples:
User: text mom saying I'll be home late
{"summary":"Text mom","steps":[{"kind":"send_sms","target":"mom","rationale":"Send text as requested","reversibility":"OneShot","message":"I'll be home late"}]}

User: set an alarm for 7am
{"summary":"Alarm at 7:00 AM","steps":[{"kind":"set_alarm","target":"7am","rationale":"Wake up alarm","reversibility":"PartiallyReversible"}]}

User: navigate to the airport
{"summary":"Directions to airport","steps":[{"kind":"get_directions","target":"airport","rationale":"User needs directions","reversibility":"FullyReversible"}]}

User: turn off wifi
{"summary":"WiFi settings","steps":[{"kind":"change_setting","target":"wifi","rationale":"User wants to toggle WiFi","reversibility":"PartiallyReversible"}]}

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
