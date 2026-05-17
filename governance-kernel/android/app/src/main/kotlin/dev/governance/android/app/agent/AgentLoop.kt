package dev.governance.android.app.agent

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Multi-turn agent loop: LLM reasons → accessibility acts → observe →
 * LLM reasons again. Each cycle is governance-gated.
 *
 * This is what lets Oak say "open Instagram and like 3 music posts"
 * and actually do it — the LLM sees the screen, decides what to tap,
 * observes the result, and plans the next move.
 *
 * ## Flow
 *
 * 1. User gives a high-level task ("like 3 music posts on Instagram")
 * 2. LLM decomposes into first action ("open Instagram")
 * 3. Action dispatched through governance kernel
 * 4. Screen is read via accessibility
 * 5. Screen state fed back to LLM
 * 6. LLM decides next action ("scroll down", "tap Like on post about music")
 * 7. Repeat until task complete or max steps reached
 *
 * ## Safety
 *
 * - Each interaction is a separate governance-gated action
 * - Max steps prevent infinite loops
 * - LLM can abort if it detects the task can't be completed
 * - All interactions are logged in the audit trail
 */
class AgentLoop(
    private val engine: LlmEngine,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
) {

    data class StepResult(
        val action: String,
        val result: UiInteractor.InteractionResult,
        val screenSummary: String?,
    )

    data class LoopResult(
        val success: Boolean,
        val steps: List<StepResult>,
        val summary: String,
    )

    /**
     * Executes a complex multi-step task. The LLM observes the screen
     * after each action and decides the next step.
     *
     * @param task High-level task description from the user
     * @param currentApp Package name of the app that's currently open
     * @return Result with all steps taken and final summary
     */
    suspend fun execute(task: String, currentApp: String): LoopResult {
        if (!engine.isLoaded) {
            return LoopResult(false, emptyList(), "LLM not loaded — cannot execute complex tasks")
        }

        val steps = mutableListOf<StepResult>()
        var consecutiveFailures = 0

        for (step in 0 until maxSteps) {
            // Read current screen
            val screen = ScreenReader.read()
            val screenText = screen?.toPrompt(80) ?: "[Cannot read screen — accessibility service not connected]"
            Log.d(TAG, "Screen for step $step (${screenText.length} chars):\n${screenText.take(500)}")

            // Build the LLM prompt with task + history + current screen
            val prompt = buildStepPrompt(task, steps, screenText, step)

            // Ask the LLM what to do next
            val response = try {
                engine.generate(prompt)
            } catch (e: Exception) {
                Log.e(TAG, "LLM generation failed at step $step: ${e.message}")
                break
            }

            Log.d(TAG, "LLM response: ${response.take(100)}")
            val action = parseAction(response)
            Log.i(TAG, "Step $step: action=${action.type} target=${action.target}")

            // Check for completion
            if (action.type == "DONE") {
                return LoopResult(true, steps, action.target ?: "Task completed")
            }
            if (action.type == "ABORT") {
                return LoopResult(false, steps, action.target ?: "Could not complete task")
            }

            // Execute the action
            val result = executeAction(action)
            val postScreen = ScreenReader.read()?.toPrompt(30)
            steps.add(StepResult(action.toString(), result, postScreen))

            if (result is UiInteractor.InteractionResult.Failed) {
                consecutiveFailures++
                if (consecutiveFailures >= 3) {
                    return LoopResult(false, steps, "Aborted after $consecutiveFailures consecutive failures")
                }
            } else {
                consecutiveFailures = 0
            }

            // Brief pause between actions for UI to settle
            delay(800)
        }

        return LoopResult(
            steps.any { it.result is UiInteractor.InteractionResult.Success },
            steps,
            "Reached max steps ($maxSteps)"
        )
    }

    data class ParsedAction(
        val type: String,      // TAP, SCROLL, TYPE, WAIT, DONE, ABORT
        val target: String?,   // element index, text, direction, etc.
    ) {
        override fun toString() = "$type:${target ?: ""}"
    }

    private fun buildStepPrompt(
        task: String,
        history: List<StepResult>,
        currentScreen: String,
        stepNum: Int,
    ): String {
        val historyText = if (history.isEmpty()) "No actions taken yet."
        else history.takeLast(5).joinToString("\n") { step ->
            val status = when (step.result) {
                is UiInteractor.InteractionResult.Success -> "OK: ${step.result.description}"
                is UiInteractor.InteractionResult.Failed -> "FAILED: ${step.result.reason}"
            }
            "  ${step.action} → $status"
        }

        // Count completed actions from history for progress tracking
        val likeCount = history.count { step ->
            step.action.startsWith("TAP") &&
                step.result is UiInteractor.InteractionResult.Success &&
                (step.result.description.contains("like", ignoreCase = true) ||
                    step.result.description.contains("♡") ||
                    step.result.description.contains("heart", ignoreCase = true))
        }
        val progressNote = Regex("\\d+").find(task)?.value?.toIntOrNull()?.let { target ->
            "Progress: $likeCount / $target completed so far."
        } ?: ""

        return """
<|im_start|>system
You are Oak, an AI agent controlling a phone via accessibility services. /no_think
You observe a simplified screen and execute ONE action per turn.

ACTIONS (respond with EXACTLY ONE, nothing else):
TAP [index] — tap element at that index
TAP_TEXT [text] — tap element containing this text
SCROLL [down/up] — scroll the view
TYPE [text] — type into focused input
WAIT — wait for screen to update
DONE [summary] — task complete
ABORT [reason] — task impossible

STRATEGY:
1. READ the screen elements carefully. Elements are listed as [index] Type: "text" (clickable).
2. TRACK PROGRESS from action history. Count successful taps on target elements.
3. IDENTIFY TARGETS by context:
   - Like/heart buttons: "♡", "Like", heart icons, content descriptions with "like" or "love"
   - Posts are grouped: username → content text → action buttons (like, comment, share)
   - To find music posts: look for words near posts: song, track, music, album, artist, beat, DJ, playlist, concert, remix, studio, 🎵, 🎶
   - To find food posts: restaurant, recipe, cooking, food, meal, delicious
4. SCROLL down if current screen lacks targets. New content loads on scroll.
5. After tapping like, SCROLL to find the next post — don't tap the same one.
6. Say DONE when you've completed the requested number of actions.

OUTPUT: Just the action. No explanation. No reasoning.
Good: TAP 5
Good: SCROLL down
Good: DONE Liked 3 posts on Instagram
Bad: I'll tap the heart button. TAP 5<|im_end|>
<|im_start|>user
Task: $task
Step: $stepNum / $maxSteps
$progressNote

Recent actions:
$historyText

Current screen:
$currentScreen

Next action?<|im_end|>
<|im_start|>assistant
""".trimIndent()
    }

    private fun parseAction(response: String): ParsedAction {
        val cleaned = response.let { r ->
            val thinkEnd = r.indexOf("</think>")
            if (thinkEnd >= 0) r.substring(thinkEnd + "</think>".length).trim() else r
        }.trim().lines().first().trim()

        return when {
            cleaned.startsWith("TAP_TEXT ", ignoreCase = true) ->
                ParsedAction("TAP_TEXT", cleaned.substringAfter(" ").trim())
            cleaned.startsWith("TAP ", ignoreCase = true) ->
                ParsedAction("TAP", cleaned.substringAfter(" ").trim())
            cleaned.startsWith("SCROLL ", ignoreCase = true) ->
                ParsedAction("SCROLL", cleaned.substringAfter(" ").trim())
            cleaned.startsWith("TYPE ", ignoreCase = true) ->
                ParsedAction("TYPE", cleaned.substringAfter(" ").trim())
            cleaned.startsWith("WAIT", ignoreCase = true) ->
                ParsedAction("WAIT", null)
            cleaned.startsWith("DONE", ignoreCase = true) ->
                ParsedAction("DONE", cleaned.substringAfter(" ").trim().takeIf { it.isNotBlank() })
            cleaned.startsWith("ABORT", ignoreCase = true) ->
                ParsedAction("ABORT", cleaned.substringAfter(" ").trim().takeIf { it.isNotBlank() })
            else -> {
                Log.w(TAG, "Unparseable LLM action: $cleaned")
                ParsedAction("ABORT", "Could not parse action: ${cleaned.take(50)}")
            }
        }
    }

    private suspend fun executeAction(action: ParsedAction): UiInteractor.InteractionResult {
        return when (action.type) {
            "TAP" -> {
                val index = action.target?.toIntOrNull()
                if (index != null) UiInteractor.tapByIndex(index)
                else UiInteractor.tapByText(action.target ?: "")
            }
            "TAP_TEXT" -> UiInteractor.tapByText(action.target ?: "")
            "SCROLL" -> UiInteractor.scroll(action.target ?: "down")
            "TYPE" -> UiInteractor.typeText(action.target ?: "")
            "WAIT" -> UiInteractor.waitForScreenChange()
            else -> UiInteractor.InteractionResult.Failed("Unknown action: ${action.type}")
        }
    }

    companion object {
        private const val TAG = "AgentLoop"
        const val DEFAULT_MAX_STEPS = 20
    }
}
