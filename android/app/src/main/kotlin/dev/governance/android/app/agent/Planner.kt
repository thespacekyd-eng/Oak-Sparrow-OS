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
            try { android.util.Log.i("OakPlanner", "LLM raw (${response.length}): ${response.take(200)}") } catch (_: Throwable) {}
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
            "set_wallpaper",
            "set_volume",
            "toggle_flashlight",
            "toggle_dnd",
            "custom_intent",
        )

        private const val UNSUPPORTED_MSG =
            "I'm not sure how to do that. Try things like: open apps, email, text, call, " +
            "calendar, alarms, timers, search, directions, camera, settings, music, " +
            "wallpaper, volume, flashlight, or just ask me anything!"

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

            // --- Wallpaper / Background ---
            if (lower.contains("wallpaper") || lower.contains("background") ||
                lower.contains("home screen image")) {
                return PlanResult.Success(Plan(
                    summary = "Change wallpaper",
                    steps = listOf(PlannedStep(
                        kind = "set_wallpaper",
                        target = null,
                        rationale = "Open wallpaper picker",
                        reversibility = Reversibility.FullyReversible,
                    )),
                ))
            }

            // --- Volume ---
            if (lower.contains("volume") && !lower.contains("setting")) {
                val direction = when {
                    lower.contains("up") || lower.contains("raise") || lower.contains("louder") -> "up"
                    lower.contains("down") || lower.contains("lower") || lower.contains("quiet") -> "down"
                    lower.contains("mute") || lower.contains("silent") -> "mute"
                    lower.contains("max") || lower.contains("full") -> "max"
                    else -> "up"
                }
                return PlanResult.Success(Plan(
                    summary = "Volume $direction",
                    steps = listOf(PlannedStep(
                        kind = "set_volume",
                        target = direction,
                        rationale = "Adjust volume as requested",
                        reversibility = Reversibility.FullyReversible,
                    )),
                ))
            }

            // --- Flashlight / Torch ---
            if (lower.contains("flashlight") || lower.contains("torch") ||
                lower.contains("flash light")) {
                return PlanResult.Success(Plan(
                    summary = "Toggle flashlight",
                    steps = listOf(PlannedStep(
                        kind = "toggle_flashlight",
                        target = null,
                        rationale = "Toggle flashlight",
                        reversibility = Reversibility.FullyReversible,
                    )),
                ))
            }

            // --- Do Not Disturb ---
            if (lower.contains("do not disturb") || lower.contains("dnd") ||
                lower.contains("don't disturb") || lower.contains("silent mode")) {
                return PlanResult.Success(Plan(
                    summary = "Do Not Disturb",
                    steps = listOf(PlannedStep(
                        kind = "toggle_dnd",
                        target = null,
                        rationale = "Toggle Do Not Disturb",
                        reversibility = Reversibility.FullyReversible,
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
<|im_start|>system
You are Oak, a friendly private AI phone assistant. /no_think
Actions (JSON): open_app, send_sms, send_email, make_call, set_alarm, set_timer, search_web, open_url, get_directions, take_photo, change_setting, play_music, create_event, read_calendar, share_to_social_app, set_wallpaper, set_volume, toggle_flashlight, toggle_dnd, custom_intent
- For phone actions: reply ONLY with JSON {"summary":"...","steps":[{"kind":"...","target":"...","rationale":"...","reversibility":"FullyReversible"}]}
- For greetings or questions: reply in plain text, NO JSON.<|im_end|>
<|im_start|>user
text mom saying I'll be late<|im_end|>
<|im_start|>assistant
{"summary":"Text mom","steps":[{"kind":"send_sms","target":"mom","rationale":"Send text","reversibility":"OneShot","message":"I'll be late"}]}<|im_end|>
<|im_start|>user
hi<|im_end|>
<|im_start|>assistant
Hi! I'm Oak, your private phone assistant. How can I help?<|im_end|>
<|im_start|>user
$instruction<|im_end|>
<|im_start|>assistant
""".trimIndent()

        /** Parses LLM output — JSON action plan or plain-text conversation. */
        internal fun parseResponse(response: String): PlanResult {
            // Strip Qwen3 thinking tags if the model entered think mode
            // despite /no_think. Keep only the content after </think>.
            val cleaned = response.let { r ->
                val thinkEnd = r.indexOf("</think>")
                if (thinkEnd >= 0) r.substring(thinkEnd + "</think>".length).trim() else r
            }

            // Extract the first JSON object from the response.
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            val jsonStr = if (start >= 0 && end > start) cleaned.substring(start, end + 1) else null

            // No JSON found — treat the whole response as conversational text.
            if (jsonStr == null) {
                val text = cleaned.trim()
                return if (text.isNotEmpty()) PlanResult.Conversational(text)
                else PlanResult.Error("I couldn't plan that. Can you rephrase?")
            }

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
